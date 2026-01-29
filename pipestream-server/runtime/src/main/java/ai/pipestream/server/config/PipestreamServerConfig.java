package ai.pipestream.server.config;

import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithName;
import io.smallrye.config.WithDefault;

import java.util.OptionalInt;
import java.util.Optional;

@ConfigMapping(prefix = "pipestream.server")
@ConfigRoot(phase = ConfigPhase.RUN_TIME)
public interface PipestreamServerConfig {

    @WithName("class")
    @WithDefault("core")
    String serverClass();

    @WithName("capabilities")
    Optional<String> capabilities();

    @WithName("host-mode")
    @WithDefault("auto")
    String hostMode();

    @WithName("advertised-host")
    Optional<String> advertisedHost();

    @WithName("internal-host")
    Optional<String> internalHost();

    @WithName("http2.connection-window-size")
    OptionalInt http2ConnectionWindowSize();
}
