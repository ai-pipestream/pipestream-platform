package ai.pipestream.server.security;

import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;

@ConfigMapping(prefix = "pipestream.security")
@ConfigRoot(phase = ConfigPhase.RUN_TIME)
public interface PipestreamSecurityConfig {

    @WithName("admin-fallback-enabled")
    @WithDefault("false")
    boolean adminFallbackEnabled();
}
