package ai.pipestream.quarkus.dynamicgrpc;

import ai.pipestream.quarkus.dynamicgrpc.base.ConsulServiceRegistration;
import ai.pipestream.test.support.ConsulTestResource;
import ai.pipestream.quarkus.dynamicgrpc.it.proto.HelloReply;
import ai.pipestream.quarkus.dynamicgrpc.it.proto.HelloRequest;
import ai.pipestream.quarkus.dynamicgrpc.it.proto.MutinyGreeterGrpc;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.*;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Tests for channel lifecycle management, caching, and cleanup.
 * Verifies channels are properly created, cached, evicted, and cleaned up.
 */
@QuarkusTest
@WithTestResource(ConsulTestResource.class)
public class ChannelLifecycleTest {

    private static final Logger LOG = Logger.getLogger(ChannelLifecycleTest.class);

    @Inject
    GrpcClientFactory clientFactory;

    @ConfigProperty(name = "quarkus.dynamic-grpc.consul.host")
    String consulHost;

    @ConfigProperty(name = "quarkus.dynamic-grpc.consul.port")
    int consulPort;

    private static Server lifecycleServer;
    private static int lifecyclePort;
    private ConsulServiceRegistration consulRegistration;
    private String serviceName;

    @BeforeAll
    static void startLifecycleServer() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            lifecyclePort = socket.getLocalPort();
        }

        lifecycleServer = ServerBuilder.forPort(lifecyclePort)
            .addService(new LifecycleGreeterService())
            .build()
            .start();

        LOG.infof("Started lifecycle test server on port: %d", lifecyclePort);
    }

    @AfterAll
    static void stopLifecycleServer() throws InterruptedException {
        if (lifecycleServer != null) {
            lifecycleServer.shutdown();
            lifecycleServer.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @BeforeEach
    void setup() {
        consulRegistration = new ConsulServiceRegistration(consulHost, consulPort);
        serviceName = "lifecycle-test-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @AfterEach
    void cleanup() {
        if (consulRegistration != null && serviceName != null) {
            try {
                consulRegistration.deregisterService(serviceName + "-1");
            } catch (Exception ignore) {}
        }
    }

    @Test
    @DisplayName("Repeated calls for the same service reuse the same channel")
    void testChannelReusedAcrossCalls() {
        consulRegistration.registerService(serviceName, serviceName + "-1", "127.0.0.1", lifecyclePort);

        // First call: miss (creates channel). Generous budget covers warmup.
        clientFactory.getChannel(serviceName).await().atMost(Duration.ofSeconds(10));
        int afterFirst = clientFactory.getActiveServiceCount();

        // 10 more calls — each should reuse the same channel.
        for (int i = 0; i < 10; i++) {
            clientFactory.getChannel(serviceName).await().atMost(Duration.ofSeconds(2));
        }
        int afterEleven = clientFactory.getActiveServiceCount();

        assertThat(afterEleven)
            .as("getActiveServiceCount must not grow when calls all target the same service")
            .isEqualTo(afterFirst);
    }

    @Test
    @DisplayName("Manual eviction should remove channel from cache")
    void testManualEviction() {
        consulRegistration.registerService(serviceName, serviceName + "-1", "127.0.0.1", lifecyclePort);

        // Capture count BEFORE the channel is created (service name is randomized, not in cache yet)
        int initialCount = clientFactory.getActiveServiceCount();

        // Create a channel (wait for Consul discovery + cache population)
        clientFactory.getChannel(serviceName).await().atMost(Duration.ofSeconds(10));

        int afterCreation = clientFactory.getActiveServiceCount();
        assertThat(afterCreation)
            .as("Active service count should increase after channel creation")
            .isGreaterThan(initialCount);

        // Evict it
        clientFactory.evictChannel(serviceName);

        // Eviction can be async, wait for it
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(clientFactory.getActiveServiceCount())
                .as("Active service count should decrease after eviction")
                .isLessThan(afterCreation);
        });
    }

    @Test
    @DisplayName("Multiple evictions of same service should be safe")
    void testMultipleEvictions() {
        consulRegistration.registerService(serviceName, serviceName + "-1", "127.0.0.1", lifecyclePort);

        // Create channel
        clientFactory.getChannel(serviceName).await().atMost(Duration.ofSeconds(10));

        // Evict multiple times - should not crash
        clientFactory.evictChannel(serviceName);
        clientFactory.evictChannel(serviceName);
        clientFactory.evictChannel(serviceName);

        // Should still be able to create new channel
        assertThat(clientFactory.getChannel(serviceName).await().atMost(Duration.ofSeconds(5)))
            .as("Should be able to re-create channel after multiple evictions")
            .isNotNull();
    }

    @Test
    @DisplayName("Cached channel should be reused across different stub types")
    void testChannelReuseAcrossStubTypes() {
        consulRegistration.registerService(serviceName, serviceName + "-1", "127.0.0.1", lifecyclePort);

        // Capture count BEFORE channel creation (randomized service not in cache yet)
        int initialCount = clientFactory.getActiveServiceCount();

        // Get Mutiny stub (populates cache; generous budget covers discovery warmup)
        var mutinyStub = clientFactory.getClient(serviceName, MutinyGreeterGrpc::newMutinyStub)
            .await().atMost(Duration.ofSeconds(10));

        // Get raw channel again (should reuse cached channel)
        var channel = clientFactory.getChannel(serviceName)
            .await().atMost(Duration.ofSeconds(5));

        // Should still only have ONE cached channel for this service
        assertThat(clientFactory.getActiveServiceCount())
            .as("Should reuse the same channel for different stub types — only 1 new channel")
            .isEqualTo(initialCount + 1);

        // Both should work
        HelloReply reply = mutinyStub.sayHello(
            HelloRequest.newBuilder().setName("Test").build()
        ).await().atMost(Duration.ofSeconds(3));

        assertThat(reply.getMessage()).as("Stub should work").contains("Hello");
        assertThat(channel).as("Raw channel should not be null").isNotNull();
    }

    @Test
    @DisplayName("Active service count should accurately reflect cached channels")
    void testActiveServiceCountAccuracy() {
        int initialCount = clientFactory.getActiveServiceCount();

        // Create 5 different service channels
        for (int i = 0; i < 5; i++) {
            String name = serviceName + "-" + i;
            consulRegistration.registerService(name, name + "-1", "127.0.0.1", lifecyclePort);
        }

        // Populate cache (generous budget per service covers discovery warmup)
        for (int i = 0; i < 5; i++) {
            String name = serviceName + "-" + i;
            clientFactory.getChannel(name).await().atMost(Duration.ofSeconds(10));
        }

        assertThat(clientFactory.getActiveServiceCount())
            .as("Active service count should match expected number of services")
            .isEqualTo(initialCount + 5);

        // Evict 2 services
        clientFactory.evictChannel(serviceName + "-0");
        clientFactory.evictChannel(serviceName + "-1");
        
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(clientFactory.getActiveServiceCount())
                .as("Active service count should decrease after multiple evictions")
                .isLessThanOrEqualTo(initialCount + 3);
        });

        // Cleanup
        for (int i = 0; i < 5; i++) {
            try {
                consulRegistration.deregisterService(serviceName + "-" + i + "-1");
            } catch (Exception ignore) {}
        }
    }

    @Test
    @DisplayName("Cache stats should provide useful debugging information")
    void testCacheStatsContent() {
        consulRegistration.registerService(serviceName, serviceName + "-1", "127.0.0.1", lifecyclePort);

        // First call takes the discovery hit; subsequent calls hit the cache.
        clientFactory.getChannel(serviceName).await().atMost(Duration.ofSeconds(10));
        for (int i = 0; i < 4; i++) {
            clientFactory.getChannel(serviceName).await().atMost(Duration.ofSeconds(3));
        }

        String stats = clientFactory.getCacheStats();
        assertThat(stats).as("Stats should not be null").isNotNull();
        assertThat(stats).as("Stats should not be empty").isNotEmpty();
        assertThat(stats.toLowerCase())
            .as("Stats should report active channel count")
            .contains("channel");
    }

    @Test
    @DisplayName("Concurrent access to same service should create only one channel")
    void testConcurrentChannelCreation() throws InterruptedException {
        consulRegistration.registerService(serviceName, serviceName + "-1", "127.0.0.1", lifecyclePort);

        // Capture count BEFORE any channel creation (randomized service not in cache yet)
        int initialCount = clientFactory.getActiveServiceCount();

        // Wait for Consul discovery to be ready (but don't cache the channel yet —
        // we want the concurrent threads to be the first to create it)
        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(200)).until(() -> {
            try {
                // Just verify discovery works, then evict so the concurrent test starts clean
                clientFactory.getChannel(serviceName).await().atMost(Duration.ofSeconds(1));
                clientFactory.evictChannel(serviceName);
                return true;
            } catch (Exception e) {
                return false;
            }
        });

        // Re-capture after eviction to get a clean baseline
        int baselineCount = clientFactory.getActiveServiceCount();

        // Create 10 concurrent channel requests — all for the same service
        Thread[] threads = new Thread[10];
        java.util.concurrent.atomic.AtomicInteger successCount = new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.concurrent.atomic.AtomicInteger failureCount = new java.util.concurrent.atomic.AtomicInteger(0);

        for (int i = 0; i < 10; i++) {
            threads[i] = new Thread(() -> {
                try {
                    clientFactory.getChannel(serviceName).await().atMost(Duration.ofSeconds(5));
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failureCount.incrementAndGet();
                    LOG.error("Concurrent channel creation failed", e);
                }
            });
            threads[i].start();
        }

        for (Thread thread : threads) {
            thread.join(10000);
        }

        assertThat(successCount.get())
            .as("all 10 concurrent requests should succeed")
            .isEqualTo(10);
        assertThat(failureCount.get())
            .as("no concurrent requests should fail")
            .isZero();

        // Should only have created ONE channel despite 10 concurrent requests
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(clientFactory.getActiveServiceCount())
                .as("10 concurrent requests for same service should result in exactly one new cached channel")
                .isEqualTo(baselineCount + 1);
        });
    }

    /**
     * Simple greeter service for lifecycle testing.
     */
    static class LifecycleGreeterService extends MutinyGreeterGrpc.GreeterImplBase {
        @Override
        public Uni<HelloReply> sayHello(HelloRequest request) {
            HelloReply response = HelloReply.newBuilder()
                .setMessage("Hello " + request.getName())
                .build();
            return Uni.createFrom().item(response);
        }
    }
}
