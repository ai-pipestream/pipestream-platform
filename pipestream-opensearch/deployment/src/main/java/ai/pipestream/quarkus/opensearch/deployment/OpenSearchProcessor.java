package ai.pipestream.quarkus.opensearch.deployment;

import ai.pipestream.quarkus.opensearch.client.OpenSearchClientProducer;
import ai.pipestream.quarkus.opensearch.client.ReactiveOpenSearchClient;
import ai.pipestream.quarkus.opensearch.config.OpenSearchBuildTimeConfig;
import ai.pipestream.quarkus.opensearch.health.OpenSearchHealthCheck;
import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.smallrye.health.deployment.spi.HealthBuildItem;
import org.jboss.logging.Logger;

/**
 * Main build processor for the OpenSearch extension.
 * Registers CDI beans and extension features.
 */
public class OpenSearchProcessor {

    private static final Logger LOG = Logger.getLogger(OpenSearchProcessor.class);
    private static final String FEATURE = "pipestream-opensearch";

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

    @BuildStep
    void registerBeans(BuildProducer<AdditionalBeanBuildItem> additionalBeans) {
        LOG.debug("Registering OpenSearch CDI beans");

        // Register the client producer
        additionalBeans.produce(AdditionalBeanBuildItem.builder()
                .addBeanClass(OpenSearchClientProducer.class)
                .setUnremovable()
                .build());

        // Register the reactive client
        additionalBeans.produce(AdditionalBeanBuildItem.builder()
                .addBeanClass(ReactiveOpenSearchClient.class)
                .setUnremovable()
                .build());
    }

    @BuildStep
    void registerHealthCheck(
            OpenSearchBuildTimeConfig config,
            BuildProducer<AdditionalBeanBuildItem> additionalBeans,
            BuildProducer<HealthBuildItem> healthBuildItems) {

        if (config.healthEnabled()) {
            LOG.debug("Registering OpenSearch health check");

            additionalBeans.produce(AdditionalBeanBuildItem.builder()
                    .addBeanClass(OpenSearchHealthCheck.class)
                    .setUnremovable()
                    .build());

            healthBuildItems.produce(new HealthBuildItem(
                    OpenSearchHealthCheck.class.getName(),
                    true  // enabled by default
            ));
        }
    }
}
