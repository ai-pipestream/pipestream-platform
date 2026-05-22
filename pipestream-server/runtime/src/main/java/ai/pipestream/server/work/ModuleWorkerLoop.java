package ai.pipestream.server.work;

import com.google.protobuf.Message;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.event.Observes;
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
 */
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
                    try {
                        runWorker();
                    } finally {
                        activeWorkers.decrementAndGet();
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

    private void runWorker() {
        BackoffSchedule backoff = new BackoffSchedule(
                config.reconnectInitialDelay(), config.reconnectMaxDelay());
        try {
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
                        if (activeWorkers.get() > minWorkers) {
                            return;
                        }
                        Duration wait = suggestedNoWorkRetry;
                        if (wait.isZero() || wait.isNegative()) {
                            wait = config.noWorkRetryAfter();
                        }
                        sleepInterruptibly(wait);
                    }
                    case STREAM_ERROR -> {
                        streamErrors.incrementAndGet();
                        engineClient.reconnect();
                        Duration wait = backoff.next();
                        LOG.warnf("Stream error; backing off %s before reconnect "
                                + "(total stream-errors=%d)", wait, streamErrors.get());
                        sleepInterruptibly(wait);
                    }
                }
            }
        } finally {
            // worker thread exits
        }
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
