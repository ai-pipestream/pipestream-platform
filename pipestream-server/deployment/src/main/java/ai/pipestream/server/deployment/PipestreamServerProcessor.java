package ai.pipestream.server.deployment;

import ai.pipestream.server.health.GrpcServiceHealthCheck;
import ai.pipestream.server.health.HttpServerReadinessCheck;
import ai.pipestream.server.health.PipestreamHealthConfig;
import ai.pipestream.server.health.PipestreamLivenessCheck;
import ai.pipestream.server.http.PipestreamHttpServerOptionsCustomizer;
import ai.pipestream.server.meta.BuildInfoProvider;
import ai.pipestream.server.meta.BuildInfoResource;
import ai.pipestream.server.util.ChunkSizeCalculator;
import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.smallrye.health.deployment.spi.HealthBuildItem;

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
                .addBeanClasses(ChunkSizeCalculator.class)
                .addBeanClasses(BuildInfoProvider.class)
                .addBeanClasses(BuildInfoResource.class)
                .setUnremovable()
                .build();
    }

    @BuildStep
    void registerHealthChecks(BuildProducer<HealthBuildItem> healthChecks,
                              BuildProducer<AdditionalBeanBuildItem> beans) {
        beans.produce(AdditionalBeanBuildItem.unremovableOf(PipestreamHealthConfig.class));

        beans.produce(AdditionalBeanBuildItem.unremovableOf(GrpcServiceHealthCheck.class));
        healthChecks.produce(new HealthBuildItem(
                GrpcServiceHealthCheck.class.getName(), true));

        beans.produce(AdditionalBeanBuildItem.unremovableOf(HttpServerReadinessCheck.class));
        healthChecks.produce(new HealthBuildItem(
                HttpServerReadinessCheck.class.getName(), true));

        beans.produce(AdditionalBeanBuildItem.unremovableOf(PipestreamLivenessCheck.class));
        healthChecks.produce(new HealthBuildItem(
                PipestreamLivenessCheck.class.getName(), true));
    }

}
