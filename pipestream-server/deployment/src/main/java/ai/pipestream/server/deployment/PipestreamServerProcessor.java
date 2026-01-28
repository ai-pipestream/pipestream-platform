package ai.pipestream.server.deployment;

import ai.pipestream.server.http.PipestreamHttpServerOptionsCustomizer;
import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.FeatureBuildItem;

public class PipestreamServerProcessor {

    private static final String FEATURE = "pipestream-server";

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

    @BuildStep
    AdditionalBeanBuildItem registerBeans() {
        return AdditionalBeanBuildItem.builder()
                .addBeanClasses(PipestreamHttpServerOptionsCustomizer.class)
                .setUnremovable()
                .build();
    }
}
