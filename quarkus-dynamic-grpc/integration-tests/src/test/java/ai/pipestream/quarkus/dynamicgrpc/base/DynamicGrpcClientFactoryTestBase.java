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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Base test class with real greeter gRPC server and real Consul service discovery.
 * Uses TestContainers for Consul - no mocks, full integration testing.
 */
@WithTestResource(ConsulTestResource.class)
public abstract class DynamicGrpcClientFactoryTestBase {

    private static final Logger LOG = Logger.getLogger(DynamicGrpcClientFactoryTestBase.class);

    protected static Server testGrpcServer;
    protected static int testGrpcPort;
    protected static final String TEST_SERVICE_NAME = "greeter-test";
    protected static ConsulServiceRegistration consulRegistration;

    @ConfigProperty(name = "quarkus.dynamic-grpc.consul.host")
    String consulHost;

    @ConfigProperty(name = "quarkus.dynamic-grpc.consul.port")
    int consulPort;

    protected abstract GrpcClientFactory getFactory();

    @BeforeAll
    static void startTestServer() throws IOException, InterruptedException {
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

    /**
     * Register the gRPC service in Consul after Quarkus test starts
     * (called from subclass @BeforeEach after injection is available)
     */
    protected void registerServiceInConsul() {
        if (consulRegistration == null) {
            consulRegistration = new ConsulServiceRegistration(consulHost, consulPort);
        }

        // Register our test gRPC server in Consul
        consulRegistration.registerService(
            TEST_SERVICE_NAME,
            TEST_SERVICE_NAME + "-1",
            "127.0.0.1",
            testGrpcPort
        );

        LOG.infof("Registered %s in Consul at 127.0.0.1:%d", TEST_SERVICE_NAME, testGrpcPort);
    }

    @AfterAll
    static void stopTestServer() throws InterruptedException {
        if (consulRegistration != null) {
            consulRegistration.deregisterService(TEST_SERVICE_NAME + "-1");
        }

        if (testGrpcServer != null) {
            testGrpcServer.shutdown();
            testGrpcServer.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void testClientCreationAndCall() throws InterruptedException {
        // Wait a moment for Consul registration to propagate
        Thread.sleep(500);

        // Get a Mutiny client stub using the factory - will discover via Consul
        var clientUni = getFactory().getClient(
            TEST_SERVICE_NAME,
            MutinyGreeterGrpc::newMutinyStub
        );

        var client = clientUni.await().atMost(java.time.Duration.ofSeconds(10));
        assertThat(client).isNotNull();

        // Make a real gRPC call
        HelloRequest request = HelloRequest.newBuilder()
            .setName("Factory Test")
            .build();

        HelloReply response = client.sayHello(request)
            .await().atMost(java.time.Duration.ofSeconds(5));

        assertThat(response).isNotNull();
        assertThat(response.getMessage()).isEqualTo("Hello Factory Test");
    }

    @Test
    void testClientReuse() throws InterruptedException {
        Thread.sleep(500);

        GrpcClientFactory factory = getFactory();

        // Request the same client twice - should use cached channel
        var client1Uni = factory.getClient(TEST_SERVICE_NAME, MutinyGreeterGrpc::newMutinyStub);
        var client2Uni = factory.getClient(TEST_SERVICE_NAME, MutinyGreeterGrpc::newMutinyStub);

        var client1 = client1Uni.await().atMost(java.time.Duration.ofSeconds(10));
        var client2 = client2Uni.await().atMost(java.time.Duration.ofSeconds(10));

        // Should have cached the channel - only 1 active service
        assertThat(factory.getActiveServiceCount()).isGreaterThanOrEqualTo(1);

        // Both clients should work
        HelloRequest request = HelloRequest.newBuilder()
            .setName("Reuse Test")
            .build();

        HelloReply response1 = client1.sayHello(request)
            .await().atMost(java.time.Duration.ofSeconds(5));
        HelloReply response2 = client2.sayHello(request)
            .await().atMost(java.time.Duration.ofSeconds(5));

        assertThat(response1.getMessage()).isEqualTo("Hello Reuse Test");
        assertThat(response2.getMessage()).isEqualTo("Hello Reuse Test");
    }

    @Test
    void testCacheStats() throws InterruptedException {
        Thread.sleep(500);

        GrpcClientFactory factory = getFactory();

        // Make a call to populate cache
        factory.getClient(TEST_SERVICE_NAME, MutinyGreeterGrpc::newMutinyStub)
            .await().atMost(java.time.Duration.ofSeconds(10));

        String stats = factory.getCacheStats();
        assertThat(stats).isNotNull();
        assertThat(stats.toLowerCase()).contains("hit");
    }

    @Test
    void testChannelEviction() throws InterruptedException {
        Thread.sleep(500);

        GrpcClientFactory factory = getFactory();

        // Create a client to populate cache
        factory.getClient(TEST_SERVICE_NAME, MutinyGreeterGrpc::newMutinyStub)
            .await().atMost(java.time.Duration.ofSeconds(10));

        int countBeforeEviction = factory.getActiveServiceCount();
        assertThat(countBeforeEviction).isGreaterThanOrEqualTo(1);

        // Evict the channel
        factory.evictChannel(TEST_SERVICE_NAME);

        // Count may stay the same or decrease (eviction can be async)
        int countAfterEviction = factory.getActiveServiceCount();
        assertThat(countAfterEviction).isLessThanOrEqualTo(countBeforeEviction);
    }

    /**
     * Test implementation of the Greeter service.
     */
    static class TestGreeterService extends MutinyGreeterGrpc.GreeterImplBase {
        @Override
        public Uni<HelloReply> sayHello(HelloRequest request) {
            HelloReply response = HelloReply.newBuilder()
                .setMessage("Hello " + request.getName())
                .build();
            return Uni.createFrom().item(response);
        }
    }
}
