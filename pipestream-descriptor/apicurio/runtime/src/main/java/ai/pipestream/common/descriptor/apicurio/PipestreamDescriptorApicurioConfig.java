package ai.pipestream.common.descriptor.apicurio;

import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

import java.util.Optional;

/**
 * Runtime configuration for the Pipestream Descriptor Apicurio extension.
 */
@ConfigMapping(prefix = "pipestream.descriptor.apicurio")
@ConfigRoot(phase = ConfigPhase.RUN_TIME)
public interface PipestreamDescriptorApicurioConfig {

    /**
     * Whether to enable the Apicurio descriptor loader.
     */
    @WithDefault("true")
    boolean enabled();

    /**
     * The URL of the Apicurio Registry.
     * If not specified, will attempt to use common registry properties.
     */
    Optional<String> registryUrl();

    /**
     * The group ID to use when searching for descriptors in Apicurio.
     */
    @WithDefault("default")
    String groupId();

    /**
     * Whether to automatically load all descriptors from the registry on startup.
     * If false, descriptors will be resolved on-demand.
     */
    @WithDefault("false")
    boolean autoLoadOnStartup();
}
