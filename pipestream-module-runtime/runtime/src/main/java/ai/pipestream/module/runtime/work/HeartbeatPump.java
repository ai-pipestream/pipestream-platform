package ai.pipestream.module.runtime.work;

import ai.pipestream.module.work.v1.Heartbeat;
import ai.pipestream.module.work.v1.WorkRequest;
import io.grpc.stub.StreamObserver;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Periodic {@code Heartbeat} emitter for one open {@code Work} stream.
 *
 * <p>Started by {@link WorkStreamSession} just before invoking the
 * module's {@code process} call; stopped immediately after the call
 * returns. Each heartbeat is sent on whatever thread the scheduler
 * provides; the underlying gRPC stream's
 * {@link StreamObserver#onNext} is documented as thread-safe-against-
 * itself when the bidi stream is in flow-controlled mode, but the
 * session is the sole writer to the request observer aside from this
 * pump, so the session's call sites cooperate via a write lock owned
 * by the session.
 *
 * <p>One pump per stream — they are not reused. A single daemon-VT
 * scheduler is allocated on construction and shut down on
 * {@link #close()}; cost is one virtual thread per active work unit
 * for the lifetime of the {@code process} call only.
 */
final class HeartbeatPump implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(HeartbeatPump.class);

    private final StreamObserver<WorkRequest> requestObserver;
    private final Runnable writeGuardedSend;
    private final Duration interval;
    private final ScheduledExecutorService scheduler;
    private volatile ScheduledFuture<?> tick;

    /**
     * @param requestObserver  the open stream's request observer; the
     *                         pump writes {@code Heartbeat} messages to
     *                         it on the scheduler thread
     * @param writeLock        synchronization gate the session passes
     *                         in; every {@code onNext} call goes
     *                         through it so heartbeats can't interleave
     *                         with the session's own Hello or Ack writes
     * @param interval         heartbeat cadence; the engine's watchdog
     *                         silence threshold is {@code 2 × interval}
     */
    HeartbeatPump(StreamObserver<WorkRequest> requestObserver,
                  Object writeLock,
                  Duration interval) {
        this.requestObserver = Objects.requireNonNull(requestObserver, "requestObserver");
        Objects.requireNonNull(writeLock, "writeLock");
        this.interval = Objects.requireNonNull(interval, "interval");
        this.scheduler = Executors.newSingleThreadScheduledExecutor(
                Thread.ofVirtual().name("worker-heartbeat-").factory());
        this.writeGuardedSend = () -> {
            synchronized (writeLock) {
                try {
                    requestObserver.onNext(WorkRequest.newBuilder()
                            .setHeartbeat(Heartbeat.newBuilder().build())
                            .build());
                } catch (RuntimeException e) {
                    // The stream has likely closed under us (peer RST,
                    // watchdog forced close, normal completion that
                    // raced the scheduler). Log at debug — the session
                    // will catch the real failure via its own error
                    // path.
                    LOG.debugf(e, "heartbeat send failed; stream likely closed");
                }
            }
        };
    }

    /** Begin emitting heartbeats. Idempotent — repeated calls are ignored. */
    void start() {
        if (tick != null) {
            return;
        }
        long millis = interval.toMillis();
        tick = scheduler.scheduleAtFixedRate(writeGuardedSend, millis, millis, TimeUnit.MILLISECONDS);
    }

    @Override
    public void close() {
        if (tick != null) {
            tick.cancel(false);
            tick = null;
        }
        scheduler.shutdownNow();
    }
}
