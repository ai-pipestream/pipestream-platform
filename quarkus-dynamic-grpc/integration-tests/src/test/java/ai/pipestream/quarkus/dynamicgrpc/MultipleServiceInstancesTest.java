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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

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

    private static final List<Server> servers = new ArrayList<>();
    private static final List<Integer> ports = new ArrayList<>();
    private ConsulServiceRegistration consulRegistration;
    private String serviceName;

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
    void setup() {
        consulRegistration = new ConsulServiceRegistration(consulHost, consulPort);
        serviceName = "multi-greeter-" + UUID.randomUUID().toString().substring(0, 8);

        // Register all 3 instances in Consul
        for (int i = 0; i < ports.size(); i++) {
            consulRegistration.registerService(
                serviceName,
                serviceName + "-" + (i + 1),
                "127.0.0.1",
                ports.get(i)
            );
        }
    }

    @AfterEach
    void cleanup() {
        if (consulRegistration != null && serviceName != null) {
            for (int i = 0; i < ports.size(); i++) {
                try {
                    consulRegistration.deregisterService(serviceName + "-" + (i + 1));
                } catch (Exception ignore) {}
            }
        }
    }

    @Test
    @DisplayName("Should discover all service instances")
    void testDiscoverAllInstances() {
        // Wait for discovery to work
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(clientFactory.getClient(serviceName, MutinyGreeterGrpc::newMutinyStub).await().atMost(Duration.ofSeconds(1)))
                .as("Client should be discoverable with multiple instances")
                .isNotNull();
        });

        // The factory should be able to create a client (discovers at least one instance)
        var client = clientFactory.getClient(serviceName, MutinyGreeterGrpc::newMutinyStub)
            .await().atMost(Duration.ofSeconds(10));

        assertThat(client).as("Client stub should not be null").isNotNull();

        // Make a call to verify it works
        HelloRequest request = HelloRequest.newBuilder()
            .setName("Multi-Instance Test")
            .build();

        HelloReply reply = client.sayHello(request)
            .await().atMost(Duration.ofSeconds(5));

        assertThat(reply).as("Response should not be null").isNotNull();
        assertThat(reply.getMessage())
            .as("Response message should contain instance identifier")
            .contains("Hello")
            .contains("Instance-");
    }

    @Test
    @DisplayName("Multiple requests should potentially hit different instances")
    void testLoadDistribution() {
        // Wait for discovery
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(clientFactory.getClient(serviceName, MutinyGreeterGrpc::newMutinyStub).await().atMost(Duration.ofSeconds(1)))
                .as("Client should be discoverable for load distribution test")
                .isNotNull();
        });

        var client = clientFactory.getClient(serviceName, MutinyGreeterGrpc::newMutinyStub)
            .await().atMost(Duration.ofSeconds(10));

        // Make multiple requests and collect responses
        List<String> responses = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            HelloRequest request = HelloRequest.newBuilder()
                .setName("Request-" + i)
                .build();

            HelloReply reply = client.sayHello(request)
                .await().atMost(Duration.ofSeconds(5));

            responses.add(reply.getMessage());
        }

        // All responses should be successful
        assertThat(responses)
            .as("Should have collected 10 responses")
            .hasSize(10);
        
        responses.forEach(response -> 
            assertThat(response)
                .as("Response message should be valid")
                .contains("Hello"));
    }

    @Test
    @DisplayName("Service should remain available if one instance goes down")
    void testFaultTolerance() throws Exception {
        // Use dedicated servers for this test — do NOT touch the shared static servers
        String ftServiceName = "ft-greeter-" + UUID.randomUUID().toString().substring(0, 8);
        int ftPort1, ftPort2;
        try (ServerSocket s = new ServerSocket(0)) { ftPort1 = s.getLocalPort(); }
        try (ServerSocket s = new ServerSocket(0)) { ftPort2 = s.getLocalPort(); }

        Server ftServer1 = ServerBuilder.forPort(ftPort1)
            .addService(new TestGreeterService("FT-1"))
            .build().start();
        Server ftServer2 = ServerBuilder.forPort(ftPort2)
            .addService(new TestGreeterService("FT-2"))
            .build().start();

        consulRegistration.registerService(ftServiceName, ftServiceName + "-1", "127.0.0.1", ftPort1);
        consulRegistration.registerService(ftServiceName, ftServiceName + "-2", "127.0.0.1", ftPort2);

        // Wait for discovery
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            var c = clientFactory.getClient(ftServiceName, MutinyGreeterGrpc::newMutinyStub)
                .await().atMost(Duration.ofSeconds(1));
            HelloReply r = c.sayHello(HelloRequest.newBuilder().setName("Init").build())
                .await().atMost(Duration.ofSeconds(2));
            assertThat(r.getMessage()).contains("Hello");
        });

        // Shut down one instance
        ftServer1.shutdownNow();
        consulRegistration.deregisterService(ftServiceName + "-1");
        clientFactory.evictChannel(ftServiceName);

        // Service should still work via ftServer2 — get a fresh client after eviction
        await().atMost(Duration.ofSeconds(15))
            .pollInterval(Duration.ofMillis(500))
            .untilAsserted(() -> {
                try {
                    var newClient = clientFactory.getClient(ftServiceName, MutinyGreeterGrpc::newMutinyStub)
                        .await().atMost(Duration.ofSeconds(2));
                    HelloReply reply = newClient.sayHello(HelloRequest.newBuilder().setName("After Shutdown").build())
                        .await().atMost(Duration.ofSeconds(2));
                    assertThat(reply.getMessage())
                        .as("Should fall back to surviving instance")
                        .contains("Hello");
                } catch (Exception e) {
                    clientFactory.evictChannel(ftServiceName);
                    throw e;
                }
            });

        // Cleanup
        ftServer2.shutdownNow();
        try { consulRegistration.deregisterService(ftServiceName + "-2"); } catch (Exception ignore) {}
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
