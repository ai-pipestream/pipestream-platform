package ai.pipestream.quarkus.opensearch.config;

import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

import java.util.Optional;

/**
 * Configuration for the OpenSearch Manager gRPC client.
 * 
 * <p>This configures the connection to the opensearch-manager gRPC service,
 * which handles index lifecycle, schema management, and document operations.
 */
@ConfigMapping(prefix = "opensearch.manager")
@ConfigRoot(phase = ConfigPhase.RUN_TIME)
public interface OpenSearchManagerClientConfig {

    /**
     * Whether the opensearch-manager gRPC client is enabled.
     * When enabled, the extension will provide CDI beans for the gRPC stubs.
     */
    @WithDefault("false")
    boolean enabled();

    /**
     * The gRPC client name to use for the opensearch-manager service.
     * This corresponds to the quarkus.grpc-client.{name}.* configuration.
     */
    @WithDefault("opensearch-manager")
    String clientName();

    /**
     * Optional host override for the opensearch-manager service.
     * If not set, uses the configured gRPC client settings.
     */
    Optional<String> host();

    /**
     * Optional port override for the opensearch-manager service.
     * If not set, uses the configured gRPC client settings.
     */
    Optional<Integer> port();

    /**
     * Whether to use plaintext (non-TLS) for the gRPC connection.
     */
    @WithDefault("true")
    boolean plaintext();
}
