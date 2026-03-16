package ai.pipestream.server.security;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;

@ConfigMapping(prefix = "pipestream.security")
public interface PipestreamSecurityConfig {

    @WithName("admin-fallback-enabled")
    @WithDefault("false")
    boolean adminFallbackEnabled();
}
