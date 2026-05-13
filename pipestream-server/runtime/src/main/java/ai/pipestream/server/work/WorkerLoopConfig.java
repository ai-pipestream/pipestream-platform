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
 * {@code application.properties}, along with the routing tuple
 * ({@code cluster}, {@code graph-id}, {@code node-id}, {@code module-id}).
 * Modules that don't enable the loop pay zero runtime cost beyond the
 * extension jar's bytes on disk.
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
     * The {@code Hello.cluster} value this worker sends. Engine maps
     * this to the Kafka topic
     * {@code pipestream.<cluster>.<graph_id>.<node_id>}.
     */
    @WithDefault("default")
    String cluster();

    /**
     * The {@code Hello.graph_id} value this worker sends.
     */
    String graphId();

    /**
     * The {@code Hello.node_id} value this worker sends. Identifies
     * which node in the graph this worker is fulfilling.
     */
    String nodeId();

    /**
     * The {@code Hello.module_id} value, diagnostic only. Convention:
     * the module's logical name (e.g. {@code chunker},
     * {@code embedder}).
     */
    @WithDefault("module")
    String moduleId();

    /**
     * Number of concurrent open streams to maintain. Each stream
     * processes one work unit at a time on its own virtual thread,
     * so this is also the module's concurrency cap (in work-units,
     * not docs — relevant for the embedder where one doc can be many
     * embedding tasks; that's bounded by the module's internal
     * batching, not by this number).
     *
     * <p>Sized per the calibration step in the architecture doc.
     * Embedder/semantic-graph: 5. Chunker/parser: 20. Echo (intake
     * blast absorber): 100. Default 5 is conservative; modules that
     * can handle more set their own value.
     */
    @WithDefault("5")
    int concurrency();

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
     * How long to wait after a {@code NoWorkAvailable} response
     * before opening a fresh stream. The engine's response carries a
     * suggested delay; this is the local fallback when the suggestion
     * is missing or zero.
     */
    @WithDefault("1s")
    Duration noWorkRetryAfter();
}
