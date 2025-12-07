package ai.pipestream.quarkus.devservices.runtime;

import io.quarkus.runtime.configuration.ConfigBuilder;
import io.smallrye.config.SmallRyeConfigBuilder;

/**
 * ConfigBuilder that provides BUILD_TIME configuration for Quarkus Compose Dev Services.
 * This allows the extension to set compose dev services properties that BUILD_TIME config can read.
 * 
 * The ConfigSource is added unconditionally and reads static variables lazily when getValue() is called,
 * allowing the values to be set by build steps after the ConfigBuilder is invoked.
 */
public class ComposeDevServicesConfigBuilder implements ConfigBuilder {
    
    // These values are set by the deployment module build step
    public static volatile String composeFiles;
    public static volatile String projectName;
    public static volatile boolean enabled = true;
    public static volatile boolean startServices = true;
    public static volatile boolean stopServices = false;
    public static volatile boolean reuseProjectForTests = true;
    
    @Override
    public SmallRyeConfigBuilder configBuilder(SmallRyeConfigBuilder builder) {
        // Always add the ConfigSource - it will check static variables lazily when getValue() is called
        // This allows the values to be set by build steps after the ConfigBuilder is invoked
        return builder.withSources(new PipelineDevServicesConfigSource());
    }
}
