package ai.pipestream.registration.it;

import ai.pipestream.platform.registration.v1.ServiceType;
import ai.pipestream.registration.model.ServiceInfo;
import ai.pipestream.registration.model.RegistrationState;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
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
                .name("test-service")
                .type(ServiceType.SERVICE_TYPE_SERVICE)
                .version("1.0.0")
                .advertisedHost("localhost")
                .advertisedPort(8080)
                .tlsEnabled(false)
                .metadata(metadata)
                .build();

        assertEquals("test-service", serviceInfo.getName());
        assertEquals(ServiceType.SERVICE_TYPE_SERVICE, serviceInfo.getType());
        assertEquals("1.0.0", serviceInfo.getVersion());
        assertEquals("localhost", serviceInfo.getAdvertisedHost());
        assertEquals(8080, serviceInfo.getAdvertisedPort());
        assertFalse(serviceInfo.isTlsEnabled());
        assertEquals("value1", serviceInfo.getMetadata().get("key1"));
    }

    @Test
    void testServiceInfoRequiresName() {
        assertThrows(NullPointerException.class, () -> {
            ServiceInfo.builder()
                    .type(ServiceType.SERVICE_TYPE_SERVICE)
                    .advertisedHost("localhost")
                    .advertisedPort(8080)
                    .build();
        });
    }

    @Test
    void testServiceInfoRequiresAdvertisedHost() {
        assertThrows(NullPointerException.class, () -> {
            ServiceInfo.builder()
                    .name("test-service")
                    .type(ServiceType.SERVICE_TYPE_SERVICE)
                    .advertisedPort(8080)
                    .build();
        });
    }

    @Test
    void testServiceInfoWithInternalAddresses() {
        ServiceInfo serviceInfo = ServiceInfo.builder()
                .name("test-service")
                .type(ServiceType.SERVICE_TYPE_MODULE)
                .advertisedHost("external.example.com")
                .advertisedPort(9000)
                .internalHost("0.0.0.0")
                .internalPort(9000)
                .tlsEnabled(true)
                .build();

        assertEquals("external.example.com", serviceInfo.getAdvertisedHost());
        assertEquals(9000, serviceInfo.getAdvertisedPort());
        assertEquals("0.0.0.0", serviceInfo.getInternalHost());
        assertEquals(9000, serviceInfo.getInternalPort());
        assertTrue(serviceInfo.isTlsEnabled());
        assertEquals(ServiceType.SERVICE_TYPE_MODULE, serviceInfo.getType());
    }

    @Test
    void testServiceInfoWithTagsAndCapabilities() {
        List<String> tags = List.of("production", "critical");
        List<String> capabilities = List.of("parse-pdf", "extract-text");

        ServiceInfo serviceInfo = ServiceInfo.builder()
                .name("test-module")
                .type(ServiceType.SERVICE_TYPE_MODULE)
                .advertisedHost("localhost")
                .advertisedPort(8080)
                .tags(tags)
                .capabilities(capabilities)
                .build();

        assertEquals(2, serviceInfo.getTags().size());
        assertTrue(serviceInfo.getTags().contains("production"));
        assertTrue(serviceInfo.getTags().contains("critical"));

        assertEquals(2, serviceInfo.getCapabilities().size());
        assertTrue(serviceInfo.getCapabilities().contains("parse-pdf"));
        assertTrue(serviceInfo.getCapabilities().contains("extract-text"));
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
                .name("test-service")
                .type(ServiceType.SERVICE_TYPE_SERVICE)
                .version("1.0.0")
                .advertisedHost("localhost")
                .advertisedPort(8080)
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
                .name("test-service")
                .type(ServiceType.SERVICE_TYPE_SERVICE)
                .advertisedHost("localhost")
                .advertisedPort(8080)
                .build();

        assertNotNull(serviceInfo.getMetadata());
        assertTrue(serviceInfo.getMetadata().isEmpty());
    }
}
