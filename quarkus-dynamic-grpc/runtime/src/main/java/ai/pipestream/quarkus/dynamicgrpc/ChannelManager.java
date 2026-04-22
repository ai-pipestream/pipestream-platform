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
import io.quarkus.grpc.runtime.stork.StorkGrpcChannel;
import io.quarkus.grpc.runtime.config.GrpcClientConfiguration;
import io.quarkus.grpc.runtime.supports.SSLConfigHelper;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.net.PemKeyCertOptions;
import io.vertx.core.net.PemTrustOptions;
import io.vertx.grpc.client.GrpcClient;
import io.vertx.grpc.client.GrpcClientOptions;
import io.vertx.core.Vertx;
import io.smallrye.mutiny.Uni;
import io.smallrye.stork.api.ServiceInstance;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.ConfigProvider;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manages gRPC Channels for services.
 * <p>
 * Channels are cached with Caffeine and automatically evicted after an idle TTL.
 * On eviction or application shutdown, channels are shut down gracefully.
 * </p>
 */
@ApplicationScoped
public class ChannelManager {

    /**
     * Default constructor for CDI frameworks.
     */
    public ChannelManager() {
    }

    private static final Logger LOG = Logger.getLogger(ChannelManager.class);

    /**
     * MP-Config property prefix for per-service round-robin pool size overrides.
     * Must mirror {@link DynamicGrpcConfig}'s {@code @ConfigMapping(prefix=...)}
     * so direct lookups (needed for service names containing dashes that
     * Quarkus 3.34's @ConfigMapping Map keys mangle) resolve from the same
     * namespace as the structured config. Touch one, touch the other.
     */
    static final String PER_SERVICE_OVERRIDE_PREFIX = "quarkus.dynamic-grpc.channel.per-service.";

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

    private Cache<String, Channel> channelCache;
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);

    /**
     * Initializes the channel cache and logs effective settings.
     * Invoked automatically by CDI when the application starts.
     */
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

        // Register active channel gauge
        metrics.registerActiveChannelGauge(this::getActiveServiceCount);
    }

    /**
     * Handles cache eviction by gracefully shutting down removed channels.
     *
     * @param serviceName logical service name used as cache key
     * @param channel     the channel instance being removed
     * @param cause       reason for eviction
     */
    private void onChannelRemoved(String serviceName, Channel channel, RemovalCause cause) {
        if (channel == null) return;

        // Record metrics for eviction
        String evictionReason = switch (cause) {
            case EXPIRED -> "ttl_expired";
            case SIZE -> "size_limit";
            case EXPLICIT -> "manual";
            case REPLACED -> "replaced";
            default -> "other";
        };
        metrics.recordChannelEvicted(serviceName, evictionReason);

        // Channel may be wrapped by ClientInterceptors (auth) — unwrap is not
        // exposed on the Channel API, but the auth wrapper doesn't own any
        // I/O resources; it just forwards newCall. The underlying pool /
        // managed channel is what actually needs closing. For the auth-wrapped
        // case we lost the pool reference, which is fine: the GC will clean up
        // once this bean is unreachable. All other cases we close explicitly.
        if (shuttingDown.get()) {
            LOG.debugf("Application shutting down, initiating non-blocking channel shutdown for service '%s'", serviceName);
            closeChannelQuietly(channel, serviceName);
            return;
        }

        LOG.infof("Evicting gRPC channel for service '%s' due to: %s", serviceName, cause);

        try {
            if (channel instanceof ManagedChannel mc) {
                mc.shutdown();
                if (!mc.awaitTermination(500, TimeUnit.MILLISECONDS)) {
                    LOG.warnf("Channel for service %s did not terminate gracefully, forcing shutdown", serviceName);
                    mc.shutdownNow();
                }
            } else if (channel instanceof RoundRobinChannel rrc) {
                rrc.close();
            } else if (channel instanceof StorkGrpcChannel sgc) {
                sgc.close();
            }
            LOG.debugf("Successfully shut down channel for service: %s", serviceName);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.errorf("Interrupted while shutting down channel for service %s", serviceName);
            closeChannelQuietly(channel, serviceName);
        } catch (Exception e) {
            LOG.errorf(e, "Error shutting down channel for service %s", serviceName);
            closeChannelQuietly(channel, serviceName);
        }
    }

    private void closeChannelQuietly(Channel channel, String serviceName) {
        try {
            if (channel instanceof ManagedChannel mc) {
                mc.shutdownNow();
            } else if (channel instanceof RoundRobinChannel rrc) {
                rrc.close();
            } else if (channel instanceof StorkGrpcChannel sgc) {
                sgc.close();
            }
        } catch (Exception e) {
            LOG.tracef("Error during quiet shutdown of channel for service %s: %s", serviceName, e.getMessage());
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

        // Caffeine's get(key, mappingFunction) is atomic — only one thread creates
        // the channel for a given serviceName, others wait for the result.
        // This prevents the TOCTOU race condition where concurrent calls to
        // getIfPresent() all see a miss and create duplicate channels.
        try {
            Channel channel = channelCache.get(serviceName, key -> {
                LOG.infof("Creating new Stork gRPC channel for service: %s", key);
                metrics.recordCacheMiss(key);
                return createChannelSync(key);
            });

            // If we got here via the loader, it was a miss. Otherwise a hit.
            // Caffeine doesn't distinguish, so we record hits separately.
            // The miss is recorded inside the loader above.
            LOG.tracef("Returning gRPC channel for service: %s", serviceName);
            return Uni.createFrom().item(channel);
        } catch (Exception e) {
            // Caffeine wraps loader exceptions in CompletionException
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            LOG.errorf(cause, "Failed to create gRPC channel for service: %s", serviceName);
            metrics.recordException(cause.getClass().getSimpleName(), serviceName, "channel_creation");
            return Uni.createFrom().failure(
                    new ChannelCreationException(serviceName, "Channel creation failed", cause)
            );
        }
    }

    /**
     * Synchronously creates a gRPC channel <i>pool</i> for the given service.
     * Called inside Caffeine's atomic loader — guaranteed single-threaded per key.
     * <p>
     * Returns a {@link RoundRobinChannel} that holds N delegate {@link StorkGrpcChannel}s
     * (N = {@code config.channel().channelsPerService()}), each built on its own
     * {@link GrpcClient}. Round-robined per {@code newCall} so concurrent gRPC
     * calls spread over N independent connections on the receiving service
     * rather than multiplexing on one.
     * <p>
     * Delegate construction runs in parallel across the injected {@code executor}
     * so a pool of 8 costs ~1 Stork resolution wall-clock, not 8. Without this,
     * the first caller who triggers channel creation for an N=8 pool pays 8× the
     * serial Stork lookup cost while their gRPC request sits timing out —
     * observed as intake's {@code uploadPipeDoc} 30s timeouts on the first crawl
     * after restart.
     */
    /**
     * One slot of a {@link RoundRobinChannel} pool: a {@link Channel} bound
     * to the {@link GrpcClient} that backs its HTTP/2 connection pool. Both
     * are closed together via {@link #closeSlot(Slot)} on cleanup or eviction.
     */
    private record Slot(Channel channel, GrpcClient grpcClient) {}

    private static void closeSlot(Slot s) {
        try {
            if (s.channel() instanceof StorkGrpcChannel sgc) {
                sgc.close();
            }
        } catch (Exception ignore) { /* best-effort cleanup */ }
        try {
            s.grpcClient().close();
        } catch (Exception ignore) { /* best-effort cleanup */ }
    }

    private Channel createChannelSync(String serviceName) {
        // Per-service override resolution order:
        //  1. @ConfigMapping Map<String,Integer> perService() — typed and
        //     structured. Works for Stork keys without dashes (e.g. "embedder",
        //     "engine", "repository").
        //  2. Direct MP-Config property lookup at the same prefix — covers
        //     service names containing dashes (e.g. "opensearch-sink",
        //     "semantic-manager") that Quarkus 3.34's @ConfigMapping Map keys
        //     mangle. Same property as #1, so the two cannot disagree.
        //
        // Both paths share the canonical prefix "quarkus.dynamic-grpc" — the
        // direct lookup must mirror DynamicGrpcConfig's @ConfigMapping prefix
        // (locked by PerServiceOverridePrefixTest).
        int defaultSize = config.channel().channelsPerService();
        Integer mapOverride = config.channel().perService().get(serviceName);
        int poolSize = Math.max(1,
                mapOverride != null
                        ? mapOverride
                        : ConfigProvider.getConfig()
                                .getOptionalValue(PER_SERVICE_OVERRIDE_PREFIX + serviceName, Integer.class)
                                .orElse(defaultSize));

        // Pool-build cleanup contract:
        //  - poolFailed flag is set BEFORE the cleanup loop runs, so any
        //    slot that completes asynchronously after we've given up still
        //    hits the whenComplete handler and gets closed.
        //  - The inner try/catch around getChannel() closes the GrpcClient
        //    if the Channel build throws — otherwise that orphan client
        //    would never reach the Slot envelope and never get closed.
        AtomicBoolean poolFailed = new AtomicBoolean(false);
        List<CompletableFuture<Slot>> slotFutures = new ArrayList<>(poolSize);
        for (int i = 0; i < poolSize; i++) {
            CompletableFuture<Slot> f = CompletableFuture.supplyAsync(() -> {
                GrpcClient gc = buildGrpcClient(serviceName);
                try {
                    Channel ch = getChannel(serviceName, gc);
                    return new Slot(ch, gc);
                } catch (Throwable t) {
                    try { gc.close(); } catch (Exception ignore) { /* shutting down */ }
                    throw t;
                }
            }, executor).whenComplete((slot, err) -> {
                if (slot != null && poolFailed.get()) {
                    closeSlot(slot);
                }
            });
            slotFutures.add(f);
        }

        Channel[] delegates = new Channel[poolSize];
        GrpcClient[] grpcClients = new GrpcClient[poolSize];
        try {
            // Single 30s budget across the whole pool — the parallel point of
            // CompletableFuture.supplyAsync is wasted if we then serialise
            // .get(30s, ...) per slot (10s + 30s + ... == late failure).
            CompletableFuture.allOf(slotFutures.toArray(new CompletableFuture[0]))
                    .get(30, TimeUnit.SECONDS);
            for (int i = 0; i < poolSize; i++) {
                Slot s = slotFutures.get(i).getNow(null);
                delegates[i] = s.channel();
                grpcClients[i] = s.grpcClient();
            }
        } catch (Exception e) {
            poolFailed.set(true);
            for (CompletableFuture<Slot> f : slotFutures) {
                f.cancel(true);
                Slot done = f.getNow(null);
                if (done != null) {
                    closeSlot(done);
                }
            }
            throw new RuntimeException(
                    "Failed to build channel pool for service '" + serviceName
                            + "' (poolSize=" + poolSize + ")", e);
        }
        Channel pool = new RoundRobinChannel(delegates, grpcClients);

        // Wrap channel with auth interceptor if auth is enabled — wrap the pool,
        // not the delegates, so the interceptor runs once per call and then the
        // round-robin fans the intercepted call across a delegate.
        Channel created = config.auth().enabled()
                ? ClientInterceptors.intercept(pool, authInterceptor)
                : pool;

        LOG.infof("Created RoundRobinChannel for %s (pool=%d, http2MaxPoolSize=%d, multiplexingLimit=%d)",
                serviceName, poolSize,
                config.channel().http2MaxPoolSize(),
                config.channel().http2MultiplexingLimit());
        metrics.recordChannelCreated(serviceName);

        return created;
    }

    /** Builds a single {@link GrpcClient} for one slot of the round-robin pool. */
    private GrpcClient buildGrpcClient(String serviceName) {
        HttpClientOptions httpOptions = new HttpClientOptions();
        httpOptions.setHttp2ClearTextUpgrade(false); // Recommended by Quarkus

        // Vert.x HTTP/2 defaults to a single connection per host; every stream
        // multiplexes on it and the server side ends up with one event loop
        // doing all the inbound framing. http2MaxPoolSize raises the ceiling
        // on how many TCP connections the pool will open, and
        // http2MultiplexingLimit forces Vert.x to actually open a new one
        // once the current connection has that many concurrent streams.
        // Without the multiplexing limit Vert.x keeps multiplexing onto
        // connection #1 up to the server's max-concurrent-streams (2000) and
        // the pool sits unused. Together with the outer N-channel round-robin
        // this fans concurrent gRPC calls out across N event loops on the
        // receiving service.
        httpOptions.setHttp2MaxPoolSize(config.channel().http2MaxPoolSize());
        httpOptions.setHttp2MultiplexingLimit(config.channel().http2MultiplexingLimit());

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

    private Channel getChannel(String serviceName, GrpcClient grpcClient) {
        GrpcClientConfiguration.StorkConfig storkConfig = new GrpcClientConfiguration.StorkConfig() {
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
                return 3;
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

        Channel created = new StorkGrpcChannel(grpcClient, serviceName, storkConfig, executor);
        return created;
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

        // Update metrics with current cache stats
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

    /**
     * Shuts down all channels during application shutdown.
     * Invoked automatically by CDI before the bean is destroyed.
     */
    @PreDestroy
    void cleanup() {
        shuttingDown.set(true);

        if (channelCache == null) {
            LOG.debug("No channel cache to clean up");
            return;
        }

        LOG.infof("Shutting down %d cached gRPC channels on application exit...", channelCache.estimatedSize());

        var channels = new java.util.ArrayList<>(channelCache.asMap().values());

        channelCache.invalidateAll();
        channelCache.cleanUp();

        ExecutorService shutdownExecutor = Executors.newSingleThreadExecutor();
        try {
            shutdownExecutor.submit(() -> {
                for (Channel channel : channels) {
                    try {
                        if (channel instanceof ManagedChannel mc) {
                            if (!mc.isShutdown()) {
                                mc.shutdown();
                                if (!mc.awaitTermination(100, TimeUnit.MILLISECONDS)) {
                                    mc.shutdownNow();
                                }
                            }
                        } else if (channel instanceof StorkGrpcChannel) {
                            ((StorkGrpcChannel) channel).close();
                        }
                    } catch (Exception e) {
                        LOG.tracef(e, "Error during channel shutdown, forcing immediate termination");
                        try {
                            if (channel instanceof ManagedChannel) {
                                ((ManagedChannel) channel).shutdownNow();
                            }
                        } catch (Exception ex) {
                            LOG.tracef(ex, "Error forcing shutdown during cleanup - ignoring");
                        }
                    }
                }
            }).get(config.channel().shutdownTimeoutSeconds(), TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            LOG.warn("Channel shutdown timed out, forcing immediate termination");
            channels.forEach(ch -> {
                try {
                    if (ch instanceof ManagedChannel mc) {
                        if (!mc.isShutdown()) mc.shutdownNow();
                    } else if (ch instanceof StorkGrpcChannel) {
                        ((StorkGrpcChannel) ch).close();
                    }
                } catch (Exception ex) {
                    LOG.tracef(ex, "Error during forced channel shutdown on timeout - ignoring");
                }
            });
        } catch (Exception e) {
            LOG.error("Error during channel cleanup", e);
        } finally {
            shutdownExecutor.shutdownNow();
        }

        LOG.info("ChannelManager cleanup complete.");
    }
}
