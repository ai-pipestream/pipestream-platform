package ai.pipestream.server.work;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

import java.time.Duration;

/**
 * Configuration for the {@link ModuleWorkerLoop} client framework.
 *
 * <p>Settings live under {@code pipestream.module.worker-loop}.
 * Defaults reflect the choices reasoned about in the engine repo's
 * {@code docs/architecture/15-demand-driven-flow-control.md}; a module
 * that opts in with no override gets a production-shaped configuration.
 *
 * <p>The framework is disabled by default ({@link #enabled()} = false).
 * A module enables itself by setting
 * {@code pipestream.module.worker-loop.enabled=true} in its
 * {@code application.properties} and giving the worker a
 * {@link #moduleId() module-id} — the engine looks up the matching
 * Kafka consumer by that id and serves whatever work is buffered for
 * the module across every graph. Modules that don't enable the loop
 * pay zero runtime cost beyond the extension jar's bytes on disk.
 */
@ConfigMapping(prefix = "pipestream.module.worker-loop")
public interface WorkerLoopConfig {

    /**
     * Master switch. When false, the {@link ModuleWorkerLoop}'s
     * lifecycle observers are no-ops and no streams are opened.
     * Modules that don't process engine-served work leave this off.
     */
    @WithDefault("false")
    boolean enabled();

    /**
     * The {@code Hello.module_id} value this worker sends — the engine's
     * sole routing key. Convention: the module's logical name (e.g.
     * {@code chunker}, {@code embedder}, {@code echo}). Must match an
     * entry in the engine's {@code work-server.enabled-services} list
     * or Hello is rejected with {@code NOT_FOUND}.
     *
     * <p>The legacy {@code cluster} / {@code graph-id} / {@code node-id}
     * fields were dropped — they named a per-(cluster,graph,node) Kafka
     * topic scheme that the current engine doesn't use. Per-work-unit
     * graph / node identifiers travel on the payload's StreamMetadata.
     */
    @WithDefault("module")
    String moduleId();

    /**
     * Maximum concurrent open streams when work is available. The
     * loop starts at {@link #minConcurrency()} and adds workers after
     * each successful unit until this cap is reached; idle workers
     * above the minimum exit after {@link #noWorkRetryAfter()}.
     *
     * <p>Sized per module capacity. Embedder: ~5. Chunker/parser: ~20.
     * Echo under heavy intake: up to 100.
     */
    @WithDefault("5")
    int concurrency();

    /**
     * Workers to keep when the engine queue is empty. Typically {@code 1}:
     * a single poller opens a stream every {@link #noWorkRetryAfter()}
     * instead of holding {@link #concurrency()} idle bidi streams open.
     *
     * <p>Set equal to {@link #concurrency()} for legacy fixed-pool behavior
     * (all workers start at once).
     */
    @WithDefault("1")
    int minConcurrency();

    /**
     * How often the worker emits a {@code Heartbeat} while
     * {@link ModuleProcessor#process} is running. The engine's
     * watchdog uses {@code 2 × this} as its silence threshold, so
     * shortening this gets faster stuck-module detection at the cost
     * of marginally more wire traffic during long-running work.
     */
    @WithDefault("15s")
    Duration heartbeatInterval();

    /**
     * Initial reconnect delay after a stream error. The loop uses
     * exponential backoff capped at {@link #reconnectMaxDelay()} so
     * a flapping engine doesn't trigger a tight reconnect loop, but
     * a transient failure still recovers quickly.
     */
    @WithDefault("500ms")
    Duration reconnectInitialDelay();

    /**
     * Maximum reconnect delay. The backoff schedule doubles from
     * {@link #reconnectInitialDelay()} up to this ceiling.
     */
    @WithDefault("30s")
    Duration reconnectMaxDelay();

    /**
     * How long the sole idle poller waits after {@code NoWorkAvailable}
     * before opening the next stream. The engine's response may carry a
     * suggested {@code retry_after_ms}; this is the local fallback.
     */
    @WithDefault("3s")
    Duration noWorkRetryAfter();

    /**
     * Max wait for the first {@code WorkResponse} after {@code Hello}.
     * Must exceed the engine's server-side no-work wait (often ~5s) plus
     * RTT. Shorter values surface transport problems faster; too short
     * causes false {@link WorkStreamSession.Outcome#STREAM_ERROR}s.
     */
    @WithDefault("15s")
    Duration firstResponseTimeout();
}
