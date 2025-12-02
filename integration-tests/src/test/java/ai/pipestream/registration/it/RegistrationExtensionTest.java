package ai.pipestream.registration.it;

import ai.pipestream.registration.model.ServiceInfo;
import ai.pipestream.registration.model.RegistrationState;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the service registration extension models.
 */
public class RegistrationExtensionTest {

    @Test
    void testServiceInfoBuilder() {
        Map<String, String> metadata = new HashMap<>();
        metadata.put("key1", "value1");
        
        ServiceInfo serviceInfo = ServiceInfo.builder()
                .serviceName("test-service")
                .version("1.0.0")
                .host("localhost")
                .port(8080)
                .metadata(metadata)
                .build();

        assertEquals("test-service", serviceInfo.getServiceName());
        assertEquals("1.0.0", serviceInfo.getVersion());
        assertEquals("localhost", serviceInfo.getHost());
        assertEquals(8080, serviceInfo.getPort());
        assertEquals("value1", serviceInfo.getMetadata().get("key1"));
    }

    @Test
    void testServiceInfoRequiresServiceName() {
        assertThrows(NullPointerException.class, () -> {
            ServiceInfo.builder()
                    .host("localhost")
                    .port(8080)
                    .build();
        });
    }

    @Test
    void testServiceInfoRequiresHost() {
        assertThrows(NullPointerException.class, () -> {
            ServiceInfo.builder()
                    .serviceName("test-service")
                    .port(8080)
                    .build();
        });
    }

    @Test
    void testRegistrationStateValues() {
        assertEquals(6, RegistrationState.values().length);
        assertNotNull(RegistrationState.UNREGISTERED);
        assertNotNull(RegistrationState.REGISTERING);
        assertNotNull(RegistrationState.REGISTERED);
        assertNotNull(RegistrationState.FAILED);
        assertNotNull(RegistrationState.DEREGISTERING);
        assertNotNull(RegistrationState.DEREGISTERED);
    }

    @Test
    void testServiceInfoToString() {
        ServiceInfo serviceInfo = ServiceInfo.builder()
                .serviceName("test-service")
                .version("1.0.0")
                .host("localhost")
                .port(8080)
                .build();

        String toString = serviceInfo.toString();
        assertTrue(toString.contains("test-service"));
        assertTrue(toString.contains("1.0.0"));
        assertTrue(toString.contains("localhost"));
        assertTrue(toString.contains("8080"));
    }

    @Test
    void testServiceInfoEmptyMetadata() {
        ServiceInfo serviceInfo = ServiceInfo.builder()
                .serviceName("test-service")
                .host("localhost")
                .port(8080)
                .build();

        assertNotNull(serviceInfo.getMetadata());
        assertTrue(serviceInfo.getMetadata().isEmpty());
    }
}
