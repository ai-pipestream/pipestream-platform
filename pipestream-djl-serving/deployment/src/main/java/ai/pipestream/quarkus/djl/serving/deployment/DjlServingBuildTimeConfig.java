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
         * Whether to share the Dev Service instance across multiple applications.
         */
        @WithDefault("true")
        boolean shared();

        /**
         * The container image variant to use: {@code cpu}, {@code cuda}, or {@code aarch64}.
         * When not set, the variant is auto-detected: {@code aarch64} on ARM, {@code cuda} if
         * an NVIDIA device is present (dev mode only), otherwise {@code cpu}.
         * In test mode the variant is always {@code cpu} unless explicitly overridden.
         */
        Optional<String> variant();

        /**
         * The model name to register in DJL Serving on startup.
         * This becomes the path segment in /predictions/{modelName}.
         */
        @WithDefault(DjlServingContainer.DEFAULT_MODEL_NAME)
        String modelName();

        /**
         * The model URI passed to DJL Serving's management API for registration.
         */
        @WithDefault(DjlServingContainer.DEFAULT_MODEL_URI)
        String modelUri();
    }
}
