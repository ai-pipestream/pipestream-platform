package ai.pipestream.registration.deployment;

import ai.pipestream.registration.HealthReporter;
import ai.pipestream.registration.RegistrationClient;
import ai.pipestream.registration.ServiceMetadataCollector;
import ai.pipestream.registration.ServiceRegistrationManager;
import ai.pipestream.registration.config.RegistrationConfig;
import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.FeatureBuildItem;

/**
 * Quarkus deployment processor for the service registration extension.
 * 
 * <p>This processor registers the extension's beans and performs any
 * necessary build-time setup.
 */
public class RegistrationProcessor {

    private static final String FEATURE = "pipestream-service-registration";

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

    @BuildStep
    AdditionalBeanBuildItem registerBeans() {
        return AdditionalBeanBuildItem.builder()
                .addBeanClasses(
                        ServiceRegistrationManager.class,
                        RegistrationClient.class,
                        ServiceMetadataCollector.class,
                        HealthReporter.class
                )
                .setUnremovable()
                .build();
    }
}
