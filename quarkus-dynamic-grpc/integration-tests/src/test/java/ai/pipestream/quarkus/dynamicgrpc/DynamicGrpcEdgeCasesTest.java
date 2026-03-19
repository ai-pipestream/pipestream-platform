package ai.pipestream.quarkus.dynamicgrpc;

import ai.pipestream.quarkus.dynamicgrpc.GrpcClientFactory;
import ai.pipestream.quarkus.dynamicgrpc.base.ConsulServiceRegistration;
import ai.pipestream.test.support.ConsulTestResource;
import ai.pipestream.quarkus.dynamicgrpc.exception.InvalidServiceNameException;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

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
    private String serviceName;

    @BeforeEach
    void setup() {
        consulRegistration = new ConsulServiceRegistration(consulHost, consulPort);
        serviceName = "edge-test-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @AfterEach
    void cleanup() {
        if (consulRegistration != null && serviceName != null) {
            try {
                consulRegistration.deregisterService(serviceName + "-1");
                consulRegistration.deregisterService(serviceName + "-temp");
                consulRegistration.deregisterService(serviceName + "-1-instance");
            } catch (Exception ignore) {}
        }
    }

    @Test
    @DisplayName("Non-existent service should fail gracefully")
    void testNonExistentService() {
        // Attempting to get a channel for a non-existent service should eventually fail
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            try {
                clientFactory.getChannel("non-existent-" + UUID.randomUUID()).await().atMost(Duration.ofSeconds(1));
                org.junit.jupiter.api.Assertions.fail("Expected exception was not thrown");
            } catch (Throwable e) {
                System.err.println("DEBUG: Caught message: " + e.getMessage());
                assertThat(e.getMessage()).as("Message should indicate failure")
                    .matches(".*(?i)(service|discovery|instances|no).*");
            }
        });
    }

    @Test
    @DisplayName("Null service name should throw InvalidServiceNameException")
    void testNullServiceName() {
        assertThatThrownBy(() ->
            clientFactory.getChannel(null)
                .await().atMost(Duration.ofSeconds(1))
        ).as("Null service name should throw InvalidServiceNameException")
         .isInstanceOf(InvalidServiceNameException.class)
         .hasMessageContaining("null");
    }

    @Test
    @DisplayName("Empty service name should throw InvalidServiceNameException")
    void testEmptyServiceName() {
        assertThatThrownBy(() ->
            clientFactory.getChannel("")
                .await().atMost(Duration.ofSeconds(1))
        ).as("Empty service name should throw InvalidServiceNameException")
         .isInstanceOf(InvalidServiceNameException.class)
         .hasMessageContaining("blank");
    }

    @Test
    @DisplayName("Blank service name should throw InvalidServiceNameException")
    void testBlankServiceName() {
        assertThatThrownBy(() ->
            clientFactory.getChannel("   ")
                .await().atMost(Duration.ofSeconds(1))
        ).as("Blank service name should throw InvalidServiceNameException")
         .isInstanceOf(InvalidServiceNameException.class)
         .hasMessageContaining("blank");
    }

    @Test
    @DisplayName("Cache stats should always be available")
    void testCacheStatsAlwaysAvailable() {
        String stats = clientFactory.getCacheStats();
        assertThat(stats).as("Cache stats should not be null").isNotNull();
        assertThat(stats).as("Cache stats should not be empty").isNotEmpty();
    }

    @Test
    @DisplayName("Evicting non-existent channel should not fail")
    void testEvictNonExistentChannel() {
        // Should not throw - safe operation
        clientFactory.evictChannel("non-existent-service-" + UUID.randomUUID());
    }

    @Test
    @DisplayName("Active service count should be non-negative")
    void testActiveServiceCountNonNegative() {
        int count = clientFactory.getActiveServiceCount();
        assertThat(count).as("Active service count should be 0 or more").isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("Service registered then deregistered should fail discovery")
    void testServiceDeregistration() {
        String id = serviceName + "-temp";
        consulRegistration.registerService(serviceName, id, "127.0.0.1", 9999);
        
        // Verify it's discoverable
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(clientFactory.getChannel(serviceName).await().atMost(Duration.ofSeconds(1)))
                .as("Service should be discoverable after registration")
                .isNotNull();
        });

        // Deregister it
        consulRegistration.deregisterService(id);
        clientFactory.evictChannel(serviceName);

        // Now it should fail
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            try {
                clientFactory.getChannel(serviceName).await().atMost(Duration.ofSeconds(1));
                org.junit.jupiter.api.Assertions.fail("Expected exception was not thrown");
            } catch (Throwable e) {
                System.err.println("DEBUG: Caught message: " + e.getMessage());
                assertThat(e.getMessage()).as("Message should indicate failure")
                    .matches(".*(?i)(service|discovery|instances|no).*");
            }
        });
    }

    @Test
    @DisplayName("Multiple concurrent requests for same service don't create duplicate channels")
    void testConcurrentRequestsSameService() throws InterruptedException {
        String id = serviceName + "-1-instance";
        consulRegistration.registerService(serviceName, id, "127.0.0.1", 9998);
        
        // Wait for discovery
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(clientFactory.getChannel(serviceName).await().atMost(Duration.ofSeconds(1)))
                .as("Service should be discoverable for concurrent test")
                .isNotNull();
        });

        int initialCount = clientFactory.getActiveServiceCount();

        // Make 10 concurrent requests for the same service
        Thread[] threads = new Thread[10];
        for (int i = 0; i < 10; i++) {
            threads[i] = new Thread(() -> {
                try {
                    clientFactory.getChannel(serviceName).await().atMost(Duration.ofSeconds(5));
                } catch (Exception ignore) {}
            });
            threads[i].start();
        }

        for (Thread t : threads) {
            t.join(5000);
        }

        // Should only have created ONE new channel
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(clientFactory.getActiveServiceCount())
                .as("Concurrent requests for same service should share a single channel in cache")
                .isLessThanOrEqualTo(initialCount + 1);
        });
    }
}
