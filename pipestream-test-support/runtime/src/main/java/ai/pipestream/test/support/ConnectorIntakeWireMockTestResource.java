package ai.pipestream.test.support;

import org.testcontainers.containers.GenericContainer;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

/**
 * WireMock resource for connector-intake tests with Stork overrides.
 */
public class ConnectorIntakeWireMockTestResource extends BaseWireMockTestResource {

    // Default to true for large message support (direct gRPC port 50052 has 2GB limit vs 4MB on Jetty port 8080)
    private boolean useDirectGrpc = true;

    @Override
    public void init(Map<String, String> initArgs) {
        // Allow tests to explicitly override with false if needed, otherwise default to true
        if (initArgs != null && initArgs.containsKey("useDirectGrpc")) {
            this.useDirectGrpc = "true".equals(initArgs.get("useDirectGrpc"));
        }
    }

    @Override
    protected void configureContainer(GenericContainer<?> container) {
        container
                // Ensure account not-found scenario is deterministic for tests.
                // NOTE: pipestream-wiremock-server's MockConfig lowercases env-var keys, but some mock initializers
                // look up mixed-case keys (e.g. wiremock.account.GetAccount.notfound.id). To avoid that mismatch,
                // set a JVM system property inside the container via JAVA_TOOL_OPTIONS.
                .withEnv("JAVA_TOOL_OPTIONS", "-Dwiremock.account.GetAccount.notfound.id=nonexistent-account");
    }

    @Override
    protected Map<String, String> buildConfig(GenericContainer<?> container) {
        String host = getHost();
        String directPort = String.valueOf(getMappedPort(DEFAULT_GRPC_PORT));
        String standardPort = String.valueOf(getMappedPort(DEFAULT_HTTP_PORT));

        // Determine which port repo-service uses based on initArgs
        String repoServicePort = useDirectGrpc ? directPort : standardPort;

        // Build the address for Stork static service discovery
        String repoServiceAddress = host + ":" + repoServicePort;

        // Use standard port (8080) for connector-admin and engine (unary gRPC)
        String standardServiceAddress = host + ":" + standardPort;

        Map<String, String> config = new java.util.HashMap<>();

        // Configure Stork static service discovery for repo-service
        // This overrides the Consul-based discovery in ServiceDiscoveryManager
        config.put("stork.repository.service-discovery.type", "static");
        config.put("stork.repository.service-discovery.address-list", repoServiceAddress);

        // Configure Stork for connector-admin (unary gRPC via standard port)
        config.put("stork.connector-admin.service-discovery.type", "static");
        config.put("stork.connector-admin.service-discovery.address-list", standardServiceAddress);

        // Configure Stork for engine (unary gRPC via standard port)
        config.put("stork.pipestream-engine.service-discovery.type", "static");
        config.put("stork.pipestream-engine.service-discovery.address-list", standardServiceAddress);

        // Configure Stork for account-manager (unary gRPC via standard port)
        config.put("stork.account-manager.service-discovery.type", "static");
        config.put("stork.account-manager.service-discovery.address-list", standardServiceAddress);

        // Legacy Quarkus gRPC client config (for any direct client usage)
        config.put("quarkus.grpc.clients.repository.host", host);
        config.put("quarkus.grpc.clients.repository.port", repoServicePort);
        config.put("quarkus.grpc.clients.connector-admin.host", host);
        config.put("quarkus.grpc.clients.connector-admin.port", standardPort);
        config.put("quarkus.grpc.clients.pipestream-engine.host", host);
        config.put("quarkus.grpc.clients.pipestream-engine.port", standardPort);
        config.put("quarkus.grpc.clients.account-manager.host", host);
        config.put("quarkus.grpc.clients.account-manager.port", standardPort);

        // Point Registration Service to Direct server (it handles streaming)
        config.put("pipestream.registration.registration-service.host", host);
        config.put("pipestream.registration.registration-service.port", directPort);

        // Expose standard port in case needed for HTTP/Admin API
        config.put("wiremock.host", host);
        config.put("wiremock.port", standardPort);
        // Ensure REST client always targets WireMock intake endpoint in tests using this resource.
        config.put("quarkus.rest-client.connector-intake.url", "http://" + host + ":" + standardPort);

        // Ensure Quarkus gRPC server allows large messages (overriding defaults)
        config.put("quarkus.grpc.server.max-inbound-message-size", "2147483647");

        // Provide a default intake upload stub so tests fail only on real integration issues,
        // not because /uploads/raw has no mapping.
        registerDefaultUploadStub(host, standardPort);

        return config;
    }

    private void registerDefaultUploadStub(String host, String port) {
        String body = """
                {
                  "request": { "method": "POST", "url": "/uploads/raw" },
                  "response": {
                    "status": 200,
                    "body": "{\\"status\\":\\"accepted\\"}",
                    "headers": { "Content-Type": "application/json" }
                  }
                }
                """;
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://" + host + ":" + port + "/__admin/mappings"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        try {
            HttpResponse<String> response = HttpClient.newHttpClient()
                    .send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new IllegalStateException("Failed to register default /uploads/raw stub: HTTP " + response.statusCode());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Failed to register default /uploads/raw stub", e);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to register default /uploads/raw stub", e);
        }
    }
}
