package ai.pipestream.quarkus.dynamicgrpc.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Centralized metrics recording for the Dynamic gRPC extension.
 * <p>
 * Tracks client creation, channel lifecycle, service discovery, cache performance,
 * and exception patterns for monitoring and tracing.
 * </p>
 * <p>
 * Metrics are optional - if Micrometer is not available, all operations become no-ops.
 * </p>
 */
@ApplicationScoped
public class DynamicGrpcMetrics {

    private static final Logger LOG = Logger.getLogger(DynamicGrpcMetrics.class);

    private static final String METRIC_PREFIX = "dynamic.grpc";

    @Inject
    Instance<MeterRegistry> registryInstance;

    private final AtomicInteger activeChannels = new AtomicInteger(0);

    /**
     * Returns the MeterRegistry if available, or null if metrics are disabled.
     */
    private MeterRegistry getRegistry() {
        return registryInstance.isResolvable() ? registryInstance.get() : null;
    }

    /**
     * Records a successful client creation.
     *
     * @param serviceName the service name
     */
    public void recordClientCreationSuccess(String serviceName) {
        MeterRegistry registry = getRegistry();
        if (registry == null) return;

        Counter.builder(METRIC_PREFIX + ".client.created")
                .tag("service", serviceName)
                .tag("result", "success")
                .description("Number of gRPC clients successfully created")
                .register(registry)
                .increment();
    }

    /**
     * Records a failed client creation with exception context.
     *
     * @param serviceName the service name
     * @param exceptionType the exception class name
     */
    public void recordClientCreationFailure(String serviceName, String exceptionType) {
        MeterRegistry registry = getRegistry();
        if (registry == null) return;

        Counter.builder(METRIC_PREFIX + ".client.created")
                .tag("service", serviceName)
                .tag("result", "failure")
                .tag("exception", exceptionType)
                .description("Number of gRPC client creation failures")
                .register(registry)
                .increment();
    }

    /**
     * Records a successful channel creation.
     *
     * @param serviceName the service name
     */
    public void recordChannelCreated(String serviceName) {
        activeChannels.incrementAndGet();

        MeterRegistry registry = getRegistry();
        if (registry == null) return;

        Counter.builder(METRIC_PREFIX + ".channel.created")
                .tag("service", serviceName)
                .description("Number of gRPC channels created")
                .register(registry)
                .increment();
    }

    /**
     * Records a channel eviction/removal.
     *
     * @param serviceName the service name
     * @param reason the eviction reason (e.g., "ttl_expired", "manual", "size_limit")
     */
    public void recordChannelEvicted(String serviceName, String reason) {
        activeChannels.decrementAndGet();

        MeterRegistry registry = getRegistry();
        if (registry == null) return;

        Counter.builder(METRIC_PREFIX + ".channel.evicted")
                .tag("service", serviceName)
                .tag("reason", reason)
                .description("Number of gRPC channels evicted")
                .register(registry)
                .increment();
    }

    /**
     * Records a cache hit.
     *
     * @param serviceName the service name
     */
    public void recordCacheHit(String serviceName) {
        MeterRegistry registry = getRegistry();
        if (registry == null) return;

        Counter.builder(METRIC_PREFIX + ".cache.hit")
                .tag("service", serviceName)
                .description("Number of channel cache hits")
                .register(registry)
                .increment();
    }

    /**
     * Records a cache miss.
     *
     * @param serviceName the service name
     */
    public void recordCacheMiss(String serviceName) {
        MeterRegistry registry = getRegistry();
        if (registry == null) return;

        Counter.builder(METRIC_PREFIX + ".cache.miss")
                .tag("service", serviceName)
                .description("Number of channel cache misses")
                .register(registry)
                .increment();
    }

    /**
     * Records a service discovery attempt.
     *
     * @param serviceName the service name
     * @param success whether discovery succeeded
     * @param instanceCount number of instances found (0 if failed)
     */
    public void recordServiceDiscovery(String serviceName, boolean success, int instanceCount) {
        MeterRegistry registry = getRegistry();
        if (registry == null) return;

        Counter.builder(METRIC_PREFIX + ".discovery.attempts")
                .tag("service", serviceName)
                .tag("result", success ? "success" : "failure")
                .description("Number of service discovery attempts")
                .register(registry)
                .increment();

        if (success) {
            io.micrometer.core.instrument.Gauge.builder(METRIC_PREFIX + ".discovery.instances", () -> instanceCount)
                    .tags("service", serviceName)
                    .description("Number of discovered service instances")
                    .register(registry);
        }
    }

    /**
     * Records an exception occurrence with full context for tracing.
     *
     * @param exceptionType the exception class name
     * @param serviceName the service name (may be null)
     * @param operation the operation that failed (e.g., "client_creation", "channel_creation", "discovery")
     */
    public void recordException(String exceptionType, String serviceName, String operation) {
        MeterRegistry registry = getRegistry();
        if (registry == null) return;

        Counter.builder(METRIC_PREFIX + ".exceptions")
                .tag("exception", exceptionType)
                .tag("service", serviceName != null ? serviceName : "unknown")
                .tag("operation", operation)
                .description("Number of exceptions by type and operation")
                .register(registry)
                .increment();
    }

    /**
     * Times an operation and records the duration.
     *
     * @param <T> the return type
     * @param serviceName the service name
     * @param operation the operation name (e.g., "channel_creation", "discovery")
     * @param callable the operation to time
     * @return the result of the operation
     * @throws Exception if the operation fails
     */
    public <T> T timeOperation(String serviceName, String operation, Callable<T> callable) throws Exception {
        MeterRegistry registry = getRegistry();
        if (registry == null) {
            // If metrics not available, just execute the callable
            return callable.call();
        }

        Timer timer = Timer.builder(METRIC_PREFIX + ".operation.duration")
                .tag("service", serviceName)
                .tag("operation", operation)
                .description("Duration of operations")
                .register(registry);

        return timer.recordCallable(callable);
    }

    /**
     * Returns the current number of active channels.
     *
     * @return active channel count
     */
    public int getActiveChannelCount() {
        return activeChannels.get();
    }

    /**
     * Registers a gauge for active channels that updates dynamically.
     *
     * @param countSupplier supplier that provides the current channel count
     */
    public void registerActiveChannelGauge(Supplier<Integer> countSupplier) {
        MeterRegistry registry = getRegistry();
        if (registry == null) return;

        io.micrometer.core.instrument.Gauge.builder(METRIC_PREFIX + ".channels.active", countSupplier, s -> s.get().doubleValue())
                .description("Number of active gRPC channels")
                .register(registry);
    }

    /**
     * Records cache statistics.
     *
     * @param hitCount total cache hits
     * @param missCount total cache misses
     * @param evictionCount total evictions
     * @param size current cache size
     */
    public void updateCacheStats(long hitCount, long missCount, long evictionCount, long size) {
        MeterRegistry registry = getRegistry();
        if (registry == null) return;

        io.micrometer.core.instrument.Gauge.builder(METRIC_PREFIX + ".cache.size", () -> size)
                .description("Current cache size")
                .register(registry);

        io.micrometer.core.instrument.Gauge.builder(METRIC_PREFIX + ".cache.hit.total", () -> hitCount)
                .description("Total cache hits")
                .register(registry);

        io.micrometer.core.instrument.Gauge.builder(METRIC_PREFIX + ".cache.miss.total", () -> missCount)
                .description("Total cache misses")
                .register(registry);

        io.micrometer.core.instrument.Gauge.builder(METRIC_PREFIX + ".cache.evictions.total", () -> evictionCount)
                .description("Total cache evictions")
                .register(registry);

        // Calculate hit rate
        long total = hitCount + missCount;
        if (total > 0) {
            double hitRate = (double) hitCount / total;
            io.micrometer.core.instrument.Gauge.builder(METRIC_PREFIX + ".cache.hit.rate", () -> hitRate)
                    .description("Cache hit rate")
                    .register(registry);
        }
    }
}
