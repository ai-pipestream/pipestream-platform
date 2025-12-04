package ai.pipestream.quarkus.dynamicgrpc;

import ai.pipestream.quarkus.dynamicgrpc.GrpcClientFactory;
import ai.pipestream.quarkus.dynamicgrpc.base.ConsulServiceRegistration;
import ai.pipestream.quarkus.dynamicgrpc.base.ConsulTestResource;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for failure scenarios and recovery mechanisms.
 * Verifies the extension handles failures gracefully and recovers properly.
 */
@QuarkusTest
@WithTestResource(ConsulTestResource.class)
public class FailureRecoveryTest {

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
    @DisplayName("Service crash and restart - should recover")
    void testServiceCrashAndRestart() throws Exception {
        String serviceName = "crash-test-service";
        int port;

        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }

        // Start initial server
        Server server = ServerBuilder.forPort(port)
            .addService(new TestGreeterService("v1"))
            .build()
            .start();

        consulRegistration.registerService(serviceName, serviceName + "-1", "127.0.0.1", port);
        Thread.sleep(500);

        // Make successful call
        var client = clientFactory.getClient(serviceName, MutinyGreeterGrpc::newMutinyStub)
            .await().atMost(Duration.ofSeconds(5));

        HelloReply reply1 = client.sayHello(
            HelloRequest.newBuilder().setName("Before Crash").build()
        ).await().atMost(Duration.ofSeconds(3));

        assertThat(reply1.getMessage()).contains("Hello Before Crash");

        // Simulate crash
        server.shutdownNow();
        server.awaitTermination(2, TimeUnit.SECONDS);
        Thread.sleep(1000);

        // Restart server
        server = ServerBuilder.forPort(port)
            .addService(new TestGreeterService("v2"))
            .build()
            .start();

        Thread.sleep(1000);

        // Should recover and work again (may need to evict old channel)
        clientFactory.evictChannel(serviceName);

        var newClient = clientFactory.getClient(serviceName, MutinyGreeterGrpc::newMutinyStub)
            .await().atMost(Duration.ofSeconds(5));

        HelloReply reply2 = newClient.sayHello(
            HelloRequest.newBuilder().setName("After Restart").build()
        ).await().atMost(Duration.ofSeconds(3));

        assertThat(reply2.getMessage()).contains("Hello After Restart");

        // Cleanup
        server.shutdown();
        consulRegistration.deregisterService(serviceName + "-1");
    }

    @Test
    @DisplayName("Multiple instances - one fails, others continue")
    void testPartialInstanceFailure() throws Exception {
        String serviceName = "partial-failure-service";
        int port1, port2;

        try (ServerSocket socket = new ServerSocket(0)) {
            port1 = socket.getLocalPort();
        }
        try (ServerSocket socket = new ServerSocket(0)) {
            port2 = socket.getLocalPort();
        }

        // Start two instances
        Server server1 = ServerBuilder.forPort(port1)
            .addService(new TestGreeterService("instance-1"))
            .build()
            .start();

        Server server2 = ServerBuilder.forPort(port2)
            .addService(new TestGreeterService("instance-2"))
            .build()
            .start();

        consulRegistration.registerService(serviceName, serviceName + "-1", "127.0.0.1", port1);
        consulRegistration.registerService(serviceName, serviceName + "-2", "127.0.0.1", port2);
        Thread.sleep(1000);

        // Get client and make initial call
        var client = clientFactory.getClient(serviceName, MutinyGreeterGrpc::newMutinyStub)
            .await().atMost(Duration.ofSeconds(5));

        HelloReply reply1 = client.sayHello(
            HelloRequest.newBuilder().setName("Both Running").build()
        ).await().atMost(Duration.ofSeconds(3));

        assertThat(reply1.getMessage()).contains("Hello");

        // Kill first instance
        server1.shutdownNow();
        consulRegistration.deregisterService(serviceName + "-1");
        Thread.sleep(2500); // Wait for deregistration

        // Service should still work via second instance
        HelloReply reply2 = client.sayHello(
            HelloRequest.newBuilder().setName("One Down").build()
        ).await().atMost(Duration.ofSeconds(5));

        assertThat(reply2.getMessage()).contains("Hello");

        // Cleanup
        server2.shutdown();
        consulRegistration.deregisterService(serviceName + "-2");
    }

    @Test
    @DisplayName("All instances down - should fail gracefully")
    void testAllInstancesDown() throws Exception {
        String serviceName = "all-down-service";
        int port;

        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }

        Server server = ServerBuilder.forPort(port)
            .addService(new TestGreeterService("temp"))
            .build()
            .start();

        consulRegistration.registerService(serviceName, serviceName + "-1", "127.0.0.1", port);
        Thread.sleep(500);

        // Get client while service is up
        var client = clientFactory.getClient(serviceName, MutinyGreeterGrpc::newMutinyStub)
            .await().atMost(Duration.ofSeconds(5));

        // Shut down server and deregister
        server.shutdownNow();
        consulRegistration.deregisterService(serviceName + "-1");
        Thread.sleep(2500);

        // Evict cached channel
        clientFactory.evictChannel(serviceName);

        // Now requests should fail gracefully
        assertThatThrownBy(() ->
            clientFactory.getClient(serviceName, MutinyGreeterGrpc::newMutinyStub)
                .await().atMost(Duration.ofSeconds(5))
        ).hasMessageContaining("service");
    }

    @Test
    @DisplayName("Slow service response - should timeout appropriately")
    void testSlowServiceTimeout() throws Exception {
        String serviceName = "slow-service";
        int port;

        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }

        Server server = ServerBuilder.forPort(port)
            .addService(new SlowGreeterService(5000)) // 5 second delay
            .build()
            .start();

        consulRegistration.registerService(serviceName, serviceName + "-1", "127.0.0.1", port);
        Thread.sleep(500);

        var client = clientFactory.getClient(serviceName, MutinyGreeterGrpc::newMutinyStub)
            .await().atMost(Duration.ofSeconds(5));

        // Short timeout should fail
        assertThatThrownBy(() ->
            client.sayHello(HelloRequest.newBuilder().setName("Quick").build())
                .await().atMost(Duration.ofSeconds(2))
        );

        // Longer timeout should succeed
        HelloReply reply = client.sayHello(
            HelloRequest.newBuilder().setName("Patient").build()
        ).await().atMost(Duration.ofSeconds(10));

        assertThat(reply.getMessage()).contains("Hello Patient");

        // Cleanup
        server.shutdown();
        consulRegistration.deregisterService(serviceName + "-1");
    }

    @Test
    @DisplayName("Rapid service registration changes")
    void testRapidServiceChanges() throws Exception {
        String serviceName = "changing-service";
        int port;

        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }

        Server server = ServerBuilder.forPort(port)
            .addService(new TestGreeterService("original"))
            .build()
            .start();

        // Register, deregister, re-register rapidly
        for (int i = 0; i < 5; i++) {
            consulRegistration.registerService(serviceName, serviceName + "-temp-" + i, "127.0.0.1", port);
            Thread.sleep(200);
            consulRegistration.deregisterService(serviceName + "-temp-" + i);
            Thread.sleep(200);
        }

        // Final registration
        consulRegistration.registerService(serviceName, serviceName + "-final", "127.0.0.1", port);
        Thread.sleep(1000);

        // Should eventually work
        var client = clientFactory.getClient(serviceName, MutinyGreeterGrpc::newMutinyStub)
            .await().atMost(Duration.ofSeconds(10));

        HelloReply reply = client.sayHello(
            HelloRequest.newBuilder().setName("After Changes").build()
        ).await().atMost(Duration.ofSeconds(3));

        assertThat(reply.getMessage()).contains("Hello");

        // Cleanup
        server.shutdown();
        consulRegistration.deregisterService(serviceName + "-final");
    }

    /**
     * Normal test greeter service.
     */
    static class TestGreeterService extends MutinyGreeterGrpc.GreeterImplBase {
        private final String version;

        TestGreeterService(String version) {
            this.version = version;
        }

        @Override
        public Uni<HelloReply> sayHello(HelloRequest request) {
            HelloReply response = HelloReply.newBuilder()
                .setMessage("Hello " + request.getName() + " (" + version + ")")
                .build();
            return Uni.createFrom().item(response);
        }
    }

    /**
     * Slow greeter service for timeout testing.
     */
    static class SlowGreeterService extends MutinyGreeterGrpc.GreeterImplBase {
        private final long delayMs;

        SlowGreeterService(long delayMs) {
            this.delayMs = delayMs;
        }

        @Override
        public Uni<HelloReply> sayHello(HelloRequest request) {
            return Uni.createFrom().item(request)
                .onItem().delayIt().by(Duration.ofMillis(delayMs))
                .onItem().transform(req -> HelloReply.newBuilder()
                    .setMessage("Hello " + req.getName() + " (slow)")
                    .build());
        }
    }
}
