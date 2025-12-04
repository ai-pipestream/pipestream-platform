package ai.pipestream.quarkus.dynamicgrpc;

import ai.pipestream.quarkus.dynamicgrpc.base.ConsulServiceRegistration;
import ai.pipestream.quarkus.dynamicgrpc.base.ConsulTestResource;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Performance tests for GrpcClientFactory with real Consul.
 * Validates channel caching improves performance and handles load.
 */
@QuarkusTest
@WithTestResource(ConsulTestResource.class)
public class DynamicGrpcPerformanceTest {

    private static final Logger LOG = Logger.getLogger(DynamicGrpcPerformanceTest.class);

    @Inject
    GrpcClientFactory clientFactory;

    @ConfigProperty(name = "quarkus.dynamic-grpc.consul.host")
    String consulHost;

    @ConfigProperty(name = "quarkus.dynamic-grpc.consul.port")
    int consulPort;

    private ConsulServiceRegistration consulRegistration;

    @BeforeEach
    void setup() {
        consulRegistration = new ConsulServiceRegistration(consulHost, consulPort);
    }

    @Test
    @DisplayName("Channel caching should improve performance")
    void testChannelCachingPerformance() throws InterruptedException {
        String serviceName = "perf-service";
        String serviceId = serviceName + "-1";

        consulRegistration.registerService(serviceName, serviceId, "127.0.0.1", 9997);
        Thread.sleep(500);

        // First call - may involve service discovery + channel creation
        long start = System.nanoTime();
        clientFactory.getChannel(serviceName)
            .await().atMost(Duration.ofSeconds(5));
        long firstCallTime = System.nanoTime() - start;

        // Subsequent calls should use cached channel
        List<Long> cachedCallTimes = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            start = System.nanoTime();
            clientFactory.getChannel(serviceName)
                .await().atMost(Duration.ofSeconds(1));
            cachedCallTimes.add(System.nanoTime() - start);
        }

        double avgCachedTimeMs = cachedCallTimes.stream()
            .mapToLong(Long::longValue)
            .average()
            .orElse(0) / 1_000_000.0;

        double firstCallTimeMs = firstCallTime / 1_000_000.0;

        LOG.infof("First call: %.2fms, Avg cached: %.2fms", firstCallTimeMs, avgCachedTimeMs);

        // Cached calls should be faster or equal
        assertThat(avgCachedTimeMs).isLessThanOrEqualTo(firstCallTimeMs);

        // Cached calls should be very fast (sub-10ms)
        assertThat(avgCachedTimeMs).isLessThan(10.0);

        consulRegistration.deregisterService(serviceId);
    }

    @Test
    @DisplayName("Multiple concurrent requests should share channel efficiently")
    void testConcurrentRequestsShareChannel() throws InterruptedException {
        String serviceName = "concurrent-perf-service";
        String serviceId = serviceName + "-1";

        consulRegistration.registerService(serviceName, serviceId, "127.0.0.1", 9996);
        Thread.sleep(500);

        int initialCount = clientFactory.getActiveServiceCount();

        // Create 20 clients concurrently
        List<io.grpc.Channel> channels = new ArrayList<>();
        List<Thread> threads = new ArrayList<>();

        for (int i = 0; i < 20; i++) {
            Thread thread = new Thread(() -> {
                try {
                    io.grpc.Channel channel = clientFactory.getChannel(serviceName)
                        .await().atMost(Duration.ofSeconds(5));
                    synchronized (channels) {
                        channels.add(channel);
                    }
                } catch (Exception e) {
                    LOG.error("Failed to obtain channel in concurrent test", e);
                }
            });
            threads.add(thread);
            thread.start();
        }

        // Wait for all threads to complete
        for (Thread thread : threads) {
            thread.join(10000);
        }

        assertThat(channels).hasSizeGreaterThanOrEqualTo(15); // At least most succeeded

        // Should still only have ONE cached channel for this service
        int finalCount = clientFactory.getActiveServiceCount();
        assertThat(finalCount).isLessThanOrEqualTo(initialCount + 1);

        consulRegistration.deregisterService(serviceId);
    }

    @Test
    @DisplayName("Different services should get different channels")
    void testDifferentServicesGetDifferentChannels() throws InterruptedException {
        int initialCount = clientFactory.getActiveServiceCount();

        // Register and access 5 different services
        for (int i = 0; i < 5; i++) {
            String serviceName = "multi-service-" + i;
            String serviceId = serviceName + "-1";
            int port = 9990 + i;

            consulRegistration.registerService(serviceName, serviceId, "127.0.0.1", port);
        }

        Thread.sleep(500);

        // Access each service
        for (int i = 0; i < 5; i++) {
            String serviceName = "multi-service-" + i;
            clientFactory.getChannel(serviceName)
                .await().atMost(Duration.ofSeconds(5));
        }

        // Should have 5 new channels (one per service)
        int finalCount = clientFactory.getActiveServiceCount();
        assertThat(finalCount).isGreaterThanOrEqualTo(initialCount + 5);

        // Cleanup
        for (int i = 0; i < 5; i++) {
            consulRegistration.deregisterService("multi-service-" + i + "-1");
        }
    }

    @Test
    @DisplayName("Sequential access to same service should consistently use cache")
    void testSequentialAccessConsistency() throws InterruptedException {
        String serviceName = "sequential-test";
        String serviceId = serviceName + "-1";

        consulRegistration.registerService(serviceName, serviceId, "127.0.0.1", 9995);
        Thread.sleep(500);

        int initialCount = clientFactory.getActiveServiceCount();

        // Access 100 times sequentially
        for (int i = 0; i < 100; i++) {
            clientFactory.getChannel(serviceName)
                .await().atMost(Duration.ofSeconds(2));
        }

        // Should still only have ONE channel for this service
        int finalCount = clientFactory.getActiveServiceCount();
        assertThat(finalCount).isLessThanOrEqualTo(initialCount + 1);

        consulRegistration.deregisterService(serviceId);
    }
}
