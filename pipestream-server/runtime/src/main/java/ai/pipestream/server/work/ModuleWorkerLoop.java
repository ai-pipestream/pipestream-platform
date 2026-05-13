package ai.pipestream.server.work;

import ai.pipestream.module.work.v1.ModuleWorkServiceGrpc;
import com.google.protobuf.Message;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.event.Observes;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Module-side demand-pull worker. Maintains {@link
 * WorkerLoopConfig#concurrency()} concurrent virtual threads, each
 * running an infinite loop of {@link WorkStreamSession}s against the
 * engine's {@code ModuleWorkService.Work} bidi RPC.
 *
 * <p>This is the framework's only concrete public consumer-facing
 * class — a module instantiates one per node-type it serves and lets
 * Quarkus CDI manage its lifecycle. The class is final by design:
 * subclassing is not the integration point; constructor injection of
 * a {@link ModuleProcessor} is. That keeps the loop's lifecycle
 * semantics under the framework's control.
 *
 * <p>Concurrency model: N worker virtual threads run independent
 * session loops. Each worker:
 * <ol>
 *   <li>Opens a new {@code Work} bidi stream.</li>
 *   <li>Runs one {@link WorkStreamSession}.</li>
 *   <li>On {@code SUCCESS} or {@code FAILED_BY_MODULE}: resets backoff
 *       and immediately loops.</li>
 *   <li>On {@code NO_WORK_AVAILABLE}: sleeps {@link
 *       WorkerLoopConfig#noWorkRetryAfter()} (resetting backoff),
 *       then loops.</li>
 *   <li>On {@code STREAM_ERROR}: sleeps the current backoff value
 *       (which doubles up to {@link
 *       WorkerLoopConfig#reconnectMaxDelay()}), then loops.</li>
 * </ol>
 *
 * <p>Workers exit cleanly when {@link #onStop(ShutdownEvent)} fires;
 * the in-progress session (if any) completes its current Ack
 * naturally before the worker thread exits.
 *
 * @param <T> the module's concrete payload type
 */
public final class ModuleWorkerLoop<T extends Message> {

    private static final Logger LOG = Logger.getLogger(ModuleWorkerLoop.class);

    private final ModuleWorkServiceGrpc.ModuleWorkServiceStub asyncStub;
    private final ModuleProcessor<T> processor;
    private final PayloadCodec<T> codec;
    private final WorkerLoopConfig config;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicInteger streamErrors = new AtomicInteger();
    private final AtomicInteger sessionsCompleted = new AtomicInteger();
    private volatile CountDownLatch workersStopped;
    private Thread[] workers;

    /**
     * @param messageClass the concrete payload type — passed
     *                     separately from {@code processor} because
     *                     Java erases the generic parameter and
     *                     {@link PayloadCodec} needs the {@code Class}
     *                     for {@code Any.unpack}
     * @param processor    the module's pure-function processor
     * @param asyncStub    a configured async stub for the engine's
     *                     {@code ModuleWorkService}; the module wires
     *                     this up via {@code @GrpcClient("engine")}
     *                     and Quarkus/Stork discovery
     * @param config       the runtime config; the loop reads
     *                     {@code enabled}, {@code concurrency},
     *                     {@code heartbeatInterval}, and the reconnect
     *                     backoff settings
     */
    public ModuleWorkerLoop(Class<T> messageClass,
                            ModuleProcessor<T> processor,
                            ModuleWorkServiceGrpc.ModuleWorkServiceStub asyncStub,
                            WorkerLoopConfig config) {
        this.processor = Objects.requireNonNull(processor, "processor");
        this.codec = new PayloadCodec<>(Objects.requireNonNull(messageClass, "messageClass"));
        this.asyncStub = Objects.requireNonNull(asyncStub, "asyncStub");
        this.config = Objects.requireNonNull(config, "config");
    }

    /**
     * Quarkus lifecycle hook: on application startup, if the loop is
     * enabled, spawn the worker virtual threads. Each worker is named
     * for diagnostics ({@code worker-<cluster>-<graph>-<node>-<n>}).
     */
    public void onStart(@Observes StartupEvent event) {
        if (!config.enabled()) {
            LOG.infof("ModuleWorkerLoop disabled (pipestream.module.worker-loop.enabled=false)");
            return;
        }
        int n = config.concurrency();
        if (n < 1) {
            LOG.warnf("ModuleWorkerLoop disabled — invalid concurrency %d (must be >= 1)", n);
            return;
        }
        running.set(true);
        workersStopped = new CountDownLatch(n);
        workers = new Thread[n];
        String namePrefix = "worker-" + config.cluster() + "-" + config.graphId() + "-" + config.nodeId() + "-";
        for (int i = 0; i < n; i++) {
            final int workerIndex = i;
            workers[i] = Thread.ofVirtual()
                    .name(namePrefix + workerIndex)
                    .start(this::runWorker);
        }
        LOG.infof("ModuleWorkerLoop started: cluster=%s graph=%s node=%s concurrency=%d "
                        + "heartbeat=%s payloadType=%s",
                config.cluster(), config.graphId(), config.nodeId(), n,
                config.heartbeatInterval(), codec.messageClass().getSimpleName());
    }

    /**
     * Quarkus lifecycle hook: signal workers to exit. The actual exit
     * is cooperative — the worker's session may be in the middle of
     * processing a unit. We wait briefly for the latch to drain so
     * shutdown logs reflect what actually finished; if a worker is
     * genuinely stuck, the JVM exit will tear down the daemon VT.
     */
    public void onStop(@Observes ShutdownEvent event) {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        try {
            if (workersStopped != null) {
                workersStopped.await(10, java.util.concurrent.TimeUnit.SECONDS);
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        LOG.infof("ModuleWorkerLoop stopped: sessions=%d stream-errors=%d",
                sessionsCompleted.get(), streamErrors.get());
    }

    private void runWorker() {
        BackoffSchedule backoff = new BackoffSchedule(
                config.reconnectInitialDelay(), config.reconnectMaxDelay());
        try {
            while (running.get()) {
                WorkStreamSession<T> session = new WorkStreamSession<>(asyncStub, processor, codec, config);
                WorkStreamSession.Outcome outcome = session.run();
                sessionsCompleted.incrementAndGet();
                switch (outcome) {
                    case SUCCESS, FAILED_BY_MODULE -> backoff.reset();
                    case NO_WORK_AVAILABLE -> {
                        backoff.reset();
                        sleepInterruptibly(config.noWorkRetryAfter());
                    }
                    case STREAM_ERROR -> {
                        streamErrors.incrementAndGet();
                        Duration wait = backoff.next();
                        LOG.warnf("Stream error; backing off %s before reconnect "
                                + "(total stream-errors=%d)", wait, streamErrors.get());
                        sleepInterruptibly(wait);
                    }
                }
            }
        } finally {
            if (workersStopped != null) {
                workersStopped.countDown();
            }
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

    // ----- Visible for testing -----

    int sessionsCompleted() {
        return sessionsCompleted.get();
    }

    int streamErrors() {
        return streamErrors.get();
    }

    boolean isRunning() {
        return running.get();
    }
}
