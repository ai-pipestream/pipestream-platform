package ai.pipestream.quarkus.opensearch.it;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.*;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.core.IndexRequest;
import org.opensearch.client.opensearch.core.SearchRequest;
import org.opensearch.client.opensearch._types.Refresh;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for document operations.
 * Verifies indexing, searching, and deleting documents.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class DocumentOperationsTest {

    private static final String TEST_INDEX = "test-documents-index";

    @Inject
    OpenSearchClient client;

    @BeforeAll
    void setupIndex() throws Exception {
        // Create test index if not exists
        var exists = client.indices().exists(e -> e.index(TEST_INDEX));
        if (!exists.value()) {
            client.indices().create(c -> c.index(TEST_INDEX));
        }
    }

    @AfterAll
    void cleanupIndex() throws Exception {
        // Delete test index
        var exists = client.indices().exists(e -> e.index(TEST_INDEX));
        if (exists.value()) {
            client.indices().delete(d -> d.index(TEST_INDEX));
        }
    }

    @Test
    @Order(1)
    void testIndexDocument() throws Exception {
        var document = Map.of(
                "title", "Test Document",
                "content", "This is a test document for integration testing",
                "tags", java.util.List.of("test", "integration")
        );

        var response = client.index(IndexRequest.of(i -> i
                .index(TEST_INDEX)
                .id("doc-1")
                .document(document)
                .refresh(Refresh.True)
        ));

        assertThat(response.result().jsonValue()).isIn("created", "updated");
        assertThat(response.id()).isEqualTo("doc-1");
    }

    @Test
    @Order(2)
    void testGetDocument() throws Exception {
        var response = client.get(g -> g
                .index(TEST_INDEX)
                .id("doc-1"),
                Map.class
        );

        assertThat(response.found()).isTrue();
        assertThat(response.id()).isEqualTo("doc-1");
        assertThat(response.source()).isNotNull();
        assertThat(response.source().get("title")).isEqualTo("Test Document");
    }

    @Test
    @Order(3)
    void testSearchDocuments() throws Exception {
        var response = client.search(SearchRequest.of(s -> s
                .index(TEST_INDEX)
                .query(q -> q
                        .match(m -> m
                                .field("content")
                                .query(fv -> fv.stringValue("integration"))
                        )
                )
        ), Map.class);

        assertThat(response.hits().total()).isNotNull();
        assertThat(response.hits().total().value()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @Order(4)
    void testBulkIndexDocuments() throws Exception {
        var bulkResponse = client.bulk(b -> b
                .index(TEST_INDEX)
                .operations(op -> op
                        .index(i -> i
                                .id("doc-2")
                                .document(Map.of("title", "Bulk Doc 1", "content", "First bulk document"))
                        )
                )
                .operations(op -> op
                        .index(i -> i
                                .id("doc-3")
                                .document(Map.of("title", "Bulk Doc 2", "content", "Second bulk document"))
                        )
                )
                .refresh(Refresh.True)
        );

        assertThat(bulkResponse.errors()).isFalse();
        assertThat(bulkResponse.items()).hasSize(2);
    }

    @Test
    @Order(5)
    void testDeleteDocument() throws Exception {
        var response = client.delete(d -> d
                .index(TEST_INDEX)
                .id("doc-1")
        );

        assertThat(response.result().jsonValue()).isIn("deleted", "not_found");
    }
}
