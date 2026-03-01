package ai.pipestream.descriptor.apicurio.deployment;

import ai.pipestream.common.descriptor.DescriptorRegistry;
import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.FeatureBuildItem;

/**
 * Quarkus deployment processor for the Pipestream Descriptor Apicurio extension.
 */
public class PipestreamDescriptorApicurioProcessor {

    private static final String FEATURE = "pipestream-descriptor-apicurio";

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

    @BuildStep
    AdditionalBeanBuildItem registerBeans() {
        return AdditionalBeanBuildItem.builder()
                .addBeanClasses(DescriptorRegistry.class, ai.pipestream.common.descriptor.apicurio.ApicurioDescriptorLoaderProducer.class)
                .setUnremovable()
                .build();
    }
}
