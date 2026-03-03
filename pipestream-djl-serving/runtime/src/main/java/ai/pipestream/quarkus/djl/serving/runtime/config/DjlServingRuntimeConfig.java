package ai.pipestream.quarkus.djl.serving.runtime.config;

import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

import java.util.Optional;

@ConfigMapping(prefix = "pipestream.djl-serving")
@ConfigRoot(phase = ConfigPhase.RUN_TIME)
public interface DjlServingRuntimeConfig {

    /**
     * The DJL Serving URL.
     */
    @WithDefault("http://localhost:8080")
    String url();

    /**
     * Whether the DJL Serving extension is enabled.
     */
    @WithDefault("true")
    boolean enabled();
}
