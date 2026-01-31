package ai.pipestream.quarkus.opensearch.client;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.mapping.TypeMapping;
import org.opensearch.client.opensearch.cluster.HealthResponse;
import org.opensearch.client.opensearch.core.BulkRequest;
import org.opensearch.client.opensearch.core.BulkResponse;
import org.opensearch.client.opensearch.indices.CreateIndexRequest;
import org.opensearch.client.opensearch.indices.CreateIndexResponse;
import org.opensearch.client.opensearch.indices.DeleteIndexResponse;
import org.opensearch.client.opensearch.indices.ExistsRequest;
import org.opensearch.client.opensearch.indices.IndexSettings;

import java.io.IOException;
import java.util.function.Function;

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
    public Uni<CreateIndexResponse> createIndexWithMappings(String indexName, TypeMapping mappings) {
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
     * @return Uni containing true if acknowledged
     */
    public Uni<Boolean> createIndex(String indexName) {
        return createIndexWithMappings(indexName, null)
                .map(CreateIndexResponse::acknowledged);
    }

    /**
     * Create an index with custom settings.
     *
     * @param indexName the index name
     * @param settingsBuilder function to configure index settings
     * @return Uni containing true if acknowledged
     */
    public Uni<Boolean> createIndex(String indexName, 
            Function<IndexSettings.Builder, IndexSettings.Builder> settingsBuilder) {
        return Uni.createFrom().item(() -> {
            try {
                IndexSettings settings = settingsBuilder.apply(new IndexSettings.Builder()).build();
                
                CreateIndexResponse response = client.indices().create(c -> c
                        .index(indexName)
                        .settings(settings)
                );
                LOG.infof("Created index: %s with settings, acknowledged: %s", 
                        indexName, response.acknowledged());
                return response.acknowledged();
            } catch (IOException e) {
                LOG.errorf(e, "Failed to create index: %s", indexName);
                throw new RuntimeException("Failed to create index", e);
            }
        }).runSubscriptionOn(io.smallrye.mutiny.infrastructure.Infrastructure.getDefaultWorkerPool());
    }

    /**
     * Delete an index.
     *
     * @param indexName the index name
     * @return Uni containing true if acknowledged
     */
    public Uni<Boolean> deleteIndex(String indexName) {
        return Uni.createFrom().item(() -> {
            try {
                DeleteIndexResponse response = client.indices().delete(d -> d.index(indexName));
                LOG.infof("Deleted index: %s, acknowledged: %s", indexName, response.acknowledged());
                return response.acknowledged();
            } catch (IOException e) {
                LOG.errorf(e, "Failed to delete index: %s", indexName);
                throw new RuntimeException("Failed to delete index", e);
            }
        }).runSubscriptionOn(io.smallrye.mutiny.infrastructure.Infrastructure.getDefaultWorkerPool());
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
     * Get cluster health response.
     *
     * @return Uni containing the cluster health response
     */
    public Uni<HealthResponse> getClusterHealth() {
        return Uni.createFrom().item(() -> {
            try {
                return client.cluster().health();
            } catch (IOException e) {
                LOG.error("Failed to get cluster health", e);
                throw new RuntimeException("Failed to get cluster health", e);
            }
        }).runSubscriptionOn(io.smallrye.mutiny.infrastructure.Infrastructure.getDefaultWorkerPool());
    }

    /**
     * Get cluster health status as a string.
     *
     * @return Uni containing the cluster health status (green, yellow, red)
     */
    public Uni<String> getClusterHealthStatus() {
        return getClusterHealth().map(h -> h.status().jsonValue());
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
