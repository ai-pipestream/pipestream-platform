package ai.pipestream.quarkus.opensearch.grpc;

import ai.pipestream.quarkus.dynamicgrpc.DynamicGrpcClientFactory;
import io.grpc.Channel;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import org.opensearch.protobufs.BulkRequest;
import org.opensearch.protobufs.BulkResponse;
import org.opensearch.protobufs.services.MutinyDocumentServiceGrpc;
import org.opensearch.protobufs.services.MutinySearchServiceGrpc;

/**
 * Lazy CDI producer for OpenSearch native gRPC clients.
 * <p>
 * Uses proto-toolchain generated Mutiny stubs (methods return {@code Uni} directly).
 * Service discovery uses Stork with the logical name {@code opensearch-grpc} (registered
 * in Consul by compose-devservices). Configure via:
 * <pre>
 * quarkus.dynamic-grpc.service.opensearch-grpc.address=host:9400  # direct host:port
 * # or use Consul discovery (default when address not set)
 * </pre>
 */
@ApplicationScoped
public class OpenSearchGrpcClientProducer {

    private static final Logger LOG = Logger.getLogger(OpenSearchGrpcClientProducer.class);
    private static final String OPENSEARCH_GRPC_SERVICE = "opensearch-grpc";

    @Inject
    DynamicGrpcClientFactory clientFactory;

    /**
     * Get the gRPC channel for OpenSearch (lazy, resolved via Stork).
     */
    public Uni<Channel> getChannel() {
        return clientFactory.getChannel(OPENSEARCH_GRPC_SERVICE);
    }

    /**
     * Get the DocumentService Mutiny stub for bulk operations.
     */
    public Uni<MutinyDocumentServiceGrpc.MutinyDocumentServiceStub> getDocumentServiceStub() {
        return clientFactory.getClient(OPENSEARCH_GRPC_SERVICE, MutinyDocumentServiceGrpc::newMutinyStub);
    }

    /**
     * Get the SearchService Mutiny stub for search and k-NN queries.
     */
    public Uni<MutinySearchServiceGrpc.MutinySearchServiceStub> getSearchServiceStub() {
        return clientFactory.getClient(OPENSEARCH_GRPC_SERVICE, MutinySearchServiceGrpc::newMutinyStub);
    }

    /**
     * Execute a bulk request via DocumentService gRPC.
     */
    public Uni<BulkResponse> bulk(BulkRequest request) {
        if (request == null || request.getBulkRequestBodyCount() == 0) {
            return Uni.createFrom().item(BulkResponse.newBuilder().setErrors(false).setTook(0).build());
        }
        return getDocumentServiceStub()
                .flatMap(stub -> stub.bulk(request))
                .onItem().invoke((BulkResponse r) -> {
                    if (r.getErrors()) {
                        LOG.warnf("Bulk gRPC request completed with errors. Items: %d, took: %dms",
                                r.getItemsCount(), r.getTook());
                    } else {
                        LOG.debugf("Bulk gRPC request completed successfully. Items: %d, took: %dms",
                                r.getItemsCount(), r.getTook());
                    }
                })
                .onFailure().invoke(e -> LOG.errorf(e, "Failed to execute bulk gRPC request"));
    }
}
