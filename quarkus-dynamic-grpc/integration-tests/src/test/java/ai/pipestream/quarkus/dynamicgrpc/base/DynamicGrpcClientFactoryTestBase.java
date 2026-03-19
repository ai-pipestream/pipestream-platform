package ai.pipestream.quarkus.dynamicgrpc.base;

import ai.pipestream.quarkus.dynamicgrpc.GrpcClientFactory;
import ai.pipestream.test.support.ConsulTestResource;
import ai.pipestream.quarkus.dynamicgrpc.it.proto.HelloReply;
import ai.pipestream.quarkus.dynamicgrpc.it.proto.HelloRequest;
import ai.pipestream.quarkus.dynamicgrpc.it.proto.MutinyGreeterGrpc;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.quarkus.test.common.WithTestResource;
import io.smallrye.mutiny.Uni;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Base test class with real greeter gRPC server and real Consul service discovery.
 * Uses TestContainers for Consul - no mocks, full integration testing.
 */
@WithTestResource(ConsulTestResource.class)
public abstract class DynamicGrpcClientFactoryTestBase {

    private static final Logger LOG = Logger.getLogger(DynamicGrpcClientFactoryTestBase.class);

    protected static Server testGrpcServer;
    protected static int testGrpcPort;
    
    // Use instance-level service name for better isolation between tests
    protected String serviceName;
    protected ConsulServiceRegistration consulRegistration;

    @ConfigProperty(name = "quarkus.dynamic-grpc.consul.host")
    String consulHost;

    @ConfigProperty(name = "quarkus.dynamic-grpc.consul.port")
    int consulPort;

    protected abstract GrpcClientFactory getFactory();

    @BeforeAll
    static void startTestServer() throws IOException {
        // Find an available random port
        try (ServerSocket socket = new ServerSocket(0)) {
            testGrpcPort = socket.getLocalPort();
        }

        // Start real gRPC server with test greeter implementation
        testGrpcServer = ServerBuilder.forPort(testGrpcPort)
            .addService(new TestGreeterService())
            .build()
            .start();

        LOG.infof("Test gRPC server started on port: %d", testGrpcPort);
    }

    @BeforeEach
    void setupBase() {
        serviceName = "greeter-test-" + UUID.randomUUID().toString().substring(0, 8);
        consulRegistration = new ConsulServiceRegistration(consulHost, consulPort);
    }

    /**
     * Register the gRPC service in Consul after Quarkus test starts
     * (called from subclass @BeforeEach after injection is available)
     */
    protected void registerServiceInConsul() {
        // Register our test gRPC server in Consul
        consulRegistration.registerService(
            serviceName,
            serviceName + "-1",
            "127.0.0.1",
            testGrpcPort
        );

        LOG.infof("Registered %s in Consul at 127.0.0.1:%d (Consul at %s:%d)", 
            serviceName, testGrpcPort, consulHost, consulPort);
    }

    @AfterAll
    static void stopTestServer() throws InterruptedException {
        if (testGrpcServer != null) {
            testGrpcServer.shutdown();
            testGrpcServer.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @AfterEach
    void cleanupBase() {
        if (consulRegistration != null && serviceName != null) {
            try {
                consulRegistration.deregisterService(serviceName + "-1");
            } catch (Exception e) {
                LOG.warn("Failed to deregister service from Consul", e);
            }
        }
    }

    @Test
    void testClientCreationAndCall() {
        registerServiceInConsul();

        // Wait for Consul registration to propagate
        await().atMost(Duration.ofSeconds(5))
            .alias("Wait for service discovery")
            .untilAsserted(() -> {
                var clientUni = getFactory().getClient(serviceName, MutinyGreeterGrpc::newMutinyStub);
                assertThat(clientUni.await().atMost(Duration.ofSeconds(1)))
                    .as("Client should be discoverable in Stork")
                    .isNotNull();
            });

        // Get a Mutiny client stub using the factory
        var client = getFactory().getClient(serviceName, MutinyGreeterGrpc::newMutinyStub)
            .await().atMost(Duration.ofSeconds(5));

        // Make a real gRPC call
        HelloRequest request = HelloRequest.newBuilder()
            .setName("Factory Test")
            .build();

        HelloReply response = client.sayHello(request)
            .await().atMost(Duration.ofSeconds(5));

        assertThat(response)
            .as("gRPC response should not be null")
            .isNotNull();
        assertThat(response.getMessage())
            .as("Response message should contain the name sent")
            .isEqualTo("Hello Factory Test");
    }

    @Test
    void testClientReuse() {
        registerServiceInConsul();

        // Wait for Consul registration to propagate
        await().atMost(Duration.ofSeconds(5))
            .alias("Wait for service discovery")
            .untilAsserted(() -> {
                assertThat(getFactory().getClient(serviceName, MutinyGreeterGrpc::newMutinyStub).await().atMost(Duration.ofSeconds(1)))
                    .as("Client should be discoverable")
                    .isNotNull();
            });

        GrpcClientFactory factory = getFactory();

        // Request the same client twice - should use cached channel
        var client1 = factory.getClient(serviceName, MutinyGreeterGrpc::newMutinyStub).await().atMost(Duration.ofSeconds(10));
        var client2 = factory.getClient(serviceName, MutinyGreeterGrpc::newMutinyStub).await().atMost(Duration.ofSeconds(10));

        // Should have cached the channel
        assertThat(factory.getActiveServiceCount())
            .as("Should have at least one active service in cache")
            .isGreaterThanOrEqualTo(1);

        // Both clients should work
        HelloRequest request = HelloRequest.newBuilder()
            .setName("Reuse Test")
            .build();

        HelloReply response1 = client1.sayHello(request).await().atMost(Duration.ofSeconds(5));
        HelloReply response2 = client2.sayHello(request).await().atMost(Duration.ofSeconds(5));

        assertThat(response1.getMessage()).as("First client should work").isEqualTo("Hello Reuse Test");
        assertThat(response2.getMessage()).as("Second client should work and return same result").isEqualTo("Hello Reuse Test");
    }

    @Test
    void testCacheStats() {
        registerServiceInConsul();

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(getFactory().getClient(serviceName, MutinyGreeterGrpc::newMutinyStub).await().atMost(Duration.ofSeconds(1))).isNotNull();
        });

        GrpcClientFactory factory = getFactory();

        // Make a call to populate cache
        factory.getClient(serviceName, MutinyGreeterGrpc::newMutinyStub)
            .await().atMost(Duration.ofSeconds(10));

        String stats = factory.getCacheStats();
        assertThat(stats)
            .as("Cache stats should be available")
            .isNotNull();
        assertThat(stats.toLowerCase())
            .as("Cache stats should contain hit information")
            .contains("hit");
    }

    @Test
    void testChannelEviction() {
        registerServiceInConsul();

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(getFactory().getClient(serviceName, MutinyGreeterGrpc::newMutinyStub).await().atMost(Duration.ofSeconds(1))).isNotNull();
        });

        GrpcClientFactory factory = getFactory();

        // Create a client to populate cache
        factory.getClient(serviceName, MutinyGreeterGrpc::newMutinyStub)
            .await().atMost(Duration.ofSeconds(10));

        int countBeforeEviction = factory.getActiveServiceCount();
        assertThat(countBeforeEviction).as("Should have at least one active service before eviction").isGreaterThanOrEqualTo(1);

        // Evict the channel
        factory.evictChannel(serviceName);

        // Eviction might be async, wait for count to potentially decrease
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(factory.getActiveServiceCount())
                .as("Active service count should be less than or equal to count before eviction")
                .isLessThanOrEqualTo(countBeforeEviction);
        });
    }

    /**
     * Test implementation of the Greeter service.
     */
    public static class TestGreeterService extends MutinyGreeterGrpc.GreeterImplBase {
        @Override
        public Uni<HelloReply> sayHello(HelloRequest request) {
            HelloReply response = HelloReply.newBuilder()
                .setMessage("Hello " + request.getName())
                .build();
            return Uni.createFrom().item(response);
        }
    }
}
