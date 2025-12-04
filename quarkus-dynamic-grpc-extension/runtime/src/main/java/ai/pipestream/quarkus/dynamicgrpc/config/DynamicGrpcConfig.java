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
     */
    ChannelConfig channel();

    /**
     * TLS configuration for all dynamic gRPC clients.
     */
    TlsConfig tls();

    /**
     * Authentication configuration for all dynamic gRPC clients.
     */
    AuthConfig auth();

    /**
     * Consul configuration for service discovery.
     */
    ConsulConfig consul();

    interface ChannelConfig {
        /**
         * Idle time-to-live for cached channels in minutes.
         */
        @WithDefault("15")
        long idleTtlMinutes();

        /**
         * Maximum number of cached channels.
         */
        @WithDefault("1000")
        long maxSize();

        /**
         * Shutdown timeout in seconds.
         */
        @WithDefault("2")
        long shutdownTimeoutSeconds();
    }

    interface TlsConfig {
        /**
         * Whether TLS is enabled for dynamic gRPC clients.
         */
        @WithDefault("false")
        boolean enabled();

        /**
         * Enable trusting all certificates. Disabled by default.
         * WARNING: Only use in development!
         */
        @WithDefault("false")
        boolean trustAll();

        /**
         * Trust certificate configuration in PEM format.
         */
        PemTrustCertConfig trustCertificatePem();

        /**
         * Key/cert configuration in PEM format for mTLS.
         */
        PemKeyCertConfig keyCertificatePem();

        /**
         * Whether hostname should be verified in the SSL/TLS handshake.
         */
        @WithDefault("true")
        boolean verifyHostname();
    }

    interface PemTrustCertConfig {
        /**
         * List of trust certificate files (PEM format).
         */
        Optional<List<String>> certs();
    }

    interface PemKeyCertConfig {
        /**
         * List of key files (PEM format).
         */
        Optional<List<String>> keys();

        /**
         * List of certificate files (PEM format).
         */
        Optional<List<String>> certs();
    }

    interface AuthConfig {
        /**
         * Whether authentication is enabled for dynamic gRPC clients.
         */
        @WithDefault("false")
        boolean enabled();

        /**
         * The header name to use for the authentication token.
         */
        @WithDefault("Authorization")
        String headerName();

        /**
         * The scheme prefix to prepend to the token value.
         * For example, "Bearer " will result in "Authorization: Bearer {token}"
         */
        @WithDefault("Bearer ")
        String schemePrefix();
    }

    interface ConsulConfig {
        /**
         * Consul host for service discovery.
         */
        @WithDefault("localhost")
        String host();

        /**
         * Consul port for service discovery.
         */
        @WithDefault("8500")
        String port();

        /**
         * Refresh period for Stork service discovery.
         */
        @WithDefault("10s")
        String refreshPeriod();

        /**
         * Whether to use Consul health checks.
         */
        @WithDefault("false")
        boolean useHealthChecks();
    }
}
