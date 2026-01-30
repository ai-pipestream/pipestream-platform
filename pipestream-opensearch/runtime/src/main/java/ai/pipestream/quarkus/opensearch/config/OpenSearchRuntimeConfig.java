package ai.pipestream.quarkus.opensearch.config;

import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

import java.util.Optional;

/**
 * Runtime configuration for OpenSearch client.
 */
@ConfigMapping(prefix = "opensearch")
@ConfigRoot(phase = ConfigPhase.RUN_TIME)
public interface OpenSearchRuntimeConfig {

    /**
     * OpenSearch hosts in format "host:port" or "host1:port1,host2:port2" for multiple hosts.
     */
    @WithDefault("localhost:9200")
    String hosts();

    /**
     * Protocol to use for OpenSearch connections (http or https).
     */
    @WithDefault("http")
    String protocol();

    /**
     * Username for OpenSearch authentication.
     */
    Optional<String> username();

    /**
     * Password for OpenSearch authentication.
     */
    Optional<String> password();

    /**
     * Connection timeout in milliseconds.
     */
    @WithDefault("5000")
    int connectionTimeout();

    /**
     * Socket timeout in milliseconds.
     */
    @WithDefault("10000")
    int socketTimeout();

    /**
     * Maximum number of connections in the connection pool.
     */
    @WithDefault("20")
    int maxConnections();

    /**
     * Maximum connections per route.
     */
    @WithDefault("10")
    int maxConnectionsPerRoute();

    /**
     * Whether to verify SSL certificates.
     */
    @WithDefault("true")
    boolean sslVerify();

    /**
     * Whether to verify SSL hostname.
     */
    @WithDefault("true")
    boolean sslVerifyHostname();

    /**
     * Health check configuration.
     */
    HealthCheckConfig healthCheck();

    /**
     * Health check configuration interface.
     */
    interface HealthCheckConfig {
        /**
         * Whether to include OpenSearch in the health check endpoint.
         */
        @WithDefault("true")
        boolean enabled();
    }
}
