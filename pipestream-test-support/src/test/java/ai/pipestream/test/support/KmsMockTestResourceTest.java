package ai.pipestream.test.support;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Test for KMS WireMock test resource.
 * This is much faster than the real Infisical container and avoids conflicts.
 */
class KmsMockTestResourceTest {

    @Test
    void kmsMockResourceProvidesBasicConfig() {
        KmsMockTestResource resource = new KmsMockTestResource();
        Map<String, String> config = resource.start();

        try {
            // Basic smoke test - just verify the resource starts and provides config
            assertNotNull(config.get("kms.api.url"));
            assertNotNull(config.get("kms.api.token"));
            assertNotNull(config.get("wiremock.kms.host"));
            assertNotNull(config.get("wiremock.kms.port"));
        } finally {
            resource.stop();
        }
    }
}