package ai.pipestream.server.work;

import java.time.Duration;
import java.util.Objects;

/**
 * Exponential backoff schedule with a ceiling. Used by
 * {@link ModuleWorkerLoop} to throttle reconnect attempts after stream
 * failures: the first failure waits {@code initial}, the next waits
 * {@code 2 × initial}, then {@code 4 × initial}, and so on, capped at
 * {@code max}.
 *
 * <p>{@link #reset()} returns the schedule to the initial delay; the
 * loop calls this after a successful stream cycle so the next failure
 * doesn't inherit the previous run's accumulated backoff.
 *
 * <p>Not thread-safe — each session/worker owns its own schedule.
 * Stateless across processes since the loop reconstructs one on each
 * application start.
 */
public final class BackoffSchedule {

    private final long initialMillis;
    private final long maxMillis;
    private long currentMillis;

    public BackoffSchedule(Duration initial, Duration max) {
        Objects.requireNonNull(initial, "initial");
        Objects.requireNonNull(max, "max");
        if (initial.isNegative() || initial.isZero()) {
            throw new IllegalArgumentException("initial must be > 0: " + initial);
        }
        if (max.compareTo(initial) < 0) {
            throw new IllegalArgumentException("max (" + max + ") must be >= initial (" + initial + ")");
        }
        this.initialMillis = initial.toMillis();
        this.maxMillis = max.toMillis();
        this.currentMillis = this.initialMillis;
    }

    /**
     * Return the current delay and advance the schedule (next call
     * returns {@code min(2 × this, max)}).
     */
    public Duration next() {
        Duration result = Duration.ofMillis(currentMillis);
        currentMillis = Math.min(currentMillis * 2, maxMillis);
        return result;
    }

    /**
     * Reset to the initial delay. Called by the loop after a
     * successful stream cycle so consecutive failures don't compound
     * indefinitely across the lifetime of the JVM.
     */
    public void reset() {
        this.currentMillis = this.initialMillis;
    }
}
