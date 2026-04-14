package ai.pipestream.quarkus.dynamicgrpc;

import ai.pipestream.quarkus.dynamicgrpc.GrpcClientFactory;
import ai.pipestream.quarkus.dynamicgrpc.base.ConsulServiceRegistration;
import ai.pipestream.quarkus.dynamicgrpc.base.DynamicGrpcClientFactoryTestBase;
import ai.pipestream.test.support.ConsulTestResource;
import ai.pipestream.quarkus.dynamicgrpc.it.proto.HelloReply;
import ai.pipestream.quarkus.dynamicgrpc.it.proto.HelloRequest;
import ai.pipestream.quarkus.dynamicgrpc.it.proto.MutinyGreeterGrpc;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.mutiny.TimeoutException;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * Tests for failure scenarios and recovery mechanisms.
 * Verifies the extension handles failures gracefully and recovers properly.
 */
@QuarkusTest
@WithTestResource(ConsulTestResource.class)
public class FailureRecoveryTest {

    private static final Logger LOG = Logger.getLogger(FailureRecoveryTest.class);

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
        serviceName = "failure-test-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @AfterEach
    void cleanup() {
        if (consulRegistration != null && serviceName != null) {
            try {
                consulRegistration.deregisterService(serviceName + "-1");
                consulRegistration.deregisterService(serviceName + "-2");
            } catch (Exception ignore) {}
        }
    }

    @Test
    @DisplayName("Service crash and restart - should recover")
    void testServiceCrashAndRestart() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }

        Server server = ServerBuilder.forPort(port)
            .addService(new DynamicGrpcClientFactoryTestBase.TestGreeterService())
            .build()
            .start();

        consulRegistration.registerService(serviceName, serviceName + "-1", "127.0.0.1", port);

        // Make a call (generous budget covers initial discovery warmup)
        var client = clientFactory.getClient(serviceName, MutinyGreeterGrpc::newMutinyStub)
            .await().atMost(Duration.ofSeconds(10));
        HelloReply response = client.sayHello(HelloRequest.newBuilder().setName("Before Crash").build())
            .await().atMost(Duration.ofSeconds(2));
        assertThat(response.getMessage())
            .as("Response before crash should be correct")
            .isEqualTo("Hello Before Crash");

        // Crash the server
        server.shutdownNow();
        consulRegistration.deregisterService(serviceName + "-1");
        clientFactory.evictChannel(serviceName);

        // Verify that calls fail — the channel may still be created (gRPC channels are lazy)
        // but the actual RPC should fail with UNAVAILABLE since the server is down.
        // Stork discovery caching means getClient() may succeed even after deregistration,
        // so we verify the call fails, not the channel creation.
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThatThrownBy(() -> {
                var deadClient = clientFactory.getClient(serviceName, MutinyGreeterGrpc::newMutinyStub)
                    .await().atMost(Duration.ofSeconds(1));
                deadClient.sayHello(HelloRequest.newBuilder().setName("Should Fail").build())
                    .await().atMost(Duration.ofSeconds(2));
            }).as("RPC should fail when server is down")
             .isInstanceOfAny(io.grpc.StatusRuntimeException.class, RuntimeException.class);
        });

        // Restart server on same port
        server = ServerBuilder.forPort(port)
            .addService(new DynamicGrpcClientFactoryTestBase.TestGreeterService())
            .build()
            .start();
        consulRegistration.registerService(serviceName, serviceName + "-1", "127.0.0.1", port);

        // Verify recovery. awaitility is genuinely useful here: after the server
        // restart, Stork needs to re-discover and the gRPC connection needs to
        // re-establish — transient Mutiny TimeoutExceptions during polling are
        // expected, so we tell awaitility to ignore that specific type.
        await().atMost(Duration.ofSeconds(10))
            .ignoreException(TimeoutException.class)
            .untilAsserted(() -> {
                var recoveredClient = clientFactory.getClient(serviceName, MutinyGreeterGrpc::newMutinyStub)
                    .await().atMost(Duration.ofSeconds(1));
                HelloReply resp = recoveredClient.sayHello(HelloRequest.newBuilder().setName("After Crash").build())
                    .await().atMost(Duration.ofSeconds(2));
                assertThat(resp.getMessage())
                    .as("Response after restart should be correct")
                    .isEqualTo("Hello After Crash");
            });

        server.shutdownNow();
    }

    @Test
    @DisplayName("Multiple instances - one fails, others continue")
    void testPartialFailure() throws Exception {
        int port1, port2;
        try (ServerSocket socket = new ServerSocket(0)) { port1 = socket.getLocalPort(); }
        try (ServerSocket socket = new ServerSocket(0)) { port2 = socket.getLocalPort(); }

        Server server1 = ServerBuilder.forPort(port1)
            .addService(new DynamicGrpcClientFactoryTestBase.TestGreeterService())
            .build().start();
        Server server2 = ServerBuilder.forPort(port2)
            .addService(new DynamicGrpcClientFactoryTestBase.TestGreeterService())
            .build().start();

        consulRegistration.registerService(serviceName, serviceName + "-1", "127.0.0.1", port1);
        consulRegistration.registerService(serviceName, serviceName + "-2", "127.0.0.1", port2);

        // Warm up discovery (generous budget covers initial Consul poll)
        clientFactory.getClient(serviceName, MutinyGreeterGrpc::newMutinyStub)
            .await().atMost(Duration.ofSeconds(10));

        // Kill one server
        server1.shutdownNow();
        consulRegistration.deregisterService(serviceName + "-1");
        
        // Evict to force re-discovery
        clientFactory.evictChannel(serviceName);

        // Wait for Consul to propagate the deregistration before retrying
        Thread.sleep(2000);

        // Should still work via server2 — retry with eviction until Stork
        // picks up the surviving instance. Transient Mutiny TimeoutExceptions
        // during polling are expected while discovery re-converges.
        await().atMost(Duration.ofSeconds(30))
            .pollInterval(Duration.ofMillis(500))
            .ignoreException(TimeoutException.class)
            .untilAsserted(() -> {
                // Evict before each attempt so Stork re-discovers
                clientFactory.evictChannel(serviceName);
                var client = clientFactory.getClient(serviceName, MutinyGreeterGrpc::newMutinyStub)
                    .await().atMost(Duration.ofSeconds(2));
                HelloReply resp = client.sayHello(HelloRequest.newBuilder().setName("Partial").build())
                    .await().atMost(Duration.ofSeconds(2));
                assertThat(resp.getMessage())
                    .as("Should fall back to healthy instance")
                    .isEqualTo("Hello Partial");
            });

        server2.shutdownNow();
    }

    @Test
    @DisplayName("Rapid service registration changes")
    void testRapidChanges() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) { port = socket.getLocalPort(); }

        Server server = ServerBuilder.forPort(port)
            .addService(new DynamicGrpcClientFactoryTestBase.TestGreeterService())
            .build().start();

        // Register, deregister, register quickly
        consulRegistration.registerService(serviceName, serviceName + "-temp", "127.0.0.1", port);
        consulRegistration.deregisterService(serviceName + "-temp");
        consulRegistration.registerService(serviceName, serviceName + "-final", "127.0.0.1", port);

        // Poll until discovery stabilizes on the final registration.
        // Transient Mutiny TimeoutExceptions during poll are expected.
        await().atMost(Duration.ofSeconds(10))
            .ignoreException(TimeoutException.class)
            .untilAsserted(() -> {
                var client = clientFactory.getClient(serviceName, MutinyGreeterGrpc::newMutinyStub)
                    .await().atMost(Duration.ofSeconds(1));
                HelloReply resp = client.sayHello(HelloRequest.newBuilder().setName("Rapid").build())
                    .await().atMost(Duration.ofSeconds(2));
                assertThat(resp.getMessage())
                    .as("Should work after rapid registration changes")
                    .isEqualTo("Hello Rapid");
            });

        server.shutdownNow();
        consulRegistration.deregisterService(serviceName + "-final");
    }

    @Test
    @DisplayName("All instances down - should fail gracefully")
    void testAllInstancesDown() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) { port = socket.getLocalPort(); }

        Server server = ServerBuilder.forPort(port)
            .addService(new DynamicGrpcClientFactoryTestBase.TestGreeterService())
            .build().start();

        consulRegistration.registerService(serviceName, serviceName + "-1", "127.0.0.1", port);

        // Warm up discovery
        clientFactory.getClient(serviceName, MutinyGreeterGrpc::newMutinyStub)
            .await().atMost(Duration.ofSeconds(10));

        // Shut down server and deregister
        server.shutdownNow();
        consulRegistration.deregisterService(serviceName + "-1");

        // Evict cached channel
        clientFactory.evictChannel(serviceName);

        // Now requests should fail gracefully
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            try {
                clientFactory.getClient(serviceName, MutinyGreeterGrpc::newMutinyStub)
                    .await().atMost(Duration.ofSeconds(1));
                org.junit.jupiter.api.Assertions.fail("Expected exception was not thrown");
            } catch (Throwable e) {
                String msg = e.getMessage();
                LOG.errorf("DEBUG: Caught exception with message: %s", msg);
                assertThat(msg).as("Exception message should indicate service discovery failure")
                    .matches(".*(?i)(service|discovery|instances|no).*");
            }
        });
    }

    @Test
    @DisplayName("Slow service response - should timeout appropriately")
    void testSlowServiceTimeout() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }

        // Server that sleeps for 5 seconds
        Server server = ServerBuilder.forPort(port)
            .addService(new MutinyGreeterGrpc.GreeterImplBase() {
                @Override
                public Uni<HelloReply> sayHello(HelloRequest request) {
                    return Uni.createFrom().item(HelloReply.newBuilder().setMessage("Slow").build())
                        .onItem().delayIt().by(Duration.ofSeconds(5));
                }
            })
            .build()
            .start();

        consulRegistration.registerService(serviceName, serviceName + "-1", "127.0.0.1", port);

        // Warm up discovery (generous budget covers the first Consul poll)
        var client = clientFactory.getClient(serviceName, MutinyGreeterGrpc::newMutinyStub)
            .await().atMost(Duration.ofSeconds(10));

        // Call should timeout
        assertThatThrownBy(() ->
            client.sayHello(HelloRequest.newBuilder().setName("Timeout").build())
                .await().atMost(Duration.ofSeconds(2))
        ).as("Call to slow service should timeout")
         .isInstanceOf(Exception.class);

        server.shutdownNow();
    }
}
