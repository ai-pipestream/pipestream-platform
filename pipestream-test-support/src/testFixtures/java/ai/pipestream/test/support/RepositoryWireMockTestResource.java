package ai.pipestream.test.support;

import org.testcontainers.containers.GenericContainer;

import java.util.Map;

/**
 * WireMock resource for repository-service tests.
 */
public class RepositoryWireMockTestResource extends BaseWireMockTestResource {

    @Override
    protected void configureContainer(GenericContainer<?> container) {
        container
                // Configure accounts used by repository-service tests
                .withEnv("WIREMOCK_ACCOUNT_GETACCOUNT_DEFAULT_ID", "valid-account")
                .withEnv("WIREMOCK_ACCOUNT_GETACCOUNT_DEFAULT_NAME", "Valid Account")
                .withEnv("WIREMOCK_ACCOUNT_GETACCOUNT_DEFAULT_DESCRIPTION", "Valid account for testing")
                .withEnv("WIREMOCK_ACCOUNT_GETACCOUNT_DEFAULT_ACTIVE", "true")
                .withEnv("WIREMOCK_ACCOUNT_GETACCOUNT_NOTFOUND_ID", "nonexistent");
    }

    @Override
    protected Map<String, String> buildConfig(GenericContainer<?> container) {
        // NOTE: pipestream-wiremock-server exposes multiple endpoints:
        // - Port 8080: gRPC server exposing many services incl. AccountService (reflection-enabled)
        // - Port 50052: "Direct" streaming gRPC server (used for large streaming, and registration in some tests)
        String host = getHost();
        String standardPort = String.valueOf(getMappedPort(DEFAULT_HTTP_PORT));
        String directPort = String.valueOf(getMappedPort(DEFAULT_GRPC_PORT));
        String accountAddress = host + ":" + standardPort;

        return Map.of(
                // Legacy/Direct client config (tests may use a direct Quarkus gRPC client)
                "quarkus.grpc.clients.account-service.host", host,
                "quarkus.grpc.clients.account-service.port", standardPort,

                // Stork static discovery for dynamic-grpc
                "stork.account-service.service-discovery.type", "static",
                "stork.account-service.service-discovery.address-list", accountAddress,

                // Repo-service uses dynamic-grpc with the service name "account-manager"
                "stork.account-manager.service-discovery.type", "static",
                "stork.account-manager.service-discovery.address-list", accountAddress,

                // Registration service config - use the direct server port
                "pipestream.registration.registration-service.host", host,
                "pipestream.registration.registration-service.port", directPort
        );
    }
}
