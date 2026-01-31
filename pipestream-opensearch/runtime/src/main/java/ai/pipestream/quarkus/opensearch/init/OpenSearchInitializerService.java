package ai.pipestream.quarkus.opensearch.init;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import org.opensearch.client.opensearch.OpenSearchClient;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Service that runs all registered OpenSearch initializers on application startup.
 * 
 * <p>This service discovers all CDI beans implementing {@link OpenSearchInitializer}
 * and executes them in priority order (lowest priority first).
 */
@ApplicationScoped
public class OpenSearchInitializerService {

    private static final Logger LOG = Logger.getLogger(OpenSearchInitializerService.class);

    @Inject
    OpenSearchClient client;

    @Inject
    Instance<OpenSearchInitializer> initializers;

    /**
     * Runs all initializers on application startup.
     */
    void onStart(@Observes StartupEvent event) {
        List<OpenSearchInitializer> sortedInitializers = initializers.stream()
                .sorted(Comparator.comparingInt(OpenSearchInitializer::priority))
                .collect(Collectors.toList());

        if (sortedInitializers.isEmpty()) {
            LOG.debug("No OpenSearch initializers found");
            return;
        }

        LOG.infof("Running %d OpenSearch initializer(s)", sortedInitializers.size());

        for (OpenSearchInitializer initializer : sortedInitializers) {
            runInitializer(initializer);
        }

        LOG.info("OpenSearch initialization complete");
    }

    private void runInitializer(OpenSearchInitializer initializer) {
        String name = initializer.name();
        int priority = initializer.priority();

        LOG.debugf("Running initializer: %s (priority: %d)", name, priority);

        try {
            long startTime = System.currentTimeMillis();
            initializer.initialize(client);
            long duration = System.currentTimeMillis() - startTime;

            LOG.infof("Initializer '%s' completed in %dms", name, duration);

        } catch (Exception e) {
            if (initializer.failOnError()) {
                throw new RuntimeException(
                        String.format("OpenSearch initializer '%s' failed", name), e);
            } else {
                LOG.warnf(e, "OpenSearch initializer '%s' failed (non-fatal)", name);
            }
        }
    }
}
