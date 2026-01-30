package ai.pipestream.quarkus.opensearch.health;

import ai.pipestream.quarkus.opensearch.config.OpenSearchRuntimeConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.HealthCheckResponseBuilder;
import org.eclipse.microprofile.health.Readiness;
import org.jboss.logging.Logger;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.cluster.HealthResponse;

/**
 * Health check for OpenSearch connectivity.
 * Reports the cluster health status via the /q/health/ready endpoint.
 */
@Readiness
@ApplicationScoped
public class OpenSearchHealthCheck implements HealthCheck {

    private static final Logger LOG = Logger.getLogger(OpenSearchHealthCheck.class);

    @Inject
    OpenSearchClient client;

    @Inject
    OpenSearchRuntimeConfig config;

    @Override
    public HealthCheckResponse call() {
        HealthCheckResponseBuilder builder = HealthCheckResponse.named("OpenSearch connection health check");

        try {
            HealthResponse healthResponse = client.cluster().health();
            String status = healthResponse.status().jsonValue();
            String clusterName = healthResponse.clusterName();
            int numberOfNodes = healthResponse.numberOfNodes();

            builder.withData("hosts", config.hosts())
                    .withData("clusterName", clusterName)
                    .withData("clusterStatus", status)
                    .withData("numberOfNodes", numberOfNodes);

            // Consider green and yellow as healthy
            if ("green".equals(status) || "yellow".equals(status)) {
                builder.up();
            } else {
                builder.down();
            }

        } catch (Exception e) {
            LOG.warnf(e, "OpenSearch health check failed for hosts: %s", config.hosts());
            builder.down()
                    .withData("hosts", config.hosts())
                    .withData("error", e.getMessage());
        }

        return builder.build();
    }
}
