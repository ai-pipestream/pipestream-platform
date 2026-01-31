package ai.pipestream.quarkus.opensearch.config;

import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

import java.util.Optional;

/**
 * Build-time configuration for OpenSearch DevServices.
 */
@ConfigMapping(prefix = "pipestream.opensearch")
@ConfigRoot(phase = ConfigPhase.BUILD_TIME)
public interface OpenSearchBuildTimeConfig {

    /**
     * DevServices configuration.
     */
    DevServicesConfig devservices();

    /**
     * Whether to enable the health check integration.
     */
    @WithDefault("true")
    boolean healthEnabled();

    /**
     * DevServices configuration interface.
     */
    interface DevServicesConfig {
        /**
         * Whether DevServices for OpenSearch is enabled.
         * DevServices will only start a container if:
         * - This is enabled (default: true)
         * - Docker is available
         * - opensearch.hosts is not already configured
         */
        @WithDefault("true")
        boolean enabled();

        /**
         * The OpenSearch container image to use.
         */
        @WithDefault("opensearchproject/opensearch:3.5.0")
        String imageName();

        /**
         * Optional fixed port for the OpenSearch container.
         * If not specified, a random port will be used.
         */
        Optional<Integer> port();

        /**
         * JVM options for the OpenSearch container.
         */
        @WithDefault("-Xms512m -Xmx512m")
        String javaOpts();

        /**
         * Whether the container should be shared across multiple Quarkus applications.
         * When shared, containers are identified by their service name and can be reused.
         */
        @WithDefault("true")
        boolean shared();

        /**
         * Service name used for container sharing and identification.
         */
        @WithDefault("opensearch")
        String serviceName();
    }
}
