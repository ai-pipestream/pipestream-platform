package ai.pipestream.server.deployment;

import ai.pipestream.server.http.PipestreamHttpServerOptionsCustomizer;
import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.IndexDependencyBuildItem;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.annotations.BuildProducer;
import org.jboss.logging.Logger;

public class PipestreamServerProcessor {

    private static final Logger LOG = Logger.getLogger(PipestreamServerProcessor.class);
    private static final String FEATURE = "pipestream-server";
    private static final String GROUP_ID = "ai.pipestream";

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

    @BuildStep
    void indexDependencies(BuildProducer<IndexDependencyBuildItem> indexDependencies) {
        indexDependencies.produce(new IndexDependencyBuildItem(GROUP_ID, "pipestream-service-registration"));
        indexDependencies.produce(new IndexDependencyBuildItem(GROUP_ID, "quarkus-dynamic-grpc"));
        LOG.info("Registered index-dependencies for pipestream-service-registration and quarkus-dynamic-grpc");
    }
}
