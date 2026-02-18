package ai.pipestream.registration.deployment;

import ai.pipestream.registration.DirectRegistrationService;
import ai.pipestream.registration.RegistrationClient;
import ai.pipestream.registration.ServiceMetadataCollector;
import ai.pipestream.registration.ServiceRegistrationManager;
import ai.pipestream.registration.consul.ConsulHealthChecker;
import ai.pipestream.registration.consul.ConsulRegistrar;
import ai.pipestream.registration.consul.PipestreamConsulClientProducer;
import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.IndexDependencyBuildItem;
import io.quarkus.deployment.builditem.FeatureBuildItem;

/**
 * Quarkus deployment processor for the service registration extension.
 *
 * <p>This processor registers the extension's beans and performs any
 * necessary build-time setup.
 */
public class RegistrationProcessor {

    private static final String FEATURE = "pipestream-service-registration";
    private static final String GROUP_ID = "ai.pipestream";
    private static final String ARTIFACT_ID = "pipestream-service-registration";

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

    @BuildStep
    void indexDependencies(BuildProducer<IndexDependencyBuildItem> indexDependencies) {
        indexDependencies.produce(new IndexDependencyBuildItem(GROUP_ID, ARTIFACT_ID));
    }

    @BuildStep
    AdditionalBeanBuildItem registerBeans() {
        return AdditionalBeanBuildItem.builder()
                .addBeanClasses(
                        ServiceRegistrationManager.class,
                        RegistrationClient.class,
                        ServiceMetadataCollector.class,
                        PipestreamConsulClientProducer.class,
                        ConsulRegistrar.class,
                        ConsulHealthChecker.class,
                        DirectRegistrationService.class
                )
                .setUnremovable()
                .build();
    }
}
