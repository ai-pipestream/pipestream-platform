package ai.pipestream.quarkus.dynamicgrpc;

import io.grpc.EquivalentAddressGroup;
import io.grpc.NameResolver;
import io.grpc.NameResolverProvider;
import io.grpc.Status;
import io.grpc.SynchronizationContext;
import io.smallrye.stork.Stork;
import io.smallrye.stork.api.Service;
import io.smallrye.stork.api.ServiceInstance;
import org.jboss.logging.Logger;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * Bridges SmallRye Stork service discovery into the grpc-java
 * {@link NameResolver} SPI so a {@code NettyChannelBuilder} can
 * use Stork-resolved addresses (Consul, static-list, Kubernetes, …)
 * with no Vert.x in the data path.
 *
 * <p>Channels are built with {@code NettyChannelBuilder.forTarget("stork:///<name>")};
 * grpc-java picks the matching scheme via {@link NameResolverProvider}
 * SPI (registered through {@code META-INF/services/io.grpc.NameResolverProvider})
 * or by being passed explicitly to
 * {@code NettyChannelBuilder.nameResolverFactory(...)}. The resolver
 * issues {@link Service#getInstances()} on {@link NameResolver#start} and
 * on every {@link NameResolver#refresh}, so Stork's own discovery cache
 * (Consul TTL, static refresh period, etc.) drives freshness — we don't
 * re-implement it.
 *
 * <p>This intentionally does not implement load balancing — pair the
 * resulting channel with {@code .defaultLoadBalancingPolicy("round_robin")}
 * (or whatever the call site needs) so grpc-java's built-in balancer
 * handles instance rotation.
 */
public class StorkNameResolverProvider extends NameResolverProvider {

    public static final String SCHEME = "stork";

    private static final Logger LOG = Logger.getLogger(StorkNameResolverProvider.class);

    @Override
    public String getDefaultScheme() {
        return SCHEME;
    }

    @Override
    protected boolean isAvailable() {
        return true;
    }

    @Override
    protected int priority() {
        // 5 is the standard "user-supplied resolver" priority — above the
        // grpc-java DNS default (5 too) but the explicit nameResolverFactory()
        // wiring on the channel builder takes precedence anyway.
        return 5;
    }

    @Override
    public NameResolver newNameResolver(URI targetUri, NameResolver.Args args) {
        if (!SCHEME.equals(targetUri.getScheme())) {
            return null;
        }
        // forTarget("stork:///foo") puts "foo" in the path with a leading slash;
        // forTarget("stork://foo")  puts "foo" in the authority. Accept both.
        String serviceName = targetUri.getPath();
        if (serviceName == null || serviceName.isEmpty() || serviceName.equals("/")) {
            serviceName = targetUri.getAuthority();
        } else if (serviceName.startsWith("/")) {
            serviceName = serviceName.substring(1);
        }
        if (serviceName == null || serviceName.isEmpty()) {
            throw new IllegalArgumentException(
                    "stork:// target URI must include a service name: " + targetUri);
        }
        return new StorkNameResolver(serviceName, args.getSynchronizationContext());
    }

    /**
     * Per-channel resolver. Re-queries Stork on start and on refresh.
     *
     * <p>grpc-java's {@link NameResolver} contract requires {@code Listener2.onResult}
     * and {@code Listener2.onError} to run inside the channel's
     * {@link SynchronizationContext} so the load-balancer (round_robin etc.)
     * sees a serial, single-threaded view of resolution updates. Stork's
     * {@code service.getInstances()} fires its callbacks on the Mutiny scheduler,
     * NOT on the syncCtx — so this class dispatches every listener call back
     * through {@link SynchronizationContext#execute(Runnable)}.
     */
    static final class StorkNameResolver extends NameResolver {

        private final String serviceName;
        private final SynchronizationContext syncCtx;
        private volatile Listener2 listener;
        private volatile boolean shutdown;

        StorkNameResolver(String serviceName, SynchronizationContext syncCtx) {
            this.serviceName = serviceName;
            this.syncCtx = syncCtx;
        }

        @Override
        public String getServiceAuthority() {
            return serviceName;
        }

        @Override
        public void start(Listener2 listener) {
            this.listener = listener;
            resolve();
        }

        @Override
        public void refresh() {
            if (!shutdown) {
                resolve();
            }
        }

        @Override
        public void shutdown() {
            shutdown = true;
        }

        private void resolve() {
            Listener2 l = listener;
            if (l == null || shutdown) {
                return;
            }
            Service service;
            try {
                service = Stork.getInstance().getService(serviceName);
            } catch (RuntimeException e) {
                deliverError(l, Status.UNAVAILABLE
                        .withCause(e)
                        .withDescription("Stork has no service registered as '" + serviceName + "'"));
                return;
            }
            if (service == null) {
                deliverError(l, Status.UNAVAILABLE.withDescription(
                        "Stork has no service registered as '" + serviceName + "'"));
                return;
            }
            service.getInstances()
                    .subscribe()
                    .with(
                            instances -> publish(l, instances),
                            failure -> publishError(l, failure));
        }

        private void publish(Listener2 l, List<? extends ServiceInstance> instances) {
            if (instances == null || instances.isEmpty()) {
                deliverError(l, Status.UNAVAILABLE.withDescription(
                        "Stork returned 0 instances for service '" + serviceName + "'"));
                return;
            }
            List<EquivalentAddressGroup> groups = new ArrayList<>(instances.size());
            for (ServiceInstance si : instances) {
                groups.add(new EquivalentAddressGroup(
                        new InetSocketAddress(si.getHost(), si.getPort())));
            }
            LOG.tracef("Stork resolution for '%s' produced %d address group(s)",
                    serviceName, groups.size());
            NameResolver.ResolutionResult result = NameResolver.ResolutionResult.newBuilder()
                    .setAddresses(groups)
                    .build();
            // Hop to the channel's syncCtx so load-balancer state-machine
            // updates remain serial. See class javadoc.
            syncCtx.execute(() -> l.onResult(result));
        }

        private void publishError(Listener2 l, Throwable failure) {
            LOG.warnf(failure, "Stork resolution failed for '%s'", serviceName);
            deliverError(l, Status.UNAVAILABLE
                    .withCause(failure)
                    .withDescription("Stork resolution failed for '" + serviceName + "': "
                            + failure.getMessage()));
        }

        private void deliverError(Listener2 l, Status status) {
            syncCtx.execute(() -> l.onError(status));
        }
    }
}
