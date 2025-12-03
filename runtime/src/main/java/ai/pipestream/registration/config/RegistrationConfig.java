package ai.pipestream.registration.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;

import java.time.Duration;
import java.util.List;
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
     * The type of service being registered (SERVICE or MODULE).
     * Defaults to SERVICE.
     */
    @WithDefault("SERVICE")
    String type();

    /**
     * The version of the service.
     * Defaults to the value of quarkus.application.version if not specified.
     */
    Optional<String> version();

    /**
     * The advertised host address (client-facing address).
     * This is the address clients should use to connect to this service.
     * Defaults to 0.0.0.0 if not specified.
     */
    @WithName("advertised-host")
    @WithDefault("0.0.0.0")
    String advertisedHost();

    /**
     * The advertised port (client-facing port).
     * Defaults to the Quarkus gRPC server port.
     */
    @WithName("advertised-port")
    Optional<Integer> advertisedPort();

    /**
     * The internal host address (actual bind address).
     * Used for Docker/K8s scenarios where the service binds to a different address
     * than what clients use. If not specified, the advertised host is used.
     */
    @WithName("internal-host")
    Optional<String> internalHost();

    /**
     * The internal port (actual bind port).
     * Used for port mapping scenarios. If not specified, the advertised port is used.
     */
    @WithName("internal-port")
    Optional<Integer> internalPort();

    /**
     * Whether TLS is enabled for gRPC connections.
     */
    @WithName("tls-enabled")
    @WithDefault("false")
    boolean tlsEnabled();

    /**
     * Tags for service discovery and filtering.
     */
    Optional<List<String>> tags();

    /**
     * Capabilities advertised by this service (primarily for modules).
     */
    Optional<List<String>> capabilities();

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
