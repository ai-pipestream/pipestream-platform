package ai.pipestream.quarkus.dynamicgrpc;

import ai.pipestream.quarkus.dynamicgrpc.config.DynamicGrpcConfig;
import io.smallrye.config.ConfigMapping;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the per-service override property prefix used by
 * {@link ChannelManager#PER_SERVICE_OVERRIDE_PREFIX} to the
 * {@link DynamicGrpcConfig} {@code @ConfigMapping} prefix.
 * <p>
 * If these two ever drift apart, every {@code per-service.<name>} override
 * is silently ignored at runtime — not a compile failure, not a startup
 * failure, just every service quietly falling back to the default pool
 * size. This regression test makes that drift a build break instead.
 */
class PerServiceOverridePrefixTest {

    @Test
    void perServiceOverridePrefixMatchesConfigMapping() {
        ConfigMapping mapping = DynamicGrpcConfig.class.getAnnotation(ConfigMapping.class);
        assertThat(mapping)
                .as("DynamicGrpcConfig must keep its @ConfigMapping annotation")
                .isNotNull();

        String expected = mapping.prefix() + ".channel.per-service.";

        assertThat(ChannelManager.PER_SERVICE_OVERRIDE_PREFIX)
                .as("ChannelManager must look up per-service overrides under "
                        + "the same root as DynamicGrpcConfig's @ConfigMapping prefix")
                .isEqualTo(expected);
    }
}
