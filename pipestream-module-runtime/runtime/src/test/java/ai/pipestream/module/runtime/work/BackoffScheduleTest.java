package ai.pipestream.module.runtime.work;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BackoffScheduleTest {

    @Test
    void firstCallReturnsInitial() {
        BackoffSchedule s = new BackoffSchedule(Duration.ofMillis(100), Duration.ofSeconds(10));
        assertThat(s.next())
                .as("first next() must return the initial delay")
                .isEqualTo(Duration.ofMillis(100));
    }

    @Test
    void subsequentCallsDouble() {
        BackoffSchedule s = new BackoffSchedule(Duration.ofMillis(100), Duration.ofSeconds(10));
        s.next(); // 100ms — advance past initial
        assertThat(s.next()).as("second next() doubles").isEqualTo(Duration.ofMillis(200));
        assertThat(s.next()).as("third next() doubles again").isEqualTo(Duration.ofMillis(400));
    }

    @Test
    void cappedAtMax() {
        BackoffSchedule s = new BackoffSchedule(Duration.ofMillis(100), Duration.ofMillis(500));
        s.next(); // 100
        s.next(); // 200
        s.next(); // 400
        assertThat(s.next())
                .as("next() must cap at max instead of overshooting")
                .isEqualTo(Duration.ofMillis(500));
        assertThat(s.next())
                .as("further calls stay at max")
                .isEqualTo(Duration.ofMillis(500));
    }

    @Test
    void resetReturnsToInitial() {
        BackoffSchedule s = new BackoffSchedule(Duration.ofMillis(100), Duration.ofSeconds(10));
        s.next();
        s.next();
        s.next();
        s.reset();
        assertThat(s.next())
                .as("reset() must clear accumulated backoff so a long-running healthy "
                        + "loop doesn't inherit ancient failure history")
                .isEqualTo(Duration.ofMillis(100));
    }

    @Test
    void rejectsInvalidArgs() {
        assertThatThrownBy(() -> new BackoffSchedule(Duration.ZERO, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BackoffSchedule(Duration.ofMillis(-1), Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BackoffSchedule(Duration.ofSeconds(5), Duration.ofSeconds(1)))
                .as("max cannot be less than initial")
                .isInstanceOf(IllegalArgumentException.class);
    }
}
