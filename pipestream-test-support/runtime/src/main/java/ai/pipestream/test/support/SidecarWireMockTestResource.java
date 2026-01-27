package ai.pipestream.test.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * WireMock resource for pipestream-engine-kafka-sidecar tests.
 */
public class SidecarWireMockTestResource extends BaseWireMockTestResource {

    private static final Logger LOG = LoggerFactory.getLogger(SidecarWireMockTestResource.class);

    @Override
    protected void configureContainer(GenericContainer<?> container) {
        LOG.info("Starting Pipestream WireMock server container...");
        container
                // Wait for the Direct Streaming gRPC Server (last to start, includes service mock init)
                .waitingFor(Wait.forLogMessage(".*Direct Streaming gRPC Server started.*", 1)
                        .withStartupTimeout(Duration.ofSeconds(60)))
                .withLogConsumer(new Slf4jLogConsumer(LOG).withPrefix("wiremock"));
    }

    @Override
    protected Map<String, String> buildConfig(GenericContainer<?> container) {
        String host = getHost();
        Integer grpcPort = getMappedPort(DEFAULT_HTTP_PORT);
        Integer directPort = getMappedPort(DEFAULT_GRPC_PORT);

        LOG.info("WireMock container started. gRPC port: {}, Direct port: {}", grpcPort, directPort);

        Map<String, String> config = new HashMap<>();

        // Override Stork service discovery - clients use stork://engine and stork://repo-service
        // The application.properties sets quarkus.grpc.clients.engine.host=stork://engine
        // We need to configure Stork to point to WireMock
        String serviceAddress = host + ":" + grpcPort;

        // Engine service - for IntakeHandoff and ProcessNode
        config.put("stork.engine.service-discovery.type", "static");
        config.put("stork.engine.service-discovery.address-list", serviceAddress);
        config.put("stork.engine.load-balancer.type", "round-robin");

        // Repository service - for document hydration (GetPipeDocByReference)
        // Note: Client uses @GrpcClient("repo-service"), so Stork service name must match exactly
        config.put("stork.repo-service.service-discovery.type", "static");
        config.put("stork.repo-service.service-discovery.address-list", serviceAddress);
        config.put("stork.repo-service.load-balancer.type", "round-robin");

        // Also set repository-service (in case it's used elsewhere)
        config.put("stork.repository-service.service-discovery.type", "static");
        config.put("stork.repository-service.service-discovery.address-list", serviceAddress);
        config.put("stork.repository-service.load-balancer.type", "round-robin");

        // Override max message size for test clients (to handle large PipeDocs)
        // This prevents MessageSizeOverflowException
        config.put("quarkus.grpc.clients.engine.max-inbound-message-size", "2147483647");
        config.put("quarkus.grpc.clients.repo-service.max-inbound-message-size", "2147483647");
        config.put("quarkus.grpc.clients.engine.flow-control-window", "52428800");
        config.put("quarkus.grpc.clients.repo-service.flow-control-window", "52428800");

        // Override host to use direct connection instead of Stork for tests
        // This bypasses Stork service discovery and connects directly to WireMock
        // The application.properties has stork://engine, but we override it in tests
        // We need to set both host and port, and ensure use-quarkus-grpc-client is true
        config.put("quarkus.grpc.clients.engine.host", host);
        config.put("quarkus.grpc.clients.engine.port", grpcPort.toString());
        config.put("quarkus.grpc.clients.engine.use-quarkus-grpc-client", "true");
        config.put("quarkus.grpc.clients.repo-service.host", host);
        config.put("quarkus.grpc.clients.repo-service.port", grpcPort.toString());
        config.put("quarkus.grpc.clients.repo-service.use-quarkus-grpc-client", "true");

        LOG.info("Configured gRPC clients: engine={}:{}, repo-service={}:{}", host, grpcPort, host, grpcPort);
        LOG.info("Configured Stork services: engine={}, repo-service={}", serviceAddress, serviceAddress);

        // Registration service config - use the direct server port
        config.put("pipestream.registration.registration-service.host", host);
        config.put("pipestream.registration.registration-service.port", directPort.toString());

        // Disable actual service registration during tests
        config.put("pipestream.registration.enabled", "false");

        LOG.info("Configured gRPC clients to connect to WireMock at {}:{}", host, grpcPort);

        return config;
    }

    @Override
    public void stop() {
        // Debug aid: keep WireMock running after a test failure so we can query the request journal:
        //   KEEP_WIREMOCK_CONTAINER=true ./gradlew test --tests "ai.pipestream.sidecar.service.EngineClientTest.testIntakeHandoffSuccess"
        // Then:
        //   curl http://localhost:<mapped-8080-port>/__admin/requests/unmatched
        //   curl http://localhost:<mapped-8080-port>/__admin/requests
        if ("true".equalsIgnoreCase(System.getenv("KEEP_WIREMOCK_CONTAINER"))) {
            LOG.info("KEEP_WIREMOCK_CONTAINER=true; leaving WireMock container running for inspection.");
            return;
        }
        LOG.info("Stopping WireMock container...");
        super.stop();
    }
}
