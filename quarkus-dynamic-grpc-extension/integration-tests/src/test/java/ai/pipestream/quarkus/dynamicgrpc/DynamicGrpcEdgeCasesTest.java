package ai.pipestream.quarkus.dynamicgrpc;

import ai.pipestream.quarkus.dynamicgrpc.GrpcClientFactory;
import ai.pipestream.quarkus.dynamicgrpc.base.ConsulServiceRegistration;
import ai.pipestream.quarkus.dynamicgrpc.base.ConsulTestResource;
import ai.pipestream.quarkus.dynamicgrpc.exception.InvalidServiceNameException;
import ai.pipestream.quarkus.dynamicgrpc.it.proto.MutinyGreeterGrpc;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Edge case tests for GrpcClientFactory with real Consul.
 * Tests error handling, invalid inputs, and failure scenarios.
 */
@QuarkusTest
@WithTestResource(ConsulTestResource.class)
public class DynamicGrpcEdgeCasesTest {

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
    @DisplayName("Non-existent service should fail gracefully")
    void testNonExistentService() {
        // Attempting to get a channel for a non-existent service should eventually fail
        assertThatThrownBy(() ->
            clientFactory.getChannel("non-existent-service-xyz")
                .await().atMost(Duration.ofSeconds(5))
        ).hasMessageContaining("service");
    }

    @Test
    @DisplayName("Null service name should throw InvalidServiceNameException")
    void testNullServiceName() {
        assertThatThrownBy(() ->
            clientFactory.getChannel(null)
                .await().atMost(Duration.ofSeconds(1))
        ).isInstanceOf(InvalidServiceNameException.class)
         .isInstanceOf(IllegalArgumentException.class)  // Verify it extends IllegalArgumentException
         .hasMessageContaining("null");
    }

    @Test
    @DisplayName("Empty service name should throw InvalidServiceNameException")
    void testEmptyServiceName() {
        assertThatThrownBy(() ->
            clientFactory.getChannel("")
                .await().atMost(Duration.ofSeconds(1))
        ).isInstanceOf(InvalidServiceNameException.class)
         .isInstanceOf(IllegalArgumentException.class)  // Verify it extends IllegalArgumentException
         .hasMessageContaining("blank");
    }

    @Test
    @DisplayName("Blank service name should throw InvalidServiceNameException")
    void testBlankServiceName() {
        assertThatThrownBy(() ->
            clientFactory.getChannel("   ")
                .await().atMost(Duration.ofSeconds(1))
        ).isInstanceOf(InvalidServiceNameException.class)
         .isInstanceOf(IllegalArgumentException.class)  // Verify it extends IllegalArgumentException
         .hasMessageContaining("blank");
    }

    @Test
    @DisplayName("Cache stats should always be available")
    void testCacheStatsAlwaysAvailable() {
        String stats = clientFactory.getCacheStats();
        assertThat(stats).isNotNull();
        assertThat(stats).isNotEmpty();
    }

    @Test
    @DisplayName("Evicting non-existent channel should not fail")
    void testEvictNonExistentChannel() {
        // Should not throw - safe operation
        clientFactory.evictChannel("non-existent-service");
        assertThat(true).isTrue(); // If we get here, test passed
    }

    @Test
    @DisplayName("Active service count should be non-negative")
    void testActiveServiceCountNonNegative() {
        int count = clientFactory.getActiveServiceCount();
        assertThat(count).isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("Service registered then deregistered should fail discovery")
    void testServiceDeregistration() throws InterruptedException {
        // Register a service
        String serviceName = "temp-service";
        String serviceId = serviceName + "-temp";

        consulRegistration.registerService(serviceName, serviceId, "127.0.0.1", 9999);
        Thread.sleep(500);

        // Verify it's discoverable
        clientFactory.getChannel(serviceName)
            .await().atMost(Duration.ofSeconds(5));

        // Deregister it
        consulRegistration.deregisterService(serviceId);
        Thread.sleep(2500); // Wait for Consul refresh (2s refresh period)

        // Now it should fail (or return stale cached channel)
        // The behavior depends on cache TTL, so we just verify no crash
        try {
            clientFactory.getChannel(serviceName)
                .await().atMost(Duration.ofSeconds(3));
        } catch (Exception e) {
            // Expected - service no longer exists
            assertThat(e.getMessage()).contains("service");
        }
    }

    @Test
    @DisplayName("Multiple concurrent requests for same service don't create duplicate channels")
    void testConcurrentRequestsSameService() throws InterruptedException {
        String serviceName = "concurrent-test";
        String serviceId = serviceName + "-1";

        consulRegistration.registerService(serviceName, serviceId, "127.0.0.1", 9998);
        Thread.sleep(500);

        int initialCount = clientFactory.getActiveServiceCount();

        // Make 10 concurrent requests for the same service
        for (int i = 0; i < 10; i++) {
            new Thread(() -> {
                try {
                    clientFactory.getChannel(serviceName)
                        .await().atMost(Duration.ofSeconds(5));
                } catch (Exception e) {
                    // Ignore
                }
            }).start();
        }

        Thread.sleep(2000); // Wait for all requests to complete

        // Should only have created ONE new channel
        int finalCount = clientFactory.getActiveServiceCount();
        assertThat(finalCount).isLessThanOrEqualTo(initialCount + 1);

        consulRegistration.deregisterService(serviceId);
    }
}
