package ai.pipestream.quarkus.dynamicgrpc.config;

import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

import java.util.List;
import java.util.Map;
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
         * HTTP/2 connections per remote host in the underlying Vert.x client.
         * <p>
         * Vert.x defaults to <b>one</b> HTTP/2 connection per host, so every gRPC
         * stream multiplexes on a single TCP connection. That connection's
         * event loop on the server side serialises all inbound framing work —
         * under load we observed two server-side event loops pegged (one per
         * caller connection) while the other 30+ loops sat idle.
         * <p>
         * Setting this &gt; 1 opens that many parallel HTTP/2 connections.
         * Each connection is pinned to its own event loop on the receiver, so
         * N connections fan out across N event loops. Concurrent gRPC calls
         * get round-robined across the connections by Vert.x.
         *
         * @return number of HTTP/2 connections to open per destination host
         */
        @WithDefault("8")
        int http2MaxPoolSize();

        /**
         * Multiplexing limit — the max concurrent streams Vert.x will multiplex
         * onto a single HTTP/2 connection before it opens a new connection
         * from the pool.
         * <p>
         * Must be strictly less than the server's advertised max-concurrent-streams
         * (we set 2000), otherwise Vert.x treats one connection as infinite
         * capacity and <b>never</b> opens connection #2 in the pool. Pair with
         * {@link #http2MaxPoolSize()}: with pool=8 and limit=4, the client opens
         * connection #2 as soon as #1 has 4 concurrent streams, up to 8
         * connections total.
         * <p>
         * The low limit is deliberate: each new connection lands on a fresh
         * event loop on the receiving service, so a low multiplexing limit
         * fans inbound work out fast. Was 64 — under real pipeline load we
         * never got close, so the pool sat idle.
         *
         * @return max concurrent streams per HTTP/2 connection before pool grows
         */
        @WithDefault("4")
        int http2MultiplexingLimit();

        /**
         * Default number of independent gRPC {@code Channel}s the manager
         * holds per service. Requests are round-robined across these channels.
         * Each channel has its own {@code GrpcClient} / HTTP client /
         * connection pool — so effectively fans inbound work out across that
         * many event loops on the receiving service.
         * <p>
         * Override per target service via
         * {@code quarkus.dynamic-grpc.channel.per-service.<serviceName>=N}.
         * For example, a hot-path embedder might be sized at 3, while a
         * less-used account-manager stays at the default.
         *
         * @return number of parallel channels per service cache entry
         */
        @WithDefault("4")
        int channelsPerService();

        /**
         * Per-service overrides for {@link #channelsPerService()}. Key is the
         * Stork/Consul service name (e.g. {@code embedder}, {@code engine}),
         * value is the desired channel count for that specific service.
         * <p>
         * Example configuration:
         * <pre>
         * quarkus.dynamic-grpc.channel.per-service.embedder=3
         * quarkus.dynamic-grpc.channel.per-service.engine=8
         * </pre>
         */
        Map<String, Integer> perService();

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
         * {@code .retry().atMost(n)}, which requires {@code n &ge; 1}.
         * <p>
         * Semantics: <b>1 = one call, no retries</b> (fail-fast). <b>2 = one retry
         * allowed</b>. Using 0 is invalid — Mutiny throws
         * {@code maxAttempts must be greater than zero}.
         * <p>
         * Default is <b>1</b>. Channel-level retries are hazardous in a round-robin
         * pool: with {@link #channelsPerService()} &ge; 2, each delegate has its
         * own retry budget, and a retry that collides with the {@link #deadlineMs()}
         * window can cause silent duplicate delivery (retry lands as a fresh RPC
         * while the original completes) or silent drops (retry succeeds but the
         * original's response was already cancelled). Both failure modes bypass
         * the caller's {@code catch} because Stork handles them internally.
         * <p>
         * Retries belong at the application layer where they can be paired with
         * proper idempotency, DLQ, and metrics — not buried inside the channel
         * pool. Observed 2026-04-22: setting this to 3 (the prior hardcoded
         * default) caused a 1000-doc ECHO→ECHO→SIDECAR transport test to drop
         * 10-12 docs/1000 with <b>zero</b> exceptions reaching the caller.
         * Default 1 restores fail-fast semantics.
         *
         * @return total attempts per call (1 = no retry, minimum value)
         */
        @WithDefault("1")
        int storkRetries();
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
