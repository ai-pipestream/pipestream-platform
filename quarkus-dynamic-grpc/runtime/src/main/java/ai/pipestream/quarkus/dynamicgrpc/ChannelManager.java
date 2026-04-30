package ai.pipestream.quarkus.dynamicgrpc;

import ai.pipestream.quarkus.dynamicgrpc.auth.AuthMetadataInterceptor;
import ai.pipestream.quarkus.dynamicgrpc.config.DynamicGrpcConfig;
import ai.pipestream.quarkus.dynamicgrpc.config.DynamicGrpcTlsAdapter;
import ai.pipestream.quarkus.dynamicgrpc.exception.ChannelCreationException;
import ai.pipestream.quarkus.dynamicgrpc.exception.ServiceNotFoundException;
import ai.pipestream.quarkus.dynamicgrpc.metrics.DynamicGrpcMetrics;
import io.grpc.Channel;
import io.grpc.ClientInterceptor;
import io.grpc.ClientInterceptors;
import io.grpc.ManagedChannel;
import io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.NegotiationType;
import io.grpc.netty.NettyChannelBuilder;
import io.smallrye.mutiny.Uni;
import io.smallrye.stork.api.ServiceInstance;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import javax.net.ssl.SSLException;
import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One {@link ManagedChannel} per logical service for the JVM lifetime.
 * <p>
 * Backed by a plain {@link ConcurrentHashMap} — no Caffeine, no idle TTL,
 * no eviction listener. There are roughly a dozen logical services in this
 * platform; gRPC's own subchannel pool inside each {@link ManagedChannel}
 * already handles idle connections, transport pings, and reconnects.
 * Caching the {@link ManagedChannel} itself adds nothing.
 *
 * <h2>Stork name resolution + load balancing</h2>
 *
 * <p>Channels are built with
 * {@code NettyChannelBuilder.forTarget("stork:///<name>")} and explicitly
 * configured with {@link StorkNameResolverProvider} as the resolver
 * factory plus {@code round_robin} as the load-balancing policy. Stork
 * handles instance discovery (Consul / static / Kubernetes), instance
 * health, and refresh cadence; grpc-java handles connection management
 * and per-call instance rotation.
 *
 * <h2>Why every channel goes through {@link ContextDetachingInterceptor}</h2>
 *
 * <p>Calls made from inside a Caffeine async-cache loader or other
 * fire-and-forget async chain inherit {@link io.grpc.Context#current()} at
 * call-start time. When the original caller's Vertx / gRPC-server Context
 * unwinds, any in-flight outbound call dies as
 * {@code CANCELLED: io.grpc.Context was cancelled without error}. The
 * interceptor pins outbound calls to {@link io.grpc.Context#ROOT} so they
 * survive caller unwinding.
 */
@ApplicationScoped
public class ChannelManager {

    /** Default constructor for CDI. */
    public ChannelManager() {}

    private static final Logger LOG = Logger.getLogger(ChannelManager.class);

    @Inject
    DynamicGrpcMetrics metrics;

    @Inject
    DynamicGrpcConfig config;

    @Inject
    DynamicGrpcTlsAdapter tlsConfig;

    @Inject
    AuthMetadataInterceptor authInterceptor;

    /**
     * One per service. {@code channel} is what callers use (carries the
     * client-interceptor chain); {@code managed} is the underlying
     * {@link ManagedChannel} we own the lifecycle of.
     */
    private record Entry(Channel channel, ManagedChannel managed) {}

    private final Map<String, Entry> channels = new ConcurrentHashMap<>();
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);

    /**
     * Gets or creates a gRPC Channel for the given service. Channels are
     * built once per service and reused for the JVM lifetime — gRPC's own
     * subchannel pool handles idle connections.
     *
     * @param serviceName logical service name
     * @param instances   discovered instances; only consulted on first
     *                    creation since the long-lived channel uses its
     *                    own {@link StorkNameResolverProvider} thereafter
     */
    public Uni<Channel> getOrCreateChannel(String serviceName, List<ServiceInstance> instances) {
        if (instances == null || instances.isEmpty()) {
            return Uni.createFrom().failure(
                    new ServiceNotFoundException(serviceName, "No service instances available"));
        }
        if (shuttingDown.get()) {
            return Uni.createFrom().failure(
                    new ChannelCreationException(serviceName, "Channel manager is shutting down"));
        }
        try {
            Entry entry = channels.computeIfAbsent(serviceName, this::createEntry);
            return Uni.createFrom().item(entry.channel());
        } catch (RuntimeException e) {
            LOG.errorf(e, "Failed to create gRPC channel for service: %s", serviceName);
            metrics.recordException(e.getClass().getSimpleName(), serviceName, "channel_creation");
            return Uni.createFrom().failure(
                    new ChannelCreationException(serviceName, "Channel creation failed", e));
        }
    }

    private Entry createEntry(String serviceName) {
        LOG.infof("Creating Stork-resolved gRPC channel for service: %s", serviceName);
        metrics.recordCacheMiss(serviceName);

        NettyChannelBuilder builder = NettyChannelBuilder
                .forTarget(StorkNameResolverProvider.SCHEME + ":///" + serviceName)
                .nameResolverFactory(STORK_RESOLVER_FACTORY)
                .defaultLoadBalancingPolicy("round_robin")
                .flowControlWindow(config.channel().flowControlWindow())
                .maxInboundMessageSize(config.channel().maxInboundMessageSize())
                .keepAliveTime(30, TimeUnit.SECONDS)
                .keepAliveTimeout(10, TimeUnit.SECONDS);

        if (tlsConfig.enabled()) {
            applyTls(builder, serviceName);
        } else {
            builder.negotiationType(NegotiationType.PLAINTEXT);
        }

        ManagedChannel managed = builder.build();
        Channel intercepted = ClientInterceptors.intercept(managed, buildInterceptors());
        metrics.recordChannelCreated(serviceName);
        return new Entry(intercepted, managed);
    }

    /**
     * Builds the per-call interceptor chain, in order:
     * <ol>
     *   <li>{@link ContextDetachingInterceptor} — pins call to
     *       {@code Context.ROOT} so async-loader calls survive caller
     *       Context cancellation.</li>
     *   <li>{@link AuthMetadataInterceptor} — only when
     *       {@code dynamic-grpc.auth.enabled} is set.</li>
     * </ol>
     */
    private ClientInterceptor[] buildInterceptors() {
        ClientInterceptor contextDetacher = new ContextDetachingInterceptor();
        if (config.auth().enabled()) {
            return new ClientInterceptor[]{contextDetacher, authInterceptor};
        }
        return new ClientInterceptor[]{contextDetacher};
    }

    private void applyTls(NettyChannelBuilder builder, String serviceName) {
        try {
            io.netty.handler.ssl.SslContextBuilder ssl = GrpcSslContexts.forClient();
            if (tlsConfig.trustAll()) {
                ssl.trustManager(io.netty.handler.ssl.util.InsecureTrustManagerFactory.INSTANCE);
            } else {
                tlsConfig.trustCertificatePem().certs()
                        .filter(certs -> !certs.isEmpty())
                        .ifPresent(certs -> ssl.trustManager(new File(certs.get(0))));
            }
            var keyPem = tlsConfig.keyCertificatePem();
            var keyCerts = keyPem.certs().orElse(List.of());
            var keyKeys = keyPem.keys().orElse(List.of());
            if (!keyCerts.isEmpty() && !keyKeys.isEmpty()) {
                ssl.keyManager(new File(keyCerts.get(0)), new File(keyKeys.get(0)));
            }
            builder.sslContext(ssl.build()).negotiationType(NegotiationType.TLS);
            if (!tlsConfig.verifyHostname()) {
                builder.overrideAuthority(serviceName);
            }
        } catch (SSLException e) {
            throw new IllegalStateException(
                    "Failed to build TLS context for dynamic-grpc service " + serviceName, e);
        }
    }

    private static final StorkNameResolverProvider STORK_RESOLVER_FACTORY =
            new StorkNameResolverProvider();

    /**
     * Removes a channel for a service. Mostly retained for backwards
     * compatibility — callers shouldn't normally need to evict, since
     * channels are sized for the JVM lifetime.
     */
    public void evictChannel(String serviceName) {
        Entry removed = channels.remove(serviceName);
        if (removed != null) {
            shutdownChannel(removed.managed(), serviceName);
        }
    }

    /** Number of active service channels. */
    public int getActiveServiceCount() {
        return channels.size();
    }

    /**
     * Tiny diagnostic string. Kept for callers that already log this on
     * an admin endpoint; no Caffeine stats are tracked anymore so this
     * just reports the current size.
     */
    public String getCacheStats() {
        return "Active channels: " + channels.size();
    }

    @PreDestroy
    void cleanup() {
        shuttingDown.set(true);
        LOG.infof("Shutting down %d gRPC channels on application exit", channels.size());
        channels.forEach((name, entry) -> shutdownChannel(entry.managed(), name));
        channels.clear();
    }

    private static void shutdownChannel(ManagedChannel mc, String serviceName) {
        try {
            mc.shutdown();
            if (!mc.awaitTermination(5, TimeUnit.SECONDS)) {
                mc.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            mc.shutdownNow();
        } catch (RuntimeException e) {
            LOG.tracef("Error closing channel for %s: %s", serviceName, e.getMessage());
        }
    }
}
