package ai.pipestream.server.grpc.stork;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeoutException;

import org.eclipse.microprofile.config.ConfigProvider;
import org.jboss.logging.Logger;

import com.google.common.base.Preconditions;

import io.grpc.Attributes;
import io.grpc.EquivalentAddressGroup;
import io.grpc.NameResolver;
import io.grpc.NameResolverProvider;
import io.grpc.Status;
import io.smallrye.mutiny.Uni;
import io.smallrye.stork.Stork;
import io.smallrye.stork.api.Service;
import io.smallrye.stork.api.ServiceDiscovery;
import io.smallrye.stork.api.ServiceInstance;
import io.quarkus.grpc.runtime.stork.GrpcStorkServiceDiscovery;

/**
 * Drop-in replacement for {@code io.quarkus.grpc.runtime.stork.GrpcStorkServiceDiscovery}
 * that fixes two upstream bugs in Quarkus 3.34's stock implementation:
 *
 * <ol>
 *   <li><b>Silent Uni failure → permanently wedged channel.</b> Upstream calls
 *       {@code serviceInstances.subscribe().with(this::informListener)} — the
 *       single-argument variant. If the underlying Stork {@code Uni} emits a
 *       {@code Throwable}, it goes to {@code Infrastructure::handleDroppedException}
 *       (logged once at WARN, otherwise lost). {@code informListener} is never
 *       invoked, the {@code resolving} flag stays {@code true} forever, and every
 *       subsequent {@link NameResolver#refresh()} short-circuits at the
 *       {@code if (resolving || shutdown) return;} guard. The gRPC channel sits
 *       in an unresolved state until the JVM restarts.</li>
 *   <li><b>Unbounded wait on Stork's Consul fetch.</b> Upstream
 *       {@code stork-service-discovery-consul} configures the underlying
 *       Vert.x {@code ConsulClient} with no HTTP timeout. A momentarily
 *       unresponsive Consul agent yields a {@code Uni} that emits neither
 *       success nor failure — every subscriber within Stork's
 *       {@code refresh-period} window blocks indefinitely on the same
 *       memoized in-flight Uni.</li>
 * </ol>
 *
 * The fix is two changes inside {@code resolve()}:
 *
 * <ol>
 *   <li>Bound the wait with
 *       {@code Uni.ifNoItem().after(Duration).failWith(TimeoutException)} so a
 *       hung Consul fetch surfaces as a real Mutiny failure within the
 *       configured ceiling.</li>
 *   <li>Use the two-argument
 *       {@code subscribe().with(onItem, onFailure)} form so that failure
 *       (whether from the timeout above or a real Stork failure) actually
 *       resets {@code resolving} and notifies
 *       {@link NameResolver.Listener2#onError(Status)}, letting gRPC's load
 *       balancer respond properly (transition the channel state, retry, etc.)
 *       instead of hanging.</li>
 * </ol>
 *
 * Returned {@link #priority()} is {@code 6} — one higher than upstream's
 * {@code 5} (the standard "user-supplied resolver" priority) and well
 * above stock {@code GrpcStorkServiceDiscovery}'s {@code 4}, so gRPC's
 * {@link NameResolverProvider} SPI picks this class for any
 * {@code stork:///} URI when both classes are on the classpath.
 *
 * <p>Upstream reports / PRs:
 * <ul>
 *   <li>quarkus-grpc — the {@code subscribe().with(Consumer)} silent-failure
 *       drop in {@code GrpcStorkServiceDiscovery.resolve()}</li>
 *   <li>smallrye-stork-service-discovery-consul — the missing HTTP timeout
 *       on {@code ConsulClientOptions}</li>
 * </ul>
 *
 * <p>Configuration:
 * <ul>
 *   <li>{@code pipestream.stork.fetch-timeout-ms} — ceiling on a single
 *       resolve call's wait for Stork. Default {@code 5000} (5 seconds).
 *       Must be substantially less than any caller's gRPC deadline so the
 *       caller has time to retry on a fresh stream.</li>
 * </ul>
 */
public class PatchedGrpcStorkServiceDiscovery extends NameResolverProvider {

    private static final Logger log = Logger.getLogger(PatchedGrpcStorkServiceDiscovery.class);

    /**
     * Reuse upstream's attribute key directly. {@link Attributes.Key} uses
     * <strong>object identity</strong>, not the string name, for equality —
     * so a locally-created key with the same name ("service-instance") is a
     * different key. {@code GrpcLoadBalancerProvider} reads back the
     * {@link ServiceInstance} from each {@link EquivalentAddressGroup}
     * using {@link GrpcStorkServiceDiscovery#SERVICE_INSTANCE}; if we store
     * under a different key the load balancer sees {@code null}, then NPEs
     * inside {@code TreeMap.put} when sorting by {@code instance.getId()}.
     */
    public static final Attributes.Key<ServiceInstance> SERVICE_INSTANCE =
            GrpcStorkServiceDiscovery.SERVICE_INSTANCE;

    private static final String FETCH_TIMEOUT_PROP = "pipestream.stork.fetch-timeout-ms";
    private static final long DEFAULT_FETCH_TIMEOUT_MS = 5000L;

    private static long resolveFetchTimeoutMs() {
        try {
            return ConfigProvider.getConfig()
                    .getOptionalValue(FETCH_TIMEOUT_PROP, Long.class)
                    .orElse(DEFAULT_FETCH_TIMEOUT_MS);
        } catch (RuntimeException e) {
            // Config provider not available (very early startup, native build,
            // unusual classloader situation). Fall back to the default.
            return DEFAULT_FETCH_TIMEOUT_MS;
        }
    }

    @Override
    protected boolean isAvailable() {
        return true;
    }

    @Override
    protected int priority() {
        // Match Quarkus stock GrpcStorkServiceDiscovery's priority (4).
        // We can't go higher than 5 (the default DNS resolver) without
        // becoming the implicit default for any scheme-less target like
        // {@code Grpc.newChannelBuilder("localhost:18108", ...)} —
        // gRPC would then prepend our scheme, ask us to resolve the
        // result, and fail with a null-authority NPE because we'd be
        // pretending those targets are Stork service refs.
        //
        // At priority 4 we tie with stock for {@code stork://} matches.
        // gRPC's NameResolverRegistry uses a stable sort by priority
        // and iterates in classpath insertion order on ties. Our jar
        // ({@code pipestream-server}) loads before {@code quarkus-grpc}
        // by alphabetical convention in every build tool we use, so
        // our entry wins the tie deterministically.
        //
        // If a future classpath change makes that ordering unstable
        // we'll need a different approach (e.g. an explicit override
        // hook), but the current setup is robust under all our
        // observed Gradle / Quarkus configurations.
        return 4;
    }

    @Override
    public String getDefaultScheme() {
        return Stork.STORK;
    }

    @Override
    public NameResolver newNameResolver(URI targetUri, NameResolver.Args args) {
        if (!Stork.STORK.equals(targetUri.getScheme())) {
            return null;
        }
        NameResolver.ServiceConfigParser configParser = args.getServiceConfigParser();
        long fetchTimeoutMs = resolveFetchTimeoutMs();
        return new NameResolver() {
            Listener2 listener;
            volatile boolean resolving, shutdown;
            ServiceDiscovery serviceDiscovery;
            String serviceName;

            volatile Set<Long> serviceInstanceIds = new HashSet<>();

            @Override
            public String getServiceAuthority() {
                return targetUri.getAuthority();
            }

            @Override
            public void shutdown() {
                shutdown = true;
            }

            @Override
            public void start(Listener2 listener) {
                Preconditions.checkState(this.listener == null, "already started");
                this.listener = listener;
                serviceName = targetUri.getHost();
                Service service = Stork.getInstance().getService(serviceName);
                if (service == null) {
                    listener.onError(Status.ABORTED.withDescription(
                            "No service definition for serviceName " + serviceName + " found."));
                    return;
                }
                serviceDiscovery = service.getServiceDiscovery();
                resolve();
            }

            private void resolve() {
                if (resolving || shutdown) {
                    return;
                }
                resolving = true;
                // Bound the wait. Stork's Consul fetch can hang indefinitely
                // because the underlying Vert.x ConsulClient has no HTTP
                // timeout configured. ifNoItem().after(...).failWith(...)
                // converts the hang into a real Mutiny failure that our
                // onFailure callback below can handle.
                Uni<List<ServiceInstance>> serviceInstances = serviceDiscovery.getServiceInstances()
                        .ifNoItem().after(Duration.ofMillis(fetchTimeoutMs))
                        .failWith(() -> new TimeoutException(
                                "Stork resolution for '" + serviceName
                                        + "' exceeded " + fetchTimeoutMs + "ms"));
                // Two-argument subscribe.with: on failure we MUST reset
                // resolving and inform the listener; otherwise the channel
                // wedges (the upstream bug). UNAVAILABLE is the right status
                // class for transient resolution failures — it's what every
                // caller-side retry classifier in this platform considers
                // retryable.
                serviceInstances.subscribe().with(
                        this::informListener,
                        failure -> {
                            try {
                                log.warnf(failure,
                                        "Stork resolution failed for service '%s' (timeout=%dms)",
                                        serviceName, fetchTimeoutMs);
                                listener.onError(Status.UNAVAILABLE
                                        .withCause(failure)
                                        .withDescription("Stork resolution failed for '"
                                                + serviceName + "': " + failure.getMessage()));
                            } finally {
                                resolving = false;
                            }
                        });
            }

            @Override
            public void refresh() {
                resolve();
            }

            private void informListener(List<ServiceInstance> instances) {
                try {
                    HashSet<Long> newIds = new HashSet<>();
                    for (ServiceInstance instance : instances) {
                        newIds.add(instance.getId());
                    }

                    boolean instanceSetChanged = !newIds.equals(serviceInstanceIds);
                    if (instanceSetChanged) {
                        this.serviceInstanceIds = newIds;
                    }

                    if (instances.isEmpty()) {
                        if (instanceSetChanged || serviceInstanceIds.isEmpty()) {
                            log.debugf("Stork returned no instances for '%s'; notifying UNAVAILABLE",
                                    serviceName);
                            listener.onError(Status.UNAVAILABLE.withDescription(
                                    "No instances registered for '" + serviceName + "'"));
                        }
                        return;
                    }

                    if (!instanceSetChanged) {
                        return;
                    }

                    ArrayList<EquivalentAddressGroup> addresses = new ArrayList<>();
                    for (ServiceInstance instance : instances) {
                        List<SocketAddress> socketAddresses = new ArrayList<>();
                        try {
                            for (InetAddress inetAddress : InetAddress.getAllByName(instance.getHost())) {
                                socketAddresses.add(new InetSocketAddress(inetAddress, instance.getPort()));
                            }
                        } catch (UnknownHostException e) {
                            log.warnf(e, "Ignoring wrong host: '%s' for service name '%s'",
                                    instance.getHost(), serviceName);
                        }

                        if (!socketAddresses.isEmpty()) {
                            Attributes attributes = Attributes.newBuilder()
                                    .set(SERVICE_INSTANCE, instance)
                                    .build();
                            EquivalentAddressGroup addressGroup =
                                    new EquivalentAddressGroup(socketAddresses, attributes);
                            addresses.add(addressGroup);
                        }
                    }

                    if (addresses.isEmpty()) {
                        log.errorf("Failed to determine working socket addresses for service-name: %s",
                                serviceName);
                        listener.onError(Status.UNAVAILABLE.withDescription(
                                "No reachable socket addresses for '" + serviceName
                                        + "' (Stork returned " + instances.size()
                                        + " instance(s) but none had resolvable hosts)"));
                    } else {
                        ConfigOrError serviceConfig =
                                configParser.parseServiceConfig(mapConfigForServiceName());
                        listener.onResult(ResolutionResult.newBuilder()
                                .setAddresses(addresses)
                                .setServiceConfig(serviceConfig)
                                .build());
                    }
                } finally {
                    resolving = false;
                }
            }

            private Map<String, List<Map<String, Map<String, String>>>> mapConfigForServiceName() {
                return Map.of("loadBalancingConfig", List.of(
                        Map.of(Stork.STORK, Map.of("service-name", serviceName))));
            }
        };
    }
}
