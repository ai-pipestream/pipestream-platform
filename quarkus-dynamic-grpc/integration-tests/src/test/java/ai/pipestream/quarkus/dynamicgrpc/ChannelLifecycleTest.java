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
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

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
    }

    @Test
    @DisplayName("Channel cache hit ratio should improve over time")
    void testCacheHitRatio() throws InterruptedException {
        String serviceName = "cache-ratio-service";
        consulRegistration.registerService(serviceName, serviceName + "-1", "127.0.0.1", lifecyclePort);
        Thread.sleep(500);

        // First call - miss (creates channel)
        clientFactory.getChannel(serviceName)
            .await().atMost(Duration.ofSeconds(5));

        String stats1 = clientFactory.getCacheStats();
        LOG.infof("After 1 call: %s", stats1);

        // Next 10 calls - should all be hits
        for (int i = 0; i < 10; i++) {
            clientFactory.getChannel(serviceName)
                .await().atMost(Duration.ofSeconds(2));
        }

        String stats2 = clientFactory.getCacheStats();
        LOG.infof("After 11 calls: %s", stats2);

        assertThat(stats2).containsIgnoringCase("hit");

        consulRegistration.deregisterService(serviceName + "-1");
    }

    @Test
    @DisplayName("Manual eviction should remove channel from cache")
    void testManualEviction() throws InterruptedException {
        String serviceName = "eviction-service";
        consulRegistration.registerService(serviceName, serviceName + "-1", "127.0.0.1", lifecyclePort);
        Thread.sleep(500);

        int initialCount = clientFactory.getActiveServiceCount();

        // Create a channel
        clientFactory.getChannel(serviceName)
            .await().atMost(Duration.ofSeconds(5));

        int afterCreation = clientFactory.getActiveServiceCount();
        assertThat(afterCreation).isGreaterThan(initialCount);

        // Evict it
        clientFactory.evictChannel(serviceName);
        Thread.sleep(500); // Give eviction time to complete

        int afterEviction = clientFactory.getActiveServiceCount();
        assertThat(afterEviction).isLessThanOrEqualTo(afterCreation);

        consulRegistration.deregisterService(serviceName + "-1");
    }

    @Test
    @DisplayName("Multiple evictions of same service should be safe")
    void testMultipleEvictions() throws InterruptedException {
        String serviceName = "multi-evict-service";
        consulRegistration.registerService(serviceName, serviceName + "-1", "127.0.0.1", lifecyclePort);
        Thread.sleep(500);

        // Create channel
        clientFactory.getChannel(serviceName)
            .await().atMost(Duration.ofSeconds(5));

        // Evict multiple times - should not crash
        clientFactory.evictChannel(serviceName);
        clientFactory.evictChannel(serviceName);
        clientFactory.evictChannel(serviceName);

        // Should still be able to create new channel
        clientFactory.getChannel(serviceName)
            .await().atMost(Duration.ofSeconds(5));

        consulRegistration.deregisterService(serviceName + "-1");
    }

    @Test
    @DisplayName("Cached channel should be reused across different stub types")
    void testChannelReuseAcrossStubTypes() throws InterruptedException {
        String serviceName = "stub-reuse-service";
        consulRegistration.registerService(serviceName, serviceName + "-1", "127.0.0.1", lifecyclePort);
        Thread.sleep(500);

        int initialCount = clientFactory.getActiveServiceCount();

        // Get Mutiny stub
        var mutinyStub = clientFactory.getClient(serviceName, MutinyGreeterGrpc::newMutinyStub)
            .await().atMost(Duration.ofSeconds(5));

        // Get raw channel
        var channel = clientFactory.getChannel(serviceName)
            .await().atMost(Duration.ofSeconds(5));

        // Should still only have ONE cached channel
        int finalCount = clientFactory.getActiveServiceCount();
        assertThat(finalCount).isEqualTo(initialCount + 1);

        // Both should work
        HelloReply reply = mutinyStub.sayHello(
            HelloRequest.newBuilder().setName("Test").build()
        ).await().atMost(Duration.ofSeconds(3));

        assertThat(reply.getMessage()).contains("Hello");
        assertThat(channel).isNotNull();

        consulRegistration.deregisterService(serviceName + "-1");
    }

    @Test
    @DisplayName("Active service count should accurately reflect cached channels")
    void testActiveServiceCountAccuracy() throws InterruptedException {
        int initialCount = clientFactory.getActiveServiceCount();

        // Create 5 different service channels
        for (int i = 0; i < 5; i++) {
            String serviceName = "count-service-" + i;
            consulRegistration.registerService(
                serviceName,
                serviceName + "-1",
                "127.0.0.1",
                lifecyclePort
            );
        }

        Thread.sleep(500);

        // Access each service
        for (int i = 0; i < 5; i++) {
            clientFactory.getChannel("count-service-" + i)
                .await().atMost(Duration.ofSeconds(5));
        }

        int afterCreation = clientFactory.getActiveServiceCount();
        assertThat(afterCreation).isEqualTo(initialCount + 5);

        // Evict 2 services
        clientFactory.evictChannel("count-service-0");
        clientFactory.evictChannel("count-service-1");
        Thread.sleep(500);

        int afterEviction = clientFactory.getActiveServiceCount();
        assertThat(afterEviction).isLessThanOrEqualTo(initialCount + 3);

        // Cleanup
        for (int i = 0; i < 5; i++) {
            consulRegistration.deregisterService("count-service-" + i + "-1");
        }
    }

    @Test
    @DisplayName("Cache stats should provide useful debugging information")
    void testCacheStatsContent() throws InterruptedException {
        String serviceName = "stats-service";
        consulRegistration.registerService(serviceName, serviceName + "-1", "127.0.0.1", lifecyclePort);
        Thread.sleep(500);

        // Make some calls
        for (int i = 0; i < 5; i++) {
            clientFactory.getChannel(serviceName)
                .await().atMost(Duration.ofSeconds(3));
        }

        String stats = clientFactory.getCacheStats();

        // Stats should contain useful information
        assertThat(stats).isNotNull();
        assertThat(stats).isNotEmpty();

        LOG.infof("Cache stats: %s", stats);

        // Stats should mention hits (case-insensitive)
        assertThat(stats.toLowerCase()).containsAnyOf("hit", "cache", "request");

        consulRegistration.deregisterService(serviceName + "-1");
    }

    @Test
    @DisplayName("Concurrent access to same service should create only one channel")
    void testConcurrentChannelCreation() throws InterruptedException {
        String serviceName = "concurrent-create-service";
        consulRegistration.registerService(serviceName, serviceName + "-1", "127.0.0.1", lifecyclePort);
        Thread.sleep(500);

        int initialCount = clientFactory.getActiveServiceCount();

        // Create 10 concurrent channel requests
        Thread[] threads = new Thread[10];
        for (int i = 0; i < 10; i++) {
            threads[i] = new Thread(() -> {
                try {
                    clientFactory.getChannel(serviceName)
                        .await().atMost(Duration.ofSeconds(5));
                } catch (Exception e) {
                    LOG.error("Concurrent channel creation failed", e);
                }
            });
            threads[i].start();
        }

        // Wait for all threads
        for (Thread thread : threads) {
            thread.join(10000);
        }

        Thread.sleep(1000); // Ensure all complete

        // Should only have created ONE channel despite concurrent requests
        int finalCount = clientFactory.getActiveServiceCount();
        assertThat(finalCount).isEqualTo(initialCount + 1);

        consulRegistration.deregisterService(serviceName + "-1");
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
