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

        /**
         * Maximum inbound message size in bytes.
         * Default is 2GB (Integer.MAX_VALUE) for large payload support.
         *
         * @return the maximum inbound message size in bytes
         */
        @WithDefault("2147483647")
        int maxInboundMessageSize();

        /**
         * Maximum outbound message size in bytes.
         * Default is 2GB (Integer.MAX_VALUE) for large payload support.
         *
         * @return the maximum outbound message size in bytes
         */
        @WithDefault("2147483647")
        int maxOutboundMessageSize();

        /**
         * gRPC call deadline in milliseconds.
         * Default is 15 seconds. Override per-service via application.properties.
         *
         * @return the deadline in milliseconds
         */
        @WithDefault("15000")
        long deadlineMs();

        /**
         * Total attempts {@code StorkGrpcChannel} will make for a gRPC call before
         * surfacing the error to the caller. Stork passes this to Mutiny's
         * {@code .retry().atMost(n)}, which requires {@code n &ge; 1}. Using 0 is
         * invalid — Mutiny throws {@code maxAttempts must be greater than zero}.
         * <p>
         * Default is <b>1</b> (fail-fast, no retry). Channel-level retries collide
         * with application-level retry loops: a channel retry that lands after the
         * caller's deadline has expired causes silent duplicate delivery (new RPC
         * dispatched while the original is still pending) or silent drops (retry
         * succeeds but the caller was already cancelled). Observed 2026-04-22:
         * setting this to 3 dropped 10-12/1000 docs in a 1000-doc transport test
         * with zero exceptions reaching the caller. Retries belong at the
         * application layer alongside idempotency and DLQ.
         *
         * @return total attempts per call (1 = no retry, minimum value)
         */
        @WithDefault("1")
        int storkRetries();

        /**
         * Number of HTTP/2 connections the underlying Vert.x client keeps in
         * its pool <i>per destination host</i>.
         * <p>
         * Vert.x defaults to <b>one</b> HTTP/2 connection per host. Under
         * pipeline load — 100+ concurrent {@code uploadPipeDoc} streams into
         * connector-intake or module dispatches from the engine — that one
         * connection's flow-control window becomes a serialisation point:
         * all streams share one event loop on the server side, and the 64 KB
         * default stream window is quickly exhausted by large PipeDocs,
         * leaving individual streams parked until {@code WINDOW_UPDATE} frames
         * catch up. Observed 2026-04-24: single-connection default caused
         * 1/100 intake uploads to time out at 30s, tripping the JDBC
         * crawl's no-loss circuit breaker and aborting the whole run.
         * <p>
         * Default <b>8</b> — enough parallelism that each destination is
         * backed by a real pool, small enough that a service talking to a
         * dozen downstreams doesn't open hundreds of connections.
         *
         * @return HTTP/2 connections per host
         */
        @WithDefault("8")
        int http2MaxPoolSize();

        /**
         * Max concurrent streams Vert.x will multiplex on a single HTTP/2
         * connection before it opens another from the pool (up to
         * {@link #http2MaxPoolSize()}).
         * <p>
         * Must be strictly less than the server's advertised
         * {@code max-concurrent-streams}. Otherwise Vert.x treats one
         * connection as having infinite capacity and never opens #2, #3, &hellip;
         * Paired with {@link #http2MaxPoolSize()}: with pool=8 and limit=4,
         * the second connection opens as soon as the first has 4 streams
         * in flight, up to 8 connections total.
         * <p>
         * Default <b>4</b> — intentionally low. Each new TCP connection
         * lands on a fresh event loop on the receiver, so a low multiplex
         * limit fans inbound work out across multiple server loops quickly
         * instead of piling everything onto one.
         *
         * @return stream multiplexing threshold per connection
         */
        @WithDefault("4")
        int http2MultiplexingLimit();
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
