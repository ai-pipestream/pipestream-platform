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

        return Map.of(
                // Disable real service registration
                "pipestream.registration.enabled", "false",

                // Route platform-registration client to the mock
                "pipestream.registration.registration-service.host", host,
                "pipestream.registration.registration-service.port", grpcPort
        );
    }
}
