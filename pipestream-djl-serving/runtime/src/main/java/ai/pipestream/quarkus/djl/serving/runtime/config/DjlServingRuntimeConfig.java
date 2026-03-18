package ai.pipestream.quarkus.djl.serving.runtime.config;

import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

@ConfigMapping(prefix = "pipestream.djl-serving")
@ConfigRoot(phase = ConfigPhase.RUN_TIME)
public interface DjlServingRuntimeConfig {
    enum DiscoveryMode {
        DIRECT_URL,
        CONSUL_HTTP
    }

    /**
     * The DJL Serving URL.
     */
    @WithDefault("http://localhost:8080")
    String url();

    /**
     * Whether the DJL Serving extension is enabled.
     */
    @WithDefault("true")
    boolean enabled();

    /**
     * Discovery mode for resolving DJL Serving management endpoints.
     */
    @WithDefault("DIRECT_URL")
    DiscoveryMode discoveryMode();

    /**
     * Optional additional direct URLs (for multi-instance / no-LB setups).
     */
    Optional<List<String>> directUrls();

    /**
     * Per-request timeout for DJL/Consul HTTP calls.
     */
    @WithDefault("10s")
    Duration requestTimeout();

    /**
     * Refresh interval for polling model inventory/health.
     */
    @WithDefault("30s")
    Duration refreshInterval();

    /**
     * Consul-based endpoint discovery configuration.
     */
    ConsulConfig consul();

    interface ConsulConfig {
        @WithDefault("http")
        String scheme();

        @WithDefault("localhost")
        String host();

        @WithDefault("8500")
        int port();

        @WithDefault("djl-serving")
        String serviceName();

        Optional<String> tag();

        Optional<String> datacenter();

        @WithDefault("true")
        boolean passingOnly();
    }
}
