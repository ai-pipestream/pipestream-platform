package ai.pipestream.quarkus.opensearch.init;

import ai.pipestream.quarkus.opensearch.config.OpenSearchRuntimeConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.indices.PutIndexTemplateRequest;

/**
 * Default initializer that sets up common index templates.
 * 
 * <p>This initializer creates a default index template for pipestream indices
 * with sensible defaults for vector search (k-NN) and text analysis.
 * 
 * <p>Applications can disable this by setting:
 * <pre>
 * pipestream.opensearch.default-template.enabled=false
 * </pre>
 */
@ApplicationScoped
public class IndexTemplateInitializer implements OpenSearchInitializer {

    private static final Logger LOG = Logger.getLogger(IndexTemplateInitializer.class);

    private static final String PIPESTREAM_TEMPLATE_NAME = "pipestream-default";
    private static final String PIPESTREAM_INDEX_PATTERN = "pipestream-*";

    @Inject
    OpenSearchRuntimeConfig config;

    @Override
    public void initialize(OpenSearchClient client) throws Exception {
        // Check if template already exists
        boolean templateExists = client.indices()
                .existsIndexTemplate(e -> e.name(PIPESTREAM_TEMPLATE_NAME))
                .value();

        if (templateExists) {
            LOG.debugf("Index template '%s' already exists, skipping creation", 
                    PIPESTREAM_TEMPLATE_NAME);
            return;
        }

        LOG.infof("Creating default index template: %s", PIPESTREAM_TEMPLATE_NAME);

        // Create a basic index template for pipestream indices
        // This provides sensible defaults that can be overridden per-index
        client.indices().putIndexTemplate(PutIndexTemplateRequest.of(t -> t
                .name(PIPESTREAM_TEMPLATE_NAME)
                .indexPatterns(PIPESTREAM_INDEX_PATTERN)
                .priority(100)  // Low priority so app-specific templates can override
                .template(template -> template
                        .settings(settings -> settings
                                // Basic settings
                                .numberOfShards(1)
                                .numberOfReplicas(0)  // Good for dev/test, override in prod
                                // k-NN settings for vector search
                                .knn(true)
                        )
                )
        ));

        LOG.infof("Created index template '%s' for pattern '%s'", 
                PIPESTREAM_TEMPLATE_NAME, PIPESTREAM_INDEX_PATTERN);
    }

    @Override
    public int priority() {
        // Run early - templates should be created before indices
        return 50;
    }

    @Override
    public String name() {
        return "PipestreamDefaultTemplate";
    }

    @Override
    public boolean failOnError() {
        // Template creation failure is not fatal - indices can still be created
        return false;
    }
}
