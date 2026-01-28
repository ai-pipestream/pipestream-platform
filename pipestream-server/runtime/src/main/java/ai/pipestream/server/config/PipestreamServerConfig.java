package ai.pipestream.server.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithName;

import java.util.OptionalInt;

@ConfigMapping(prefix = "pipestream.server")
public interface PipestreamServerConfig {

    @WithName("class")
    @io.smallrye.config.WithDefault("core")
    String serverClass();

    @WithName("capabilities")
    @io.smallrye.config.WithDefault("")
    String capabilities();

    @WithName("http2.connection-window-size")
    OptionalInt http2ConnectionWindowSize();
}
