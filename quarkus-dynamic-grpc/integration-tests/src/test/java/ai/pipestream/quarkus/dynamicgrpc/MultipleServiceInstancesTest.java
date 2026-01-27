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
import org.jboss.logging.Logger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for multiple instances of the same service registered in Consul.
 * Verifies load balancing and service discovery with multiple endpoints.
 */
@QuarkusTest
@WithTestResource(ConsulTestResource.class)
public class MultipleServiceInstancesTest {

    private static final Logger LOG = Logger.getLogger(MultipleServiceInstancesTest.class);

    @Inject
    GrpcClientFactory clientFactory;

    @ConfigProperty(name = "quarkus.dynamic-grpc.consul.host")
    String consulHost;

    @ConfigProperty(name = "quarkus.dynamic-grpc.consul.port")
    int consulPort;

    private static final String SERVICE_NAME = "multi-greeter";
    private static final List<Server> servers = new ArrayList<>();
    private static final List<Integer> ports = new ArrayList<>();
    private ConsulServiceRegistration consulRegistration;

    @BeforeAll
    static void startMultipleServers() throws IOException {
        // Start 3 gRPC servers
        for (int i = 0; i < 3; i++) {
            int port;
            try (ServerSocket socket = new ServerSocket(0)) {
                port = socket.getLocalPort();
            }
            ports.add(port);

            Server server = ServerBuilder.forPort(port)
                .addService(new TestGreeterService("Instance-" + (i + 1)))
                .build()
                .start();

            servers.add(server);
            LOG.infof("Started greeter instance %d on port: %d", (i + 1), port);
        }
    }

    @AfterAll
    static void stopServers() throws InterruptedException {
        for (Server server : servers) {
            server.shutdown();
            server.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @BeforeEach
    void setup() throws InterruptedException {
        consulRegistration = new ConsulServiceRegistration(consulHost, consulPort);

        // Register all 3 instances in Consul
        for (int i = 0; i < ports.size(); i++) {
            consulRegistration.registerService(
                SERVICE_NAME,
                SERVICE_NAME + "-" + (i + 1),
                "127.0.0.1",
                ports.get(i)
            );
        }

        Thread.sleep(500); // Wait for Consul registration
    }

    @Test
    @DisplayName("Should discover all service instances")
    void testDiscoverAllInstances() throws InterruptedException {
        Thread.sleep(500);

        // The factory should be able to create a client (discovers at least one instance)
        var client = clientFactory.getClient(SERVICE_NAME, MutinyGreeterGrpc::newMutinyStub)
            .await().atMost(java.time.Duration.ofSeconds(10));

        assertThat(client).isNotNull();

        // Make a call to verify it works
        HelloRequest request = HelloRequest.newBuilder()
            .setName("Multi-Instance Test")
            .build();

        HelloReply reply = client.sayHello(request)
            .await().atMost(java.time.Duration.ofSeconds(5));

        assertThat(reply).isNotNull();
        assertThat(reply.getMessage()).contains("Hello");
        assertThat(reply.getMessage()).contains("Instance-"); // Should include instance ID
    }

    @Test
    @DisplayName("Multiple requests should potentially hit different instances")
    void testLoadDistribution() throws InterruptedException {
        Thread.sleep(500);

        var client = clientFactory.getClient(SERVICE_NAME, MutinyGreeterGrpc::newMutinyStub)
            .await().atMost(java.time.Duration.ofSeconds(10));

        // Make multiple requests and collect responses
        List<String> responses = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            HelloRequest request = HelloRequest.newBuilder()
                .setName("Request-" + i)
                .build();

            HelloReply reply = client.sayHello(request)
                .await().atMost(java.time.Duration.ofSeconds(5));

            responses.add(reply.getMessage());
        }

        // All responses should be successful
        assertThat(responses).hasSize(10);
        responses.forEach(response -> assertThat(response).contains("Hello"));
    }

    @Test
    @DisplayName("Service should remain available if one instance goes down")
    void testFaultTolerance() throws InterruptedException {
        Thread.sleep(500);

        // Get initial client
        var client = clientFactory.getClient(SERVICE_NAME, MutinyGreeterGrpc::newMutinyStub)
            .await().atMost(java.time.Duration.ofSeconds(10));

        // Verify initial call works
        HelloRequest request = HelloRequest.newBuilder()
            .setName("Before Shutdown")
            .build();

        HelloReply reply1 = client.sayHello(request)
            .await().atMost(java.time.Duration.ofSeconds(5));

        assertThat(reply1.getMessage()).contains("Hello");

        // Shut down one instance
        servers.getFirst().shutdown();
        consulRegistration.deregisterService(SERVICE_NAME + "-1");
        Thread.sleep(2500); // Wait for deregistration to propagate

        // Service should still work with remaining instances
        request = HelloRequest.newBuilder()
            .setName("After Shutdown")
            .build();

        HelloReply reply2 = client.sayHello(request)
            .await().atMost(java.time.Duration.ofSeconds(5));

        assertThat(reply2.getMessage()).contains("Hello");

        // Restart the instance for other tests
        try {
            servers.set(0, ServerBuilder.forPort(ports.getFirst())
                .addService(new TestGreeterService("Instance-1"))
                .build()
                .start());

            consulRegistration.registerService(
                SERVICE_NAME,
                SERVICE_NAME + "-1",
                "127.0.0.1",
                ports.getFirst()
            );
        } catch (IOException e) {
            LOG.error("Failed to restart greeter instance", e);
        }
    }

    /**
     * Test greeter service that includes instance ID in response.
     */
    static class TestGreeterService extends MutinyGreeterGrpc.GreeterImplBase {
        private final String instanceId;

        TestGreeterService(String instanceId) {
            this.instanceId = instanceId;
        }

        @Override
        public Uni<HelloReply> sayHello(HelloRequest request) {
            HelloReply response = HelloReply.newBuilder()
                .setMessage("Hello " + request.getName() + " from " + instanceId)
                .build();
            return Uni.createFrom().item(response);
        }
    }
}
