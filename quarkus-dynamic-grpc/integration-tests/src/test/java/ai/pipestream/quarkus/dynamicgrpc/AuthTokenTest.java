package ai.pipestream.quarkus.dynamicgrpc;

import ai.pipestream.quarkus.dynamicgrpc.base.ConsulServiceRegistration;
import ai.pipestream.test.support.ConsulTestResource;
import ai.pipestream.quarkus.dynamicgrpc.it.proto.HelloReply;
import ai.pipestream.quarkus.dynamicgrpc.it.proto.HelloRequest;
import ai.pipestream.quarkus.dynamicgrpc.it.proto.MutinyGreeterGrpc;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Integration tests for authentication token functionality.
 * Tests that tokens are correctly added to gRPC metadata headers.
 */
@QuarkusTest
@QuarkusTestResource(ConsulTestResource.class)
@TestProfile(AuthTokenTest.AuthEnabledProfile.class)
class AuthTokenTest {

    /**
     * Profile that enables auth and activates the auth-test build profile
     * so TestAuthTokenProvider is created by CDI.
     */
    public static class AuthEnabledProfile implements QuarkusTestProfile {
        @Override
        public java.util.Map<String, String> getConfigOverrides() {
            return java.util.Map.of(
                "quarkus.dynamic-grpc.auth.enabled", "true"
            );
        }

        @Override
        public String getConfigProfile() {
            return "auth-test";
        }
    }


    private static final Logger LOG = Logger.getLogger(AuthTokenTest.class);

    private static Server testGrpcServer;
    private static int testGrpcPort;
    private static final AtomicReference<String> receivedToken = new AtomicReference<>();

    private ConsulServiceRegistration consulRegistration;
    private String serviceName;

    @Inject
    GrpcClientFactory factory;

    @ConfigProperty(name = "quarkus.dynamic-grpc.consul.host")
    String consulHost;

    @ConfigProperty(name = "quarkus.dynamic-grpc.consul.port")
    int consulPort;

    @BeforeAll
    static void startTestServer() throws IOException {
        // Find an available random port
        try (ServerSocket socket = new ServerSocket(0)) {
            testGrpcPort = socket.getLocalPort();
        }

        // Start gRPC server with auth interceptor that validates the token
        testGrpcServer = ServerBuilder.forPort(testGrpcPort)
            .addService(new TestGreeterService())
            .intercept(new AuthValidationInterceptor())
            .build()
            .start();

        LOG.infof("Auth test gRPC server started on port: %d", testGrpcPort);
    }

    @BeforeEach
    void setup() {
        consulRegistration = new ConsulServiceRegistration(consulHost, consulPort);
        serviceName = "auth-test-" + UUID.randomUUID().toString().substring(0, 8);

        // Register service in Consul
        consulRegistration.registerService(
            serviceName,
            serviceName + "-1",
            "127.0.0.1",
            testGrpcPort
        );

        LOG.infof("Registered %s in Consul at 127.0.0.1:%d", serviceName, testGrpcPort);

        // Reset received token
        receivedToken.set(null);
    }

    @AfterAll
    static void stopTestServer() throws InterruptedException {
        if (testGrpcServer != null) {
            testGrpcServer.shutdown();
            testGrpcServer.awaitTermination(5, TimeUnit.SECONDS);
        }
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
    void shouldAddAuthTokenToRequest() {
        // Wait for Consul registration
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(factory.getClient(serviceName, MutinyGreeterGrpc::newMutinyStub)
                .await().atMost(Duration.ofSeconds(1)))
                .as("Client should be discoverable for auth test")
                .isNotNull();
        });

        // Create client and make request
        var client = factory.getClient(serviceName, MutinyGreeterGrpc::newMutinyStub)
            .await().atMost(Duration.ofSeconds(5));

        HelloRequest request = HelloRequest.newBuilder()
            .setName("Auth Test")
            .build();

        HelloReply response = client.sayHello(request)
            .await().atMost(Duration.ofSeconds(5));

        // Verify response
        assertThat(response).as("Response should be received").isNotNull();
        assertThat(response.getMessage()).as("Response message should be correct").isEqualTo("Hello Auth Test");

        // Verify the server received the token in correct format
        assertThat(receivedToken.get())
            .as("Server should have received the Bearer token")
            .isEqualTo("Bearer " + TestAuthTokenProvider.TEST_TOKEN);
    }

    @Test
    void shouldIncludeTokenInMultipleRequests() {
        // Wait for Consul registration
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(factory.getClient(serviceName, MutinyGreeterGrpc::newMutinyStub)
                .await().atMost(Duration.ofSeconds(1)))
                .as("Client should be discoverable")
                .isNotNull();
        });

        var client = factory.getClient(serviceName, MutinyGreeterGrpc::newMutinyStub)
            .await().atMost(Duration.ofSeconds(10));

        // Make multiple requests
        for (int i = 0; i < 3; i++) {
            receivedToken.set(null); // Reset

            HelloRequest request = HelloRequest.newBuilder()
                .setName("Request " + i)
                .build();

            client.sayHello(request).await().atMost(Duration.ofSeconds(5));

            // Each request should have the token
            assertThat(receivedToken.get())
                .as("Request " + i + " should contain the Bearer token")
                .isEqualTo("Bearer " + TestAuthTokenProvider.TEST_TOKEN);
        }
    }

    /**
     * Server interceptor that validates auth tokens.
     * Captures the received token for test verification.
     */
    static class AuthValidationInterceptor implements ServerInterceptor {
        @Override
        public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
                ServerCall<ReqT, RespT> call,
                Metadata headers,
                ServerCallHandler<ReqT, RespT> next) {

            // Extract auth header
            Metadata.Key<String> authKey = Metadata.Key.of("Authorization", Metadata.ASCII_STRING_MARSHALLER);
            String authHeader = headers.get(authKey);

            // Store for test verification
            receivedToken.set(authHeader);

            // Validate token exists
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                call.close(Status.UNAUTHENTICATED.withDescription("Missing or invalid auth token"), new Metadata());
                return new ServerCall.Listener<>() {};
            }

            return next.startCall(call, headers);
        }
    }

    /**
     * Test greeter service implementation.
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
