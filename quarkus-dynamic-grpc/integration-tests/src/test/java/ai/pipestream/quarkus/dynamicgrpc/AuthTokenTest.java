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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for authentication token functionality.
 * Tests that tokens are correctly added to gRPC metadata headers.
 */
@QuarkusTest
@QuarkusTestResource(ConsulTestResource.class)
@TestProfile(AuthTokenTest.AuthEnabledProfile.class)
class AuthTokenTest {

    private static final Logger LOG = Logger.getLogger(AuthTokenTest.class);
    private static final String AUTH_SERVICE_NAME = "auth-test-service";

    private static Server testGrpcServer;
    private static int testGrpcPort;
    private static ConsulServiceRegistration consulRegistration;
    private static final AtomicReference<String> receivedToken = new AtomicReference<>();

    @Inject
    GrpcClientFactory factory;

    @ConfigProperty(name = "quarkus.dynamic-grpc.consul.host")
    String consulHost;

    @ConfigProperty(name = "quarkus.dynamic-grpc.consul.port")
    int consulPort;

    /**
     * Test profile that enables auth and activates the test token provider.
     */
    public static class AuthEnabledProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                "quarkus.dynamic-grpc.auth.enabled", "true",
                "quarkus.dynamic-grpc.auth.header-name", "Authorization",
                "quarkus.dynamic-grpc.auth.scheme-prefix", "Bearer "
            );
        }

        @Override
        public String getConfigProfile() {
            return "auth-test";
        }
    }

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
        if (consulRegistration == null) {
            consulRegistration = new ConsulServiceRegistration(consulHost, consulPort);
        }

        // Register service in Consul
        consulRegistration.registerService(
            AUTH_SERVICE_NAME,
            AUTH_SERVICE_NAME + "-1",
            "127.0.0.1",
            testGrpcPort
        );

        LOG.infof("Registered %s in Consul at 127.0.0.1:%d", AUTH_SERVICE_NAME, testGrpcPort);

        // Reset received token
        receivedToken.set(null);
    }

    @AfterAll
    static void stopTestServer() throws InterruptedException {
        if (consulRegistration != null) {
            consulRegistration.deregisterService(AUTH_SERVICE_NAME + "-1");
        }

        if (testGrpcServer != null) {
            testGrpcServer.shutdown();
            testGrpcServer.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void shouldAddAuthTokenToRequest() throws InterruptedException {
        // Wait for Consul registration
        Thread.sleep(500);

        // Create client and make request
        var client = factory.getClient(AUTH_SERVICE_NAME, MutinyGreeterGrpc::newMutinyStub)
            .await().atMost(java.time.Duration.ofSeconds(10));

        HelloRequest request = HelloRequest.newBuilder()
            .setName("Auth Test")
            .build();

        HelloReply response = client.sayHello(request)
            .await().atMost(java.time.Duration.ofSeconds(5));

        // Verify response
        assertThat(response).isNotNull();
        assertThat(response.getMessage()).isEqualTo("Hello Auth Test");

        // Verify the server received the token in correct format
        assertThat(receivedToken.get()).isEqualTo("Bearer " + TestAuthTokenProvider.TEST_TOKEN);
    }

    @Test
    void shouldIncludeTokenInMultipleRequests() throws InterruptedException {
        Thread.sleep(500);

        var client = factory.getClient(AUTH_SERVICE_NAME, MutinyGreeterGrpc::newMutinyStub)
            .await().atMost(java.time.Duration.ofSeconds(10));

        // Make multiple requests
        for (int i = 0; i < 3; i++) {
            receivedToken.set(null); // Reset

            HelloRequest request = HelloRequest.newBuilder()
                .setName("Request " + i)
                .build();

            client.sayHello(request)
                .await().atMost(java.time.Duration.ofSeconds(5));

            // Each request should have the token
            assertThat(receivedToken.get()).isEqualTo("Bearer " + TestAuthTokenProvider.TEST_TOKEN);
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
