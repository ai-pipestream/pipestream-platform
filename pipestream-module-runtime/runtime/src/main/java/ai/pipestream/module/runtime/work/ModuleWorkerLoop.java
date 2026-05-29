package ai.pipestream.module.runtime.work;

import com.google.protobuf.Message;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Vetoed;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Module-side demand-pull worker. Maintains between
 * {@link WorkerLoopConfig#minConcurrency()} and
 * {@link WorkerLoopConfig#concurrency()} concurrent virtual threads.
 *
 * <p><b>Idle:</b> only the minimum workers run. Each opens a bidi
 * stream, receives {@code NoWorkAvailable}, sleeps
 * {@link WorkerLoopConfig#noWorkRetryAfter()} (typically 2–3s), and
 * retries — instead of holding the maximum concurrency open on an empty
 * queue.
 *
 * <p><b>Under load:</b> after each successful unit (or module-reported
 * failure the engine accepted), the loop tries to spawn one more worker
 * until the maximum is reached. Workers that see {@code NoWorkAvailable}
 * while above the minimum exit so the pool ramps down when the backlog
 * drains.
 *
 * <p>Set {@code min-concurrency == concurrency} for legacy fixed-pool
 * behavior (all workers start at once).
 *
 * <p>{@code @Vetoed} so CDI never auto-registers this generic class as a
 * bean — the {@code @Observes} lifecycle methods are invoked manually by
 * the module's {@code @Produces}/{@code @Inject} wrapper, which supplies
 * the concrete {@code T}, processor, and engine client at construction.
 */
@Vetoed
public final class ModuleWorkerLoop<T extends Message> {

    private static final Logger LOG = Logger.getLogger(ModuleWorkerLoop.class);

    private final ModuleWorkEngineClient engineClient;
    private final ModuleProcessor<T> processor;
    private final PayloadCodec<T> codec;
    private final WorkerLoopConfig config;
    private final int minWorkers;
    private final int maxWorkers;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicInteger activeWorkers = new AtomicInteger(0);
    private final AtomicInteger streamErrors = new AtomicInteger();
    private final AtomicInteger sessionsCompleted = new AtomicInteger();

    public ModuleWorkerLoop(Class<T> messageClass,
                            ModuleProcessor<T> processor,
                            ModuleWorkEngineClient engineClient,
                            WorkerLoopConfig config) {
        this.processor = Objects.requireNonNull(processor, "processor");
        this.codec = new PayloadCodec<>(Objects.requireNonNull(messageClass, "messageClass"));
        this.engineClient = Objects.requireNonNull(engineClient, "engineClient");
        this.config = Objects.requireNonNull(config, "config");
        int max = Math.max(1, config.concurrency());
        int min = Math.max(1, Math.min(config.minConcurrency(), max));
        this.minWorkers = min;
        this.maxWorkers = max;
    }

    public void onStart(@Observes StartupEvent event) {
        if (!config.enabled()) {
            LOG.infof("ModuleWorkerLoop disabled (pipestream.module.worker-loop.enabled=false)");
            return;
        }
        running.set(true);
        String namePrefix = "worker-" + config.moduleId() + "-";
        for (int i = 0; i < minWorkers; i++) {
            startWorker(namePrefix);
        }
        LOG.infof("ModuleWorkerLoop started: module=%s workers=%d..%d "
                        + "idlePoll=%s heartbeat=%s payloadType=%s",
                config.moduleId(), minWorkers, maxWorkers,
                config.noWorkRetryAfter(), config.heartbeatInterval(),
                codec.messageClass().getSimpleName());
    }

    public void onStop(@Observes ShutdownEvent event) {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (activeWorkers.get() > 0 && System.nanoTime() < deadline) {
            sleepInterruptibly(Duration.ofMillis(50));
        }
        LOG.infof("ModuleWorkerLoop stopped: sessions=%d stream-errors=%d activeWorkers=%d",
                sessionsCompleted.get(), streamErrors.get(), activeWorkers.get());
    }

    private void startWorker(String namePrefix) {
        int slot = activeWorkers.incrementAndGet();
        if (slot > maxWorkers) {
            activeWorkers.decrementAndGet();
            return;
        }
        Thread.ofVirtual()
                .name(namePrefix + slot)
                .start(() -> {
                    boolean alreadyDecremented = false;
                    try {
                        alreadyDecremented = runWorker();
                    } finally {
                        if (!alreadyDecremented) {
                            activeWorkers.decrementAndGet();
                        }
                    }
                });
    }

    private void tryRampUp() {
        if (activeWorkers.get() >= maxWorkers) {
            return;
        }
        String namePrefix = "worker-" + config.moduleId() + "-";
        startWorker(namePrefix);
    }

    /**
     * Worker body. Returns {@code true} if this worker atomically
     * decremented {@link #activeWorkers} as part of a voluntary
     * ramp-down (so the caller must skip the finally-block decrement
     * to avoid double-counting). Returns {@code false} on shutdown or
     * any non-ramp-down exit, in which case the caller's finally-block
     * decrement is the single accounting point.
     */
    private boolean runWorker() {
        BackoffSchedule backoff = new BackoffSchedule(
                config.reconnectInitialDelay(), config.reconnectMaxDelay());
        while (running.get()) {
            WorkStreamSession<T> session = new WorkStreamSession<>(
                    engineClient.stub(), processor, codec, config);
            WorkStreamSession.Outcome outcome = session.run();
            Duration suggestedNoWorkRetry = session.suggestedNoWorkRetry();
            sessionsCompleted.incrementAndGet();
            switch (outcome) {
                case SUCCESS, FAILED_BY_MODULE -> {
                    backoff.reset();
                    tryRampUp();
                }
                case NO_WORK_AVAILABLE -> {
                    backoff.reset();
                    // Atomic check-and-decrement: only this worker
                    // exits if doing so still leaves >= minWorkers
                    // alive. Plain `get() > min` + finally-decrement
                    // races when many workers see NO_WORK at once —
                    // all read the same value, all decide to exit,
                    // all decrement, leaving zero workers and a dead
                    // module until restart.
                    int prev = activeWorkers.getAndUpdate(
                            n -> n > minWorkers ? n - 1 : n);
                    if (prev > minWorkers) {
                        return true;
                    }
                    Duration wait = suggestedNoWorkRetry;
                    if (wait.isZero() || wait.isNegative()) {
                        wait = config.noWorkRetryAfter();
                    }
                    sleepInterruptibly(wait);
                }
                case STREAM_ERROR -> {
                    streamErrors.incrementAndGet();
                    // A stream error is CALL-scoped: this one bidi Work
                    // stream aborted (engine watchdog close, a slow/missed
                    // first response, a transient transport blip). It does
                    // NOT mean the shared channel is bad. We deliberately do
                    // NOT tear the channel down here.
                    //
                    // The channel is an @ApplicationScoped singleton reused by
                    // every worker virtual thread (HTTP/2 multiplexing). A
                    // ManagedChannel.shutdownNow() cancels ALL in-flight calls
                    // on it — so one worker's stream error would cancel every
                    // sibling's in-flight Work stream, each of which then
                    // reports STREAM_ERROR and reconnects in turn: a
                    // self-amplifying cancellation storm. Under load that storm
                    // re-served the same work unit fast enough to exhaust the
                    // engine's per-record redelivery cap, quarantining a
                    // perfectly good document (observed: 999/1000 at a terminal
                    // node). See SharedModuleWorkEngineClient.
                    //
                    // gRPC's ManagedChannel already re-resolves and reconnects
                    // the transport on its own with backoff, and
                    // SharedModuleWorkEngineClient.channel() rebuilds a
                    // terminated channel lazily on the next stub() call, so a
                    // genuinely-dead channel still recovers without us forcing
                    // it. Here we just back off and open a fresh stream on the
                    // same channel.
                    Duration wait = backoff.next();
                    LOG.warnf("Stream error; backing off %s before retry "
                            + "(total stream-errors=%d)", wait, streamErrors.get());
                    sleepInterruptibly(wait);
                }
            }
        }
        return false;
    }

    private void sleepInterruptibly(Duration d) {
        if (d.isZero() || d.isNegative()) {
            return;
        }
        try {
            Thread.sleep(d.toMillis());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    /** Live counters for health endpoints and ops dashboards. */
    public record Snapshot(
            boolean running,
            int activeWorkers,
            int minWorkers,
            int maxWorkers,
            int sessionsCompleted,
            int streamErrors) {}

    public Snapshot snapshot() {
        return new Snapshot(
                running.get(),
                activeWorkers.get(),
                minWorkers,
                maxWorkers,
                sessionsCompleted.get(),
                streamErrors.get());
    }
}
