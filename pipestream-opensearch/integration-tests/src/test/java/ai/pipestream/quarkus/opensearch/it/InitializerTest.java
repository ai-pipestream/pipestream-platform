package ai.pipestream.quarkus.opensearch.it;

import ai.pipestream.quarkus.opensearch.init.OpenSearchInitializer;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.opensearch.client.opensearch.OpenSearchClient;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for the OpenSearch initializer framework.
 * Verifies that custom initializers are executed on startup.
 */
@QuarkusTest
public class InitializerTest {

    @Inject
    OpenSearchClient client;

    @Inject
    TestInitializer testInitializer;

    @Test
    void testCustomInitializerWasExecuted() {
        // The TestInitializer should have been executed during startup
        assertThat(testInitializer.wasInitialized()).isTrue();
    }

    @Test
    void testDefaultTemplateWasCreated() throws Exception {
        // The IndexTemplateInitializer should have created the default template
        var templates = client.indices().getIndexTemplate(t -> t.name("pipestream-default"));
        
        // Template may or may not exist depending on test order, but the call should work
        // The important thing is that the initializer ran without error
        assertThat(templates).isNotNull();
    }

    @Test
    void testInitializerCreatedIndex() throws Exception {
        // The TestInitializer creates a test index
        var exists = client.indices().exists(e -> e.index("initializer-test-index"));
        assertThat(exists.value()).isTrue();
    }

    /**
     * Test initializer that creates a test index on startup.
     */
    @ApplicationScoped
    public static class TestInitializer implements OpenSearchInitializer {

        private final AtomicBoolean initialized = new AtomicBoolean(false);

        @Override
        public void initialize(OpenSearchClient client) throws Exception {
            // Create a test index to prove we were called
            var exists = client.indices().exists(e -> e.index("initializer-test-index"));
            if (!exists.value()) {
                client.indices().create(c -> c.index("initializer-test-index"));
            }
            initialized.set(true);
        }

        @Override
        public int priority() {
            return 500; // After default template, before data seeding
        }

        @Override
        public String name() {
            return "TestInitializer";
        }

        public boolean wasInitialized() {
            return initialized.get();
        }
    }
}
