package ai.pipestream.module.runtime.deployment;

import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.FeatureBuildItem;

public class PipestreamModuleRuntimeProcessor {

    private static final String FEATURE = "pipestream-module-runtime";

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }
}
