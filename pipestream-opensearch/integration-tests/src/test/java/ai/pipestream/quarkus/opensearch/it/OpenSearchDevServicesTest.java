package ai.pipestream.quarkus.opensearch.it;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.OpenSearchAsyncClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for OpenSearch DevServices.
 * Verifies that DevServices starts an OpenSearch container and the clients are injectable.
 */
@QuarkusTest
public class OpenSearchDevServicesTest {

    @Inject
    OpenSearchClient client;

    @Inject
    OpenSearchAsyncClient asyncClient;

    @Test
    void testSyncClientIsInjectable() {
        assertThat(client).isNotNull();
    }

    @Test
    void testAsyncClientIsInjectable() {
        assertThat(asyncClient).isNotNull();
    }

    @Test
    void testClusterHealth() throws Exception {
        var health = client.cluster().health();
        assertThat(health).isNotNull();
        assertThat(health.status().jsonValue()).isIn("green", "yellow");
        assertThat(health.clusterName()).isNotBlank();
    }

    @Test
    void testClusterInfo() throws Exception {
        var info = client.info();
        assertThat(info).isNotNull();
        assertThat(info.version()).isNotNull();
        assertThat(info.version().number()).isNotBlank();
        assertThat(info.clusterName()).isNotBlank();
    }

    @Test
    void testIndexOperations() throws Exception {
        String testIndex = "test-devservices-index";

        // Create index
        var createResponse = client.indices().create(c -> c.index(testIndex));
        assertThat(createResponse.acknowledged()).isTrue();

        // Check exists
        var exists = client.indices().exists(e -> e.index(testIndex));
        assertThat(exists.value()).isTrue();

        // Get index settings
        var settings = client.indices().getSettings(s -> s.index(testIndex));
        assertThat(settings.result()).containsKey(testIndex);

        // Delete index
        var deleteResponse = client.indices().delete(d -> d.index(testIndex));
        assertThat(deleteResponse.acknowledged()).isTrue();

        // Verify deleted
        exists = client.indices().exists(e -> e.index(testIndex));
        assertThat(exists.value()).isFalse();
    }
}
