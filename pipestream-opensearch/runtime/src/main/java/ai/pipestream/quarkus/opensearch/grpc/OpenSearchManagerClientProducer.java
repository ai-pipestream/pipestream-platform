package ai.pipestream.quarkus.opensearch.grpc;

import ai.pipestream.quarkus.opensearch.config.OpenSearchManagerClientConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Helper service for OpenSearch Manager gRPC client configuration.
 * 
 * <p>This service provides configuration and helper methods for applications
 * that want to use the opensearch-manager gRPC service. The actual gRPC stubs
 * must be created by the consuming application using the Quarkus gRPC client.
 * 
 * <h2>Usage in application code:</h2>
 * <pre>
 * // In application.properties:
 * opensearch.manager.enabled=true
 * quarkus.grpc-client.opensearch-manager.host=localhost
 * quarkus.grpc-client.opensearch-manager.port=9090
 * 
 * // In your service class:
 * &#64;Inject
 * &#64;GrpcClient("opensearch-manager")
 * OpenSearchManagerServiceGrpc.OpenSearchManagerServiceBlockingStub blockingStub;
 * 
 * // Or for Mutiny:
 * &#64;Inject
 * &#64;GrpcClient("opensearch-manager")
 * MutinyOpenSearchManagerServiceGrpc.MutinyOpenSearchManagerServiceStub mutinyStub;
 * </pre>
 * 
 * <p>Note: The proto-generated stub classes must be on the application's classpath.
 */
@ApplicationScoped
public class OpenSearchManagerClientProducer {

    private static final Logger LOG = Logger.getLogger(OpenSearchManagerClientProducer.class);

    @Inject
    OpenSearchManagerClientConfig config;

    /**
     * Checks if the opensearch-manager client is enabled.
     * 
     * @return true if opensearch.manager.enabled=true
     */
    public boolean isClientEnabled() {
        return config.enabled();
    }

    /**
     * Gets the configured client name for the opensearch-manager gRPC service.
     * This corresponds to the quarkus.grpc-client.{name}.* configuration.
     * 
     * @return the client name (default: "opensearch-manager")
     */
    public String getClientName() {
        return config.clientName();
    }

    /**
     * Gets the configured host for the opensearch-manager service.
     * 
     * @return the host, or empty if not explicitly configured
     */
    public String getHost() {
        return config.host().orElse(null);
    }

    /**
     * Gets the configured port for the opensearch-manager service.
     * 
     * @return the port, or null if not explicitly configured
     */
    public Integer getPort() {
        return config.port().orElse(null);
    }

    /**
     * Whether to use plaintext (non-TLS) for the gRPC connection.
     * 
     * @return true if plaintext should be used
     */
    public boolean isPlaintext() {
        return config.plaintext();
    }

    /**
     * Validates that the client is properly configured and enabled.
     * 
     * @throws IllegalStateException if the client is not enabled
     */
    public void validateEnabled() {
        if (!config.enabled()) {
            throw new IllegalStateException(
                    "OpenSearch Manager gRPC client is not enabled. " +
                    "Set opensearch.manager.enabled=true and configure " +
                    "quarkus.grpc-client." + config.clientName() + ".* properties.");
        }
    }
}
