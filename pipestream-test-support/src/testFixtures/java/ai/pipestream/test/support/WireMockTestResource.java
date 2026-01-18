package ai.pipestream.test.support;

import org.testcontainers.containers.GenericContainer;

import java.util.Map;

public class WireMockTestResource extends BaseWireMockTestResource {

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
        String port = String.valueOf(getMappedPort(DEFAULT_GRPC_PORT));
        return Map.of(
                "pipestream.registration.registration-service.host", host,
                "pipestream.registration.registration-service.port", port
        );
    }
}
