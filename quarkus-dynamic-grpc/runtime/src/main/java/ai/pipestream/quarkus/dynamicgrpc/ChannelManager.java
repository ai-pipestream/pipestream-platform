package ai.pipestream.quarkus.dynamicgrpc;

import ai.pipestream.quarkus.dynamicgrpc.auth.AuthMetadataInterceptor;
import ai.pipestream.quarkus.dynamicgrpc.config.DynamicGrpcConfig;
import ai.pipestream.quarkus.dynamicgrpc.config.DynamicGrpcTlsAdapter;
import ai.pipestream.quarkus.dynamicgrpc.exception.ChannelCreationException;
import ai.pipestream.quarkus.dynamicgrpc.exception.ServiceNotFoundException;
import ai.pipestream.quarkus.dynamicgrpc.metrics.DynamicGrpcMetrics;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import io.grpc.Channel;
import io.grpc.ClientInterceptors;
import io.grpc.ManagedChannel;
import io.grpc.netty.NegotiationType;
import io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.GrpcSslContexts;
import io.smallrye.mutiny.Uni;
import io.smallrye.stork.api.ServiceInstance;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import javax.net.ssl.SSLException;
import java.io.File;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manages Stork-backed gRPC {@link Channel}s for logical service names.
 * <p>
 * One {@link StorkGrpcChannel} per service, cached with Caffeine and
 * evicted after an idle TTL. Stork itself handles load balancing across
 * service instances (round-robin by default; configurable via
 * {@code quarkus.stork.<name>.load-balancer.type}), health-check-driven
 * instance filtering, and periodic instance refresh. This class's job is
 * the caching + lifecycle; the transport-level policy is Stork's.
 *
 * <h2>What this class intentionally is not</h2>
 * <ul>
 *   <li><b>Not a pool of N parallel channels per service.</b> The previous
 *       round-robin-of-StorkGrpcChannels layer collided with Stork's own
 *       round-robin retry logic; at retries &ge; 2 this produced silent
 *       duplicate delivery and drops when the channel-level retry window
 *       raced the caller's deadline. Single channel per service + Stork
 *       {@code retries=1} = no duplication, no silent drops.</li>
 *   <li><b>Not an application-level retry orchestrator.</b> Retries belong
 *       at the call site where they can be paired with idempotency,
 *       backoff, and DLQ. Stork's retries stay at 1 for that reason.</li>
 *   <li><b>Not a place to reinvent Stork.</b> Load balancing, discovery
 *       backend (Consul / Kubernetes / static-list / DNS), instance health,
 *       and refresh cadence are all Stork config. Consumers get those
 *       features by configuring {@code quarkus.stork.<name>.*} — not by
 *       editing this class.</li>
 * </ul>
 */
@ApplicationScoped
public class ChannelManager {

    /** Default constructor for CDI frameworks. */
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
     * Cached per-service channel. The {@link ManagedChannel} owns its
     * underlying Netty connection pool, so on eviction we just call
     * {@link ManagedChannel#shutdownNow()} — no separate transport
     * resource to close.
     */
    private record Entry(Channel channel, ManagedChannel managedChannel) {}

    private Cache<String, Entry> channelCache;
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);

    /** Initializes the cache and registers the active-channel gauge. */
    @PostConstruct
    void init() {
        this.channelCache = Caffeine.newBuilder()
                .expireAfterAccess(Duration.ofMinutes(config.channel().idleTtlMinutes()))
                .maximumSize(config.channel().maxSize())
                .removalListener(this::onChannelRemoved)
                .recordStats()
                .build();

        LOG.infof("Initialized ChannelManager with TTL=%d minutes, max cache size=%d, maxInboundMessageSize=%d, maxOutboundMessageSize=%d",
                config.channel().idleTtlMinutes(),
                config.channel().maxSize(),
                config.channel().maxInboundMessageSize(),
                config.channel().maxOutboundMessageSize());

        if (tlsConfig.enabled()) {
            LOG.infof("TLS Configuration: enabled=true, trustAll=%s, verifyHostname=%s",
                    tlsConfig.trustAll(), tlsConfig.verifyHostname());
        }

        metrics.registerActiveChannelGauge(this::getActiveServiceCount);
    }

    private void onChannelRemoved(String serviceName, Entry entry, RemovalCause cause) {
        if (entry == null) return;

        String evictionReason = switch (cause) {
            case EXPIRED -> "ttl_expired";
            case SIZE -> "size_limit";
            case EXPLICIT -> "manual";
            case REPLACED -> "replaced";
            default -> "other";
        };
        metrics.recordChannelEvicted(serviceName, evictionReason);

        if (shuttingDown.get()) {
            LOG.debugf("Application shutting down, initiating non-blocking channel shutdown for service '%s'", serviceName);
            closeEntryQuietly(entry, serviceName);
            return;
        }

        LOG.infof("Evicting gRPC channel for service '%s' due to: %s", serviceName, cause);
        closeEntryQuietly(entry, serviceName);
    }

    private void closeEntryQuietly(Entry entry, String serviceName) {
        ManagedChannel mc = entry.managedChannel();
        if (mc == null) {
            return;
        }
        try {
            mc.shutdown();
            if (!mc.awaitTermination(5, TimeUnit.SECONDS)) {
                mc.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            mc.shutdownNow();
        } catch (Exception e) {
            LOG.tracef("Error closing channel for service %s: %s", serviceName, e.getMessage());
        }
    }

    /**
     * Gets or creates a gRPC Channel for the given service.
     *
     * @param serviceName the logical service name used for discovery and caching
     * @param instances   the list of discovered service instances (must be non-empty)
     * @return a Uni that emits the Channel when ready
     * @throws ServiceNotFoundException if no instances are found for the service
     * @throws ChannelCreationException if channel creation fails
     */
    public Uni<Channel> getOrCreateChannel(String serviceName, List<ServiceInstance> instances) {
        if (instances == null || instances.isEmpty()) {
            return Uni.createFrom().failure(
                    new ServiceNotFoundException(serviceName, "No service instances available")
            );
        }

        if (shuttingDown.get()) {
            return Uni.createFrom().failure(
                    new ChannelCreationException(serviceName, "Channel manager is shutting down")
            );
        }

        if (channelCache == null) {
            init();
        }

        // Caffeine.get(key, loader) is atomic — concurrent callers for the
        // same serviceName all wait on a single loader invocation.
        try {
            Entry entry = channelCache.get(serviceName, key -> {
                LOG.infof("Creating new Stork gRPC channel for service: %s", key);
                metrics.recordCacheMiss(key);
                return createEntry(key);
            });
            LOG.tracef("Returning gRPC channel for service: %s", serviceName);
            return Uni.createFrom().item(entry.channel());
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            LOG.errorf(cause, "Failed to create gRPC channel for service: %s", serviceName);
            metrics.recordException(cause.getClass().getSimpleName(), serviceName, "channel_creation");
            return Uni.createFrom().failure(
                    new ChannelCreationException(serviceName, "Channel creation failed", cause)
            );
        }
    }

    /**
     * Builds one Netty-backed {@link ManagedChannel} for
     * {@code serviceName} that uses {@link StorkNameResolverProvider} for
     * service discovery. Returned wrapped in an {@link Entry} so the
     * channel is shut down together with its cache eviction.
     *
     * <p>This intentionally bypasses the Vert.x gRPC client. The
     * Vert.x-backed path (the previous {@code StorkGrpcChannel +
     * GrpcClient}) silently dropped streams under direct-buffer pressure,
     * masking the underlying {@link OutOfMemoryError} as
     * {@code Status.INTERNAL "Half-closed without a request"}. The
     * standard grpc-java/Netty client surfaces the same condition cleanly
     * (RESOURCE_EXHAUSTED or a thrown OOM) and gives roughly 3× the
     * throughput on PipeDoc-class concurrent payloads. See
     * {@code LargePayloadConcurrencyTest} in the integration-tests
     * module for the regression guard.
     */
    private Entry createEntry(String serviceName) {
        NettyChannelBuilder builder = NettyChannelBuilder
                .forTarget(StorkNameResolverProvider.SCHEME + ":///" + serviceName)
                // STORK_RESOLVER_FACTORY is also registered globally via
                // META-INF/services, but wiring it explicitly avoids any
                // ServiceLoader visibility surprises in shaded/native builds.
                .nameResolverFactory(STORK_RESOLVER_FACTORY)
                // round_robin matches the rotation behavior the previous
                // StorkGrpcChannel provided. Stork's own LB policies stay
                // upstream of the resolver and can still filter instances.
                .defaultLoadBalancingPolicy("round_robin")
                .flowControlWindow(config.channel().flowControlWindow())
                .maxInboundMessageSize(config.channel().maxInboundMessageSize())
                // keep-alive only when there are pending calls; pinging an
                // idle channel would race with HTTP/2 connection setup on
                // freshly-resolved addresses (observed: FailureRecoveryTest
                // testServiceCrashAndRestart hit "Connection refused" when
                // keepAliveWithoutCalls=true raced first-RPC connect).
                .keepAliveTime(30, TimeUnit.SECONDS)
                .keepAliveTimeout(10, TimeUnit.SECONDS);

        // HTTP/2 connection pooling. Vert.x defaults to 1 connection per host
        // with unlimited stream multiplexing — a serialisation point under
        // pipeline load (observed 2026-04-24: 1/100 uploadPipeDoc timed out
        // at 30s on a single overloaded connection).
        httpOptions.setHttp2MaxPoolSize(config.channel().http2MaxPoolSize());
        httpOptions.setHttp2MultiplexingLimit(config.channel().http2MultiplexingLimit());

        // HTTP/2 flow control. The spec default of 65535 bytes is orders of
        // magnitude below typical pipeline payload sizes; streams carrying
        // PipeDocs exhaust it in one frame and park waiting for WINDOW_UPDATE.
        // Set both the connection window and the client's advertised stream
        // SETTINGS_INITIAL_WINDOW_SIZE to match the server-side setting
        // (quarkus.grpc.server.flow-control-window, typically 100 MB).
        int flowWindow = config.channel().flowControlWindow();
        httpOptions.setHttp2ConnectionWindowSize(flowWindow);
        httpOptions.setInitialSettings(new Http2Settings().setInitialWindowSize(flowWindow));

        if (tlsConfig.enabled()) {
            applyTls(builder, serviceName);
        } else {
            builder.negotiationType(NegotiationType.PLAINTEXT);
        }

        ManagedChannel managed = builder.build();
        Channel finalChannel = config.auth().enabled()
                ? ClientInterceptors.intercept(managed, authInterceptor)
                : managed;
        metrics.recordChannelCreated(serviceName);
        LOG.infof("Created Netty ManagedChannel for %s (target=%s:///%s, flowWindow=%d)",
                serviceName, StorkNameResolverProvider.SCHEME, serviceName,
                config.channel().flowControlWindow());
        return new Entry(finalChannel, managed);
    }

    /**
     * Configures TLS on a NettyChannelBuilder from the platform's
     * {@link DynamicGrpcTlsAdapter}. Maps to the standard grpc-java
     * {@link GrpcSslContexts} client builder:
     * <ul>
     *   <li>{@code trustAll} → {@code InsecureTrustManagerFactory}</li>
     *   <li>{@code trustCertificatePem.certs} → first entry as the trust manager file</li>
     *   <li>{@code keyCertificatePem.certs/keys} → first entry pair as client cert/key</li>
     * </ul>
     * JKS/PFX paths from the existing adapter are intentionally not
     * wired — pipestream prod uses PEM for both trust and client certs;
     * if that ever changes the Quarkus SSLConfigHelper-equivalent path
     * is the place to add JKS/PFX support.
     */
    private void applyTls(NettyChannelBuilder builder, String serviceName) {
        LOG.debugf("Configuring TLS for service: %s", serviceName);
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

            builder.sslContext(ssl.build())
                    .negotiationType(NegotiationType.TLS);

            if (!tlsConfig.verifyHostname()) {
                // Caller has explicitly opted out of hostname verification.
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
     * Manually evicts a channel for a service from the cache.
     *
     * @param serviceName the service whose channel should be removed
     */
    public void evictChannel(String serviceName) {
        LOG.infof("Manually evicting channel for service: %s", serviceName);
        channelCache.invalidate(serviceName);
    }

    /**
     * Gets cache statistics for monitoring.
     *
     * @return a human-readable summary of cache statistics
     */
    public String getCacheStats() {
        var stats = channelCache.stats();
        metrics.updateCacheStats(
                stats.hitCount(),
                stats.missCount(),
                stats.evictionCount(),
                channelCache.estimatedSize()
        );
        return String.format("Cache stats - Size: %d, Hits: %d, Misses: %d, Hit rate: %.2f%%, Evictions: %d",
                channelCache.estimatedSize(),
                stats.hitCount(),
                stats.missCount(),
                stats.hitRate() * 100,
                stats.evictionCount());
    }

    /**
     * Gets the number of active services with cached channels.
     *
     * @return approximate count of active services
     */
    public int getActiveServiceCount() {
        return Math.toIntExact(channelCache.estimatedSize());
    }

    /** Shuts down all channels on application exit. */
    @PreDestroy
    void cleanup() {
        shuttingDown.set(true);

        if (channelCache == null) {
            LOG.debug("No channel cache to clean up");
            return;
        }

        LOG.infof("Shutting down %d cached gRPC channels on application exit...", channelCache.estimatedSize());

        List<Entry> entries = new ArrayList<>(channelCache.asMap().values());
        channelCache.invalidateAll();
        channelCache.cleanUp();

        ExecutorService shutdownExecutor = Executors.newSingleThreadExecutor();
        try {
            shutdownExecutor.submit(() -> {
                for (Entry entry : entries) {
                    closeEntryQuietly(entry, "<shutdown>");
                }
            }).get(config.channel().shutdownTimeoutSeconds(), TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            LOG.warn("Channel shutdown timed out, forcing immediate termination");
            entries.forEach(entry -> closeEntryQuietly(entry, "<shutdown-forced>"));
        } catch (Exception e) {
            LOG.error("Error during channel cleanup", e);
        } finally {
            shutdownExecutor.shutdownNow();
        }

        LOG.info("ChannelManager cleanup complete.");
    }
}
