package ai.pipestream.quarkus.devservices;

import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

import java.util.Optional;

/**
 * Configuration for Pipeline Dev Services extension.
 * BUILD_TIME config can be in deployment module, so it's not included in runtime JAR.
 */
@ConfigRoot(phase = ConfigPhase.BUILD_TIME)
@ConfigMapping(prefix = "quarkus.pipeline-devservices")
public interface PipelineDevServicesConfig {

    /**
     * Whether the extension is enabled.
     */
    @WithDefault("true")
    boolean enabled();

    /**
     * Target directory where the compose file will be extracted.
     * Defaults to ~/.pipeline
     */
    @WithDefault("${user.home}/.pipeline")
    String targetDir();

    /**
     * Project name for the compose services.
     * If not set, uses the name from the compose file.
     */
    Optional<String> projectName();

    /**
     * Whether to auto-update the compose file if version changes.
     */
    @WithDefault("true")
    boolean autoUpdate();

    /**
     * Force update even if file was edited.
     */
    @WithDefault("false")
    boolean forceUpdate();
}
