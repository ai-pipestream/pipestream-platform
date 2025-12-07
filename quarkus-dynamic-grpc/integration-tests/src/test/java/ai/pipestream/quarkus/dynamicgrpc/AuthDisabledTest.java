package ai.pipestream.quarkus.dynamicgrpc;

import ai.pipestream.quarkus.dynamicgrpc.base.ConsulServiceRegistration;
import ai.pipestream.quarkus.dynamicgrpc.base.ConsulTestResource;
import ai.pipestream.quarkus.dynamicgrpc.it.proto.HelloReply;
import ai.pipestream.quarkus.dynamicgrpc.it.proto.HelloRequest;
import ai.pipestream.quarkus.dynamicgrpc.it.proto.MutinyGreeterGrpc;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
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
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests to verify behavior when auth is disabled.
 * Ensures no auth tokens are sent when auth.enabled=false.
 */
@QuarkusTest
@QuarkusTestResource(ConsulTestResource.class)
@TestProfile(AuthDisabledTest.AuthDisabledProfile.class)
class AuthDisabledTest {

    private static final Logger LOG = Logger.getLogger(AuthDisabledTest.class);
    private static final String AUTH_DISABLED_SERVICE = "auth-disabled-service";

    private static Server testGrpcServer;
    private static int testGrpcPort;
    private static ConsulServiceRegistration consulRegistration;
    private static final AtomicBoolean receivedAuthHeader = new AtomicBoolean(false);

    @Inject
    GrpcClientFactory factory;

    @ConfigProperty(name = "quarkus.dynamic-grpc.consul.host")
    String consulHost;

    @ConfigProperty(name = "quarkus.dynamic-grpc.consul.port")
    int consulPort;

    /**
     * Test profile with auth disabled (default config, no token provider).
     */
    public static class AuthDisabledProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                "quarkus.dynamic-grpc.auth.enabled", "false"
            );
        }
    }

    @BeforeAll
    static void startTestServer() throws IOException {
        // Find an available random port
        try (ServerSocket socket = new ServerSocket(0)) {
            testGrpcPort = socket.getLocalPort();
        }

        // Start gRPC server with interceptor that checks for auth headers
        testGrpcServer = ServerBuilder.forPort(testGrpcPort)
            .addService(new TestGreeterService())
            .intercept(new NoAuthExpectedInterceptor())
            .build()
            .start();

        LOG.infof("Auth-disabled test gRPC server started on port: %d", testGrpcPort);
    }

    @BeforeEach
    void setup() {
        if (consulRegistration == null) {
            consulRegistration = new ConsulServiceRegistration(consulHost, consulPort);
        }

        consulRegistration.registerService(
            AUTH_DISABLED_SERVICE,
            AUTH_DISABLED_SERVICE + "-1",
            "127.0.0.1",
            testGrpcPort
        );

        LOG.infof("Registered %s in Consul at 127.0.0.1:%d", AUTH_DISABLED_SERVICE, testGrpcPort);

        // Reset flag
        receivedAuthHeader.set(false);
    }

    @AfterAll
    static void stopTestServer() throws InterruptedException {
        if (consulRegistration != null) {
            consulRegistration.deregisterService(AUTH_DISABLED_SERVICE + "-1");
        }

        if (testGrpcServer != null) {
            testGrpcServer.shutdown();
            testGrpcServer.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void shouldNotAddAuthTokenWhenDisabled() throws InterruptedException {
        // Wait for Consul registration
        Thread.sleep(500);

        // Create client and make request
        var client = factory.getClient(AUTH_DISABLED_SERVICE, MutinyGreeterGrpc::newMutinyStub)
            .await().atMost(java.time.Duration.ofSeconds(10));

        HelloRequest request = HelloRequest.newBuilder()
            .setName("No Auth Test")
            .build();

        HelloReply response = client.sayHello(request)
            .await().atMost(java.time.Duration.ofSeconds(5));

        // Verify response
        assertThat(response).isNotNull();
        assertThat(response.getMessage()).isEqualTo("Hello No Auth Test");

        // Verify NO auth header was sent
        assertThat(receivedAuthHeader.get()).isFalse();
    }

    /**
     * Server interceptor that verifies NO auth header is present.
     */
    static class NoAuthExpectedInterceptor implements ServerInterceptor {
        @Override
        public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
                ServerCall<ReqT, RespT> call,
                Metadata headers,
                ServerCallHandler<ReqT, RespT> next) {

            // Check if auth header exists
            Metadata.Key<String> authKey = Metadata.Key.of("Authorization", Metadata.ASCII_STRING_MARSHALLER);
            String authHeader = headers.get(authKey);

            if (authHeader != null && authHeader.startsWith("Bearer ")) {
                receivedAuthHeader.set(true);
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
