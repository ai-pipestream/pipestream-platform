package ai.pipestream.quarkus.devservices.runtime;

import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

import java.util.Optional;

/**
 * Runtime configuration for Infisical integration in dev/test modes.
 * This configuration is only active in dev and test profiles.
 */
@ConfigRoot(phase = ConfigPhase.RUN_TIME)
@ConfigMapping(prefix = "quarkus.pipeline-devservices.infisical")
public interface InfisicalConfig {

    /**
     * Whether to automatically set up the Infisical admin account on first startup.
     * Only applies in dev and test modes.
     */
    @WithDefault("false")
    Optional<Boolean> adminAutoSetup();

    /**
     * Email address for the admin account to create.
     */
    @WithDefault("admin@pipestream.local")
    Optional<String> adminEmail();

    /**
     * Password for the admin account to create.
     */
    @WithDefault("admin-password-change-me")
    Optional<String> adminPassword();

    /**
     * Infisical API URL (internal Docker network).
     * Defaults to the service name in compose.
     */
    @WithDefault("http://infisical:8080")
    Optional<String> apiUrl();

    /**
     * Maximum number of retry attempts when checking Infisical health.
     */
    @WithDefault("30")
    Optional<Integer> healthCheckRetries();

    /**
     * Delay between health check retries in seconds.
     */
    @WithDefault("2")
    Optional<Integer> healthCheckDelaySeconds();
}

