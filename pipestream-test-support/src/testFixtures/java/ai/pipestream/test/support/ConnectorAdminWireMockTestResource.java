package ai.pipestream.test.support;

import org.testcontainers.containers.GenericContainer;

import java.util.Map;

/**
 * WireMock resource for connector-admin tests (account-manager + test account variants).
 */
public class ConnectorAdminWireMockTestResource extends BaseWireMockTestResource {

    private int grpcPort;

    @Override
    protected Integer[] exposedPorts() {
        grpcPort = Integer.parseInt(System.getProperty("wiremock.grpc.port", String.valueOf(DEFAULT_GRPC_PORT)));
        return new Integer[]{DEFAULT_HTTP_PORT, grpcPort};
    }

    @Override
    protected String readyLogPattern() {
        return ".*Direct Streaming gRPC Server started.*";
    }

    @Override
    protected void configureContainer(GenericContainer<?> container) {
        container
                // Configure additional test accounts via environment variables
                .withEnv("WIREMOCK_ACCOUNT_GETACCOUNT_DEFAULT_ID", "valid-account")
                .withEnv("WIREMOCK_ACCOUNT_GETACCOUNT_DEFAULT_NAME", "Valid Account")
                .withEnv("WIREMOCK_ACCOUNT_GETACCOUNT_DEFAULT_DESCRIPTION", "Valid account for testing")
                .withEnv("WIREMOCK_ACCOUNT_GETACCOUNT_DEFAULT_ACTIVE", "true")
                // Configure inactive account
                .withEnv("WIREMOCK_ACCOUNT_GETACCOUNT_INACTIVE_ID", "inactive-account")
                .withEnv("WIREMOCK_ACCOUNT_GETACCOUNT_INACTIVE_NAME", "Inactive Account")
                .withEnv("WIREMOCK_ACCOUNT_GETACCOUNT_INACTIVE_DESCRIPTION", "Inactive account for testing")
                .withEnv("WIREMOCK_ACCOUNT_GETACCOUNT_INACTIVE_ACTIVE", "false")
                // Configure not found account
                .withEnv("WIREMOCK_ACCOUNT_GETACCOUNT_NOTFOUND_ID", "nonexistent");
    }

    @Override
    protected Map<String, String> buildConfig(GenericContainer<?> container) {
        String host = getHost();
        String port = String.valueOf(getMappedPort(grpcPort));

        return Map.of(
                "stork.account-manager.service-discovery.type", "static",
                "stork.account-manager.service-discovery.address-list", host + ":" + port,
                // Expose WireMock connection info for tests
                "wiremock.host", host,
                "wiremock.port", port
        );
    }
}
