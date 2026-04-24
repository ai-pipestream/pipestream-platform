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
import io.quarkus.grpc.runtime.config.GrpcClientConfiguration;
import io.quarkus.grpc.runtime.stork.StorkGrpcChannel;
import io.quarkus.grpc.runtime.supports.SSLConfigHelper;
import io.smallrye.mutiny.Uni;
import io.smallrye.stork.api.ServiceInstance;
import io.vertx.core.Vertx;
import io.vertx.core.http.Http2Settings;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.grpc.client.GrpcClient;
import io.vertx.grpc.client.GrpcClientOptions;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
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
    Vertx vertx;

    @Inject
    Executor executor;

    @Inject
    DynamicGrpcMetrics metrics;

    @Inject
    DynamicGrpcConfig config;

    @Inject
    DynamicGrpcTlsAdapter tlsConfig;

    @Inject
    AuthMetadataInterceptor authInterceptor;

    /** Cached per-service channel + its backing {@link GrpcClient}. Closed together on eviction. */
    private record Entry(Channel channel, GrpcClient grpcClient) {}

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
        try {
            if (entry.channel() instanceof ManagedChannel mc) {
                mc.shutdownNow();
            } else if (entry.channel() instanceof StorkGrpcChannel sgc) {
                sgc.close();
            }
        } catch (Exception e) {
            LOG.tracef("Error closing channel for service %s: %s", serviceName, e.getMessage());
        }
        try {
            entry.grpcClient().close();
        } catch (Exception e) {
            LOG.tracef("Error closing GrpcClient for service %s: %s", serviceName, e.getMessage());
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
     * Builds one {@link StorkGrpcChannel} for {@code serviceName} plus its
     * backing Vert.x {@link GrpcClient}. Returned as an {@link Entry} so the
     * two close together on eviction. Called inside Caffeine's atomic loader.
     */
    private Entry createEntry(String serviceName) {
        GrpcClient grpcClient = buildGrpcClient(serviceName);
        try {
            Channel raw = new StorkGrpcChannel(grpcClient, serviceName, buildStorkConfig(), executor);
            Channel finalChannel = config.auth().enabled()
                    ? ClientInterceptors.intercept(raw, authInterceptor)
                    : raw;
            metrics.recordChannelCreated(serviceName);
            LOG.infof("Created StorkGrpcChannel for %s", serviceName);
            return new Entry(finalChannel, grpcClient);
        } catch (RuntimeException e) {
            try { grpcClient.close(); } catch (Exception ignore) { /* shutting down */ }
            throw e;
        }
    }

    private GrpcClient buildGrpcClient(String serviceName) {
        HttpClientOptions httpOptions = new HttpClientOptions();
        httpOptions.setHttp2ClearTextUpgrade(false);

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
            LOG.debugf("Configuring TLS for service: %s", serviceName);
            httpOptions.setSsl(true);
            httpOptions.setUseAlpn(true);
            httpOptions.setTrustAll(tlsConfig.trustAll());

            SSLConfigHelper.configurePemTrustOptions(httpOptions, tlsConfig.trustCertificatePem());
            SSLConfigHelper.configureJksTrustOptions(httpOptions, tlsConfig.trustCertificateJks());
            SSLConfigHelper.configurePfxTrustOptions(httpOptions, tlsConfig.trustCertificateP12());

            SSLConfigHelper.configurePemKeyCertOptions(httpOptions, tlsConfig.keyCertificatePem());
            SSLConfigHelper.configureJksKeyCertOptions(httpOptions, tlsConfig.keyCertificateJks());
            SSLConfigHelper.configurePfxKeyCertOptions(httpOptions, tlsConfig.keyCertificateP12());

            httpOptions.setVerifyHost(tlsConfig.verifyHostname());
        }

        GrpcClientOptions clientOptions = new GrpcClientOptions()
                .setTransportOptions(httpOptions)
                .setMaxMessageSize(config.channel().maxInboundMessageSize());

        return GrpcClient.client(vertx, clientOptions);
    }

    private GrpcClientConfiguration.StorkConfig buildStorkConfig() {
        return new GrpcClientConfiguration.StorkConfig() {
            @Override
            public int threads() {
                return 10;
            }

            @Override
            public long deadline() {
                return config.channel().deadlineMs();
            }

            @Override
            public int retries() {
                // Channel-level retries caused silent duplicate delivery and
                // drops when the retry window raced the caller's deadline
                // (observed 2026-04-22: retries=3 dropped 10-12/1000 with
                // zero exceptions reaching the caller). Retries belong at
                // the call site. Default 1 = fail-fast.
                return config.channel().storkRetries();
            }

            @Override
            public long delay() {
                return 60;
            }

            @Override
            public long period() {
                return 120;
            }
        };
    }

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
