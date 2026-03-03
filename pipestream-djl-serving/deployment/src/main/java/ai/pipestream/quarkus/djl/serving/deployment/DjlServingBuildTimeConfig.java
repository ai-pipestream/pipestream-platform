package ai.pipestream.quarkus.djl.serving.deployment;

import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

import java.util.Optional;

@ConfigMapping(prefix = "pipestream.djl-serving")
@ConfigRoot(phase = ConfigPhase.BUILD_TIME)
public interface DjlServingBuildTimeConfig {

    /**
     * Configuration for Dev Services.
     */
    DevServicesConfig devservices();

    interface DevServicesConfig {
        /**
         * Whether Dev Services for DJL Serving is enabled.
         */
        @WithDefault("true")
        boolean enabled();

        /**
         * The container image name to use.
         */
        @WithDefault(DjlServingContainer.DEFAULT_IMAGE)
        String imageName();

        /**
         * Whether to shared the Dev Service instance across multiple applications.
         */
        @WithDefault("true")
        boolean shared();
    }
}
