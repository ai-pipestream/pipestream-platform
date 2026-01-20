package ai.pipestream.test.support;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test for real KMS (Infisical) test resource.
 * This test starts actual Redis + Infisical containers for full integration testing.
 * Only runs when explicitly enabled due to slower startup time.
 *
 * Set system property to enable: -Dtest.kms.integration=true
 */
@EnabledIfSystemProperty(named = "test.kms.integration", matches = "true")
class KmsIntegrationTestResourceTest {

    @Test
    void kmsIntegrationResourceProvidesBasicConfig() {
        KmsTestResource resource = new KmsTestResource();
        Map<String, String> config = resource.start();

        try {
            // Basic smoke test - verify the resource starts and provides config
            assertNotNull(config.get("kms.api.url"));
            assertNotNull(config.get("kms.api.token"));

            // URL should be accessible
            String apiUrl = config.get("kms.api.url");
            assertNotNull(apiUrl);
            assertTrue(apiUrl.startsWith("http://"));

        } finally {
            resource.stop();
        }
    }
}