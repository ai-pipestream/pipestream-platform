package ai.pipestream.server.health;

import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

import java.time.Duration;

@ConfigMapping(prefix = "pipestream.server.health")
@ConfigRoot(phase = ConfigPhase.RUN_TIME)
public interface PipestreamHealthConfig {

    @WithDefault("true")
    boolean enabled();

    GrpcHealthConfig grpc();

    HttpHealthConfig http();

    LivenessConfig liveness();

    interface GrpcHealthConfig {
        @WithDefault("true")
        boolean enabled();

        @WithDefault("3s")
        Duration timeout();

        @WithDefault("false")
        boolean tlsEnabled();

        @WithDefault("true")
        boolean tlsSkipVerify();
    }

    interface HttpHealthConfig {
        @WithDefault("true")
        boolean enabled();

        @WithDefault("2s")
        Duration timeout();

        @WithDefault("false")
        boolean tlsEnabled();

        @WithDefault("true")
        boolean tlsSkipVerify();
    }

    interface LivenessConfig {
        @WithDefault("true")
        boolean enabled();
    }
}
