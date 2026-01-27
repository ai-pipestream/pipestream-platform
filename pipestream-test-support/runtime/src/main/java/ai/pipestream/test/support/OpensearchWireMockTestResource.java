package ai.pipestream.test.support;

import org.testcontainers.containers.GenericContainer;

import java.util.Map;

/**
 * WireMock resource for opensearch-manager tests.
 */
public class OpensearchWireMockTestResource extends BaseWireMockTestResource {

    @Override
    protected Integer[] exposedPorts() {
        return new Integer[]{DEFAULT_GRPC_PORT};
    }

    @Override
    protected String readyLogPattern() {
        return ".*Direct Streaming gRPC Server started.*";
    }

    @Override
    protected Map<String, String> buildConfig(GenericContainer<?> container) {
        String host = getHost();
        String grpcPort = String.valueOf(getMappedPort(DEFAULT_GRPC_PORT));

        java.util.Map<String, String> config = new java.util.HashMap<>();
        // Disable real service registration
        config.put("pipestream.registration.enabled", "false");

        // Route platform-registration client to the mock
        config.put("pipestream.registration.registration-service.host", host);
        config.put("pipestream.registration.registration-service.port", grpcPort);

        // Ensure AWS S3 devservices (LocalStack) stay disabled for tests
        config.put("quarkus.s3.devservices.enabled", "false");

        return config;
    }
}
