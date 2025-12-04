package ai.pipestream.apicurio.registry.protobuf.it;

import ai.pipestream.apicurio.registry.protobuf.it.proto.TestRecord;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for UUID key handling with Protobuf messages.
 * <p>
 * Verifies:
 * <ul>
 *   <li>Messages are sent with UUID keys</li>
 *   <li>UUID keys are preserved through Kafka round-trip</li>
 *   <li>Consumers can access the UUID key via metadata</li>
 *   <li>Key extractor pattern works correctly</li>
 * </ul>
 */
@QuarkusTest
class UuidKeyTest {

    @Inject
    UuidKeyProducer producer;

    @Inject
    UuidKeyConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer.clear();
    }

    @Test
    void testExplicitUuidKey() {
        // Given - a known UUID key
        UUID expectedKey = UUID.randomUUID();
        String recordId = expectedKey.toString();  // Use same value for record ID

        TestRecord record = TestRecord.newBuilder()
                .setId(recordId)
                .setName("Explicit Key Test")
                .setTimestamp(System.currentTimeMillis())
                .build();

        // When - send with explicit key
        producer.sendWithExplicitKey(expectedKey, record);

        // Then - verify key is preserved
        Awaitility.await()
                .atMost(30, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .until(() -> consumer.getByKey(expectedKey) != null);

        TestRecord received = consumer.getByKey(expectedKey);
        assertNotNull(received);
        assertEquals(recordId, received.getId());
        assertEquals("Explicit Key Test", received.getName());
    }

    @Test
    void testKeyExtractor() {
        // Given - a record with UUID in its id field
        UUID expectedKey = UUID.randomUUID();

        TestRecord record = TestRecord.newBuilder()
                .setId(expectedKey.toString())  // Key will be extracted from this
                .setName("Extractor Test")
                .setTimestamp(System.currentTimeMillis())
                .build();

        // When - send with key extractor
        producer.sendWithExtractor(record);

        // Then - verify key was extracted and preserved
        Awaitility.await()
                .atMost(30, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .until(() -> consumer.getByKey(expectedKey) != null);

        TestRecord received = consumer.getByKey(expectedKey);
        assertNotNull(received);
        assertEquals(expectedKey.toString(), received.getId());
        assertEquals("Extractor Test", received.getName());
    }

    @Test
    void testInlineExtractor() {
        // Given - a record with UUID in its id field
        UUID expectedKey = UUID.randomUUID();

        TestRecord record = TestRecord.newBuilder()
                .setId(expectedKey.toString())
                .setName("Inline Extractor Test")
                .setTimestamp(System.currentTimeMillis())
                .build();

        // When - send with inline lambda extractor
        producer.sendWithInlineExtractor(record);

        // Then - verify key was extracted and preserved
        Awaitility.await()
                .atMost(30, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .until(() -> consumer.getByKey(expectedKey) != null);

        TestRecord received = consumer.getByKey(expectedKey);
        assertNotNull(received);
        assertEquals("Inline Extractor Test", received.getName());
    }

    @Test
    void testMultipleMessagesWithDifferentKeys() {
        // Given - multiple records with different keys
        UUID key1 = UUID.randomUUID();
        UUID key2 = UUID.randomUUID();
        UUID key3 = UUID.randomUUID();

        TestRecord record1 = TestRecord.newBuilder()
                .setId(key1.toString())
                .setName("Record 1")
                .setTimestamp(System.currentTimeMillis())
                .build();

        TestRecord record2 = TestRecord.newBuilder()
                .setId(key2.toString())
                .setName("Record 2")
                .setTimestamp(System.currentTimeMillis())
                .build();

        TestRecord record3 = TestRecord.newBuilder()
                .setId(key3.toString())
                .setName("Record 3")
                .setTimestamp(System.currentTimeMillis())
                .build();

        // When - send all with extracted keys
        producer.sendWithExtractor(record1);
        producer.sendWithExtractor(record2);
        producer.sendWithExtractor(record3);

        // Then - verify all received with correct keys
        Awaitility.await()
                .atMost(30, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .until(() -> consumer.getReceivedByKey().size() >= 3);

        assertEquals("Record 1", consumer.getByKey(key1).getName());
        assertEquals("Record 2", consumer.getByKey(key2).getName());
        assertEquals("Record 3", consumer.getByKey(key3).getName());
    }
}
