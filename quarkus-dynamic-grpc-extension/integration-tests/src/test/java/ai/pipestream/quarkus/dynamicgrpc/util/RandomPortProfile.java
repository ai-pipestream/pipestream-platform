package ai.pipestream.quarkus.dynamicgrpc.util;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Map;

/**
 * Test profile that configures Quarkus gRPC server to use a random available port.
 * Useful for parallel test execution to avoid port conflicts.
 * <p>
 * Migrated from platform-libraries/libraries/dynamic-grpc test suite.
 * </p>
 */
public class RandomPortProfile implements QuarkusTestProfile {
    /**
     * Provides configuration overrides to instruct Quarkus to bind the gRPC server
     * to port {@code 0} (let the OS choose an available random port).
     *
     * @return a singleton map with the {@code quarkus.grpc.server.port} override
     */
    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of("quarkus.grpc.server.port", "0");
    }
}
