package ai.pipestream.server.config;

import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithName;

import java.util.OptionalInt;

@ConfigMapping(prefix = "pipestream.server")
@ConfigRoot(phase = ConfigPhase.RUN_TIME)
public interface PipestreamServerConfig {

    @WithName("class")
    @io.smallrye.config.WithDefault("core")
    String serverClass();

    @WithName("capabilities")
    java.util.Optional<String> capabilities();

    @WithName("http2.connection-window-size")
    OptionalInt http2ConnectionWindowSize();
}
