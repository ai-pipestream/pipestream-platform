package ai.pipestream.quarkus.opensearch.it;

import ai.pipestream.quarkus.opensearch.grpc.OpenSearchGrpcClientProducer;
import com.google.protobuf.ByteString;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.protobufs.BulkRequest;
import org.opensearch.protobufs.BulkRequestBody;
import org.opensearch.protobufs.BulkResponse;
import org.opensearch.protobufs.IndexOperation;
import org.opensearch.protobufs.OperationContainer;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for OpenSearch gRPC bulk indexing via DevServices.
 * <p>
 * Verifies that:
 * <ol>
 *   <li>DevServices starts OpenSearch with gRPC transport enabled (port 9400)</li>
 *   <li>{@code quarkus.dynamic-grpc.service.opensearch-grpc.address} is auto-configured</li>
 *   <li>{@link OpenSearchGrpcClientProducer} can bulk-index documents via gRPC</li>
 *   <li>Documents indexed via gRPC are readable via the REST client</li>
 * </ol>
 * <p>
 * This test uses NO custom test resources — everything is provided by the extension's DevServices.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class GrpcBulkIndexingTest {

    private static final String TEST_INDEX = "grpc-bulk-test-" + UUID.randomUUID().toString().substring(0, 8);
    private static final String DOC_ID = "doc-" + UUID.randomUUID().toString().substring(0, 8);

    @Inject
    OpenSearchGrpcClientProducer grpcClient;

    @Inject
    OpenSearchClient restClient;

    @Test
    @Order(1)
    void grpcClientIsInjectable() {
        assertThat(grpcClient).isNotNull();
    }

    @Test
    @Order(2)
    void createIndexViaRest() throws Exception {
        var resp = restClient.indices().create(c -> c
                .index(TEST_INDEX)
                .settings(s -> s.knn(true)));
        assertThat(resp.acknowledged()).isTrue();
    }

    @Test
    @Order(3)
    void bulkIndexDocumentViaGrpc() {
        String docJson = """
                {"title":"gRPC Test Doc","body":"Indexed via OpenSearch native gRPC transport"}""";

        BulkRequest request = BulkRequest.newBuilder()
                .setIndex(TEST_INDEX)
                .addBulkRequestBody(BulkRequestBody.newBuilder()
                        .setOperationContainer(OperationContainer.newBuilder()
                                .setIndex(IndexOperation.newBuilder()
                                        .setXIndex(TEST_INDEX)
                                        .setXId(DOC_ID)
                                        .build())
                                .build())
                        .setObject(ByteString.copyFromUtf8(docJson))
                        .build())
                .build();

        BulkResponse response = grpcClient.bulk(request)
                .await().atMost(Duration.ofSeconds(30));

        assertThat(response).isNotNull();
        assertThat(response.getErrors()).isFalse();
    }

    @Test
    @Order(4)
    void documentIsReadableViaRest() throws Exception {
        // Refresh so the document is searchable
        restClient.indices().refresh(r -> r.index(TEST_INDEX));

        var getResp = restClient.get(g -> g.index(TEST_INDEX).id(DOC_ID), Map.class);
        assertThat(getResp.found()).isTrue();
        assertThat(getResp.source()).containsEntry("title", "gRPC Test Doc");
    }

    @Test
    @Order(5)
    void cleanupIndex() throws Exception {
        var resp = restClient.indices().delete(d -> d.index(TEST_INDEX));
        assertThat(resp.acknowledged()).isTrue();
    }
}
