package ai.pipestream.quarkus.dynamicgrpc.config;

import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

import java.util.List;
import java.util.Optional;

/**
 * Configuration for the Dynamic gRPC extension.
 */
@ConfigMapping(prefix = "quarkus.dynamic-grpc")
@ConfigRoot(phase = ConfigPhase.RUN_TIME)
public interface DynamicGrpcConfig {

    /**
     * Channel configuration.
     *
     * @return the channel configuration
     */
    ChannelConfig channel();

    /**
     * TLS configuration for all dynamic gRPC clients.
     *
     * @return the TLS configuration
     */
    TlsConfig tls();

    /**
     * Authentication configuration for all dynamic gRPC clients.
     *
     * @return the authentication configuration
     */
    AuthConfig auth();

    /**
     * Consul configuration for service discovery.
     *
     * @return the Consul configuration
     */
    ConsulConfig consul();

    /**
     * Channel cache and lifecycle configuration.
     */
    interface ChannelConfig {
        /**
         * Idle time-to-live for cached channels in minutes.
         *
         * @return the idle TTL in minutes
         */
        @WithDefault("15")
        long idleTtlMinutes();

        /**
         * Maximum number of cached channels.
         *
         * @return the maximum cache size
         */
        @WithDefault("1000")
        long maxSize();

        /**
         * Shutdown timeout in seconds.
         *
         * @return the shutdown timeout in seconds
         */
        @WithDefault("2")
        long shutdownTimeoutSeconds();
    }

    /**
     * Transport Layer Security (TLS) configuration.
     */
    interface TlsConfig {
        /**
         * Whether TLS is enabled for dynamic gRPC clients.
         *
         * @return true if TLS is enabled
         */
        @WithDefault("false")
        boolean enabled();

        /**
         * Enable trusting all certificates. Disabled by default.
         * WARNING: Only use in development!
         *
         * @return true to trust all certificates
         */
        @WithDefault("false")
        boolean trustAll();

        /**
         * Trust certificate configuration in PEM format.
         *
         * @return the trust certificate PEM configuration
         */
        PemTrustCertConfig trustCertificatePem();

        /**
         * Key/cert configuration in PEM format for mTLS.
         *
         * @return the key/cert PEM configuration
         */
        PemKeyCertConfig keyCertificatePem();

        /**
         * Whether hostname should be verified in the SSL/TLS handshake.
         *
         * @return true if hostname verification is enabled
         */
        @WithDefault("true")
        boolean verifyHostname();
    }

    /**
     * Trust certificate configuration (PEM files).
     */
    interface PemTrustCertConfig {
        /**
         * List of trust certificate files (PEM format).
         *
         * @return optional list of PEM certificate file paths
         */
        Optional<List<String>> certs();
    }

    /**
     * mTLS key and certificate configuration (PEM files).
     */
    interface PemKeyCertConfig {
        /**
         * List of key files (PEM format).
         *
         * @return optional list of PEM key file paths
         */
        Optional<List<String>> keys();

        /**
         * List of certificate files (PEM format).
         *
         * @return optional list of PEM certificate file paths
         */
        Optional<List<String>> certs();
    }

    /**
     * Authentication token propagation configuration.
     */
    interface AuthConfig {
        /**
         * Whether authentication is enabled for dynamic gRPC clients.
         *
         * @return true if auth is enabled
         */
        @WithDefault("false")
        boolean enabled();

        /**
         * The header name to use for the authentication token.
         *
         * @return the HTTP/2 header name (e.g., "Authorization")
         */
        @WithDefault("Authorization")
        String headerName();

        /**
         * The scheme prefix to prepend to the token value.
         * For example, "Bearer " will result in "Authorization: Bearer {token}"
         *
         * @return the scheme prefix to prepend (e.g., "Bearer ")
         */
        @WithDefault("Bearer ")
        String schemePrefix();
    }

    /**
     * Consul configuration parameters for Stork-based service discovery.
     */
    interface ConsulConfig {
        /**
         * Consul host for service discovery.
         *
         * @return the Consul host
         */
        @WithDefault("localhost")
        String host();

        /**
         * Consul port for service discovery.
         *
         * @return the Consul port as a string
         */
        @WithDefault("8500")
        String port();

        /**
         * Refresh period for Stork service discovery.
         *
         * @return the refresh period duration string (e.g., "10s")
         */
        @WithDefault("10s")
        String refreshPeriod();

        /**
         * Whether to use Consul health checks.
         *
         * @return true if Consul health checks should be used
         */
        @WithDefault("false")
        boolean useHealthChecks();
    }
}
