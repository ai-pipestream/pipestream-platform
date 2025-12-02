package ai.pipestream.registration.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;

import java.time.Duration;
import java.util.Optional;

/**
 * Configuration for the Pipestream service registration extension.
 * 
 * <p>All settings have sensible defaults for zero-configuration usage.
 */
@ConfigMapping(prefix = "pipestream.registration")
public interface RegistrationConfig {

    /**
     * Whether the registration extension is enabled.
     */
    @WithDefault("true")
    boolean enabled();

    /**
     * The name of the service to register.
     * Defaults to the value of quarkus.application.name if not specified.
     */
    @WithName("service-name")
    Optional<String> serviceName();

    /**
     * The version of the service.
     * Defaults to the value of quarkus.application.version if not specified.
     */
    Optional<String> version();

    /**
     * The host address for this service.
     * Defaults to 0.0.0.0 if not specified.
     */
    @WithDefault("0.0.0.0")
    String host();

    /**
     * The port for this service.
     * Defaults to the Quarkus HTTP port.
     */
    @WithDefault("8080")
    int port();

    /**
     * Configuration for the registration service connection.
     */
    @WithName("registration-service")
    RegistrationServiceConfig registrationService();

    /**
     * Configuration for retry behavior.
     */
    RetryConfig retry();

    /**
     * Registration service connection configuration.
     */
    interface RegistrationServiceConfig {
        /**
         * Host of the platform-registration-service.
         */
        @WithDefault("localhost")
        String host();

        /**
         * Port of the platform-registration-service.
         */
        @WithDefault("9090")
        int port();

        /**
         * Connection timeout.
         */
        @WithDefault("10s")
        Duration timeout();
    }

    /**
     * Retry configuration.
     */
    interface RetryConfig {
        /**
         * Maximum number of registration retry attempts.
         */
        @WithDefault("5")
        int maxAttempts();

        /**
         * Initial delay before first retry.
         */
        @WithDefault("1s")
        Duration initialDelay();

        /**
         * Maximum delay between retries.
         */
        @WithDefault("30s")
        Duration maxDelay();

        /**
         * Multiplier for exponential backoff.
         */
        @WithDefault("2.0")
        double multiplier();
    }
}
