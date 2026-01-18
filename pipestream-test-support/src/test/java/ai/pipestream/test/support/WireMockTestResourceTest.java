package ai.pipestream.test.support;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WireMockTestResourceTest {

    @Test
    void startsWireMockAndProvidesRegistrationConfig() {
        WireMockTestResource resource = new WireMockTestResource();
        Map<String, String> config = resource.start();

        try {
            assertNotNull(config.get("pipestream.registration.registration-service.host"));
            assertTrue(config.get("pipestream.registration.registration-service.port").matches("\\d+"));
        } finally {
            resource.stop();
        }
    }
}
