package ai.pipestream.quarkus.opensearch.client;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.mapping.TypeMapping;
import org.opensearch.client.opensearch.core.BulkRequest;
import org.opensearch.client.opensearch.core.BulkResponse;
import org.opensearch.client.opensearch.indices.CreateIndexRequest;
import org.opensearch.client.opensearch.indices.CreateIndexResponse;
import org.opensearch.client.opensearch.indices.ExistsRequest;

import java.io.IOException;

/**
 * Reactive wrapper around the synchronous OpenSearch client.
 * Uses Mutiny Uni to convert blocking calls to non-blocking operations.
 */
@ApplicationScoped
public class ReactiveOpenSearchClient {

    private static final Logger LOG = Logger.getLogger(ReactiveOpenSearchClient.class);

    @Inject
    OpenSearchClient client;

    /**
     * Check if an index exists.
     *
     * @param indexName the index name
     * @return Uni containing true if index exists, false otherwise
     */
    public Uni<Boolean> indexExists(String indexName) {
        return Uni.createFrom().item(() -> {
            try {
                return client.indices().exists(ExistsRequest.of(e -> e.index(indexName))).value();
            } catch (IOException e) {
                LOG.errorf(e, "Failed to check if index exists: %s", indexName);
                throw new RuntimeException("Failed to check index existence", e);
            }
        }).runSubscriptionOn(io.smallrye.mutiny.infrastructure.Infrastructure.getDefaultWorkerPool());
    }

    /**
     * Create an index with optional mappings.
     *
     * @param indexName the index name
     * @param mappings optional type mappings
     * @return Uni containing the create index response
     */
    public Uni<CreateIndexResponse> createIndex(String indexName, TypeMapping mappings) {
        return Uni.createFrom().item(() -> {
            try {
                CreateIndexRequest.Builder requestBuilder = new CreateIndexRequest.Builder()
                        .index(indexName);

                if (mappings != null) {
                    requestBuilder.mappings(mappings);
                }

                CreateIndexResponse response = client.indices().create(requestBuilder.build());
                LOG.infof("Created index: %s, acknowledged: %s", indexName, response.acknowledged());
                return response;
            } catch (IOException e) {
                LOG.errorf(e, "Failed to create index: %s", indexName);
                throw new RuntimeException("Failed to create index", e);
            }
        }).runSubscriptionOn(io.smallrye.mutiny.infrastructure.Infrastructure.getDefaultWorkerPool());
    }

    /**
     * Create an index without mappings.
     *
     * @param indexName the index name
     * @return Uni containing the create index response
     */
    public Uni<CreateIndexResponse> createIndex(String indexName) {
        return createIndex(indexName, null);
    }

    /**
     * Execute a bulk request.
     *
     * @param request the bulk request
     * @return Uni containing the bulk response
     */
    public Uni<BulkResponse> bulk(BulkRequest request) {
        return Uni.createFrom().item(() -> {
            try {
                BulkResponse response = client.bulk(request);
                if (response.errors()) {
                    LOG.warnf("Bulk request completed with errors. Items: %d, took: %dms",
                            response.items().size(), response.took());
                } else {
                    LOG.debugf("Bulk request completed successfully. Items: %d, took: %dms",
                            response.items().size(), response.took());
                }
                return response;
            } catch (IOException e) {
                LOG.error("Failed to execute bulk request", e);
                throw new RuntimeException("Failed to execute bulk request", e);
            }
        }).runSubscriptionOn(io.smallrye.mutiny.infrastructure.Infrastructure.getDefaultWorkerPool());
    }

    /**
     * Get cluster health status.
     *
     * @return Uni containing the cluster health status (green, yellow, red)
     */
    public Uni<String> getClusterHealth() {
        return Uni.createFrom().item(() -> {
            try {
                return client.cluster().health().status().jsonValue();
            } catch (IOException e) {
                LOG.error("Failed to get cluster health", e);
                throw new RuntimeException("Failed to get cluster health", e);
            }
        }).runSubscriptionOn(io.smallrye.mutiny.infrastructure.Infrastructure.getDefaultWorkerPool());
    }

    /**
     * Get the underlying synchronous client for advanced operations.
     *
     * @return the OpenSearch client
     */
    public OpenSearchClient getClient() {
        return client;
    }
}
