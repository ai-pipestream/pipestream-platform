package ai.pipestream.quarkus.opensearch.it;

import ai.pipestream.quarkus.opensearch.client.ReactiveOpenSearchClient;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for ReactiveOpenSearchClient.
 * Verifies that the Mutiny-based reactive wrapper works correctly.
 */
@QuarkusTest
public class ReactiveClientTest {

    @Inject
    ReactiveOpenSearchClient reactiveClient;

    @Test
    void testReactiveClientIsInjectable() {
        assertThat(reactiveClient).isNotNull();
    }

    @Test
    void testReactiveClusterHealth() {
        var health = reactiveClient.getClusterHealth()
                .await().atMost(Duration.ofSeconds(30));

        assertThat(health).isNotNull();
        assertThat(health.status().jsonValue()).isIn("green", "yellow");
    }

    @Test
    void testReactiveIndexOperations() {
        String testIndex = "test-reactive-index";

        // Initially should not exist
        var existsBefore = reactiveClient.indexExists(testIndex)
                .await().atMost(Duration.ofSeconds(10));
        
        // Create only if doesn't exist
        if (!existsBefore) {
            var created = reactiveClient.createIndex(testIndex)
                    .await().atMost(Duration.ofSeconds(10));
            assertThat(created).isTrue();
        }

        // Should exist now
        var existsAfter = reactiveClient.indexExists(testIndex)
                .await().atMost(Duration.ofSeconds(10));
        assertThat(existsAfter).isTrue();

        // Cleanup
        reactiveClient.deleteIndex(testIndex)
                .await().atMost(Duration.ofSeconds(10));
    }

    @Test
    void testReactiveIndexWithSettings() {
        String testIndex = "test-reactive-settings-index";

        // Create index with custom settings
        var created = reactiveClient.createIndex(testIndex, settings -> settings
                .numberOfShards(1)
                .numberOfReplicas(0)
        ).await().atMost(Duration.ofSeconds(10));

        assertThat(created).isTrue();

        // Cleanup
        reactiveClient.deleteIndex(testIndex)
                .await().atMost(Duration.ofSeconds(10));
    }
}
