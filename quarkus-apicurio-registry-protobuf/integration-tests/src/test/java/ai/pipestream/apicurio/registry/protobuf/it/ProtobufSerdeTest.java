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
 * Integration test for Protobuf serialization/deserialization with Apicurio Registry.
 * <p>
 * This test verifies:
 * 1. TestRecord can be serialized and sent to Kafka
 * 2. Messages are registered in Apicurio Registry
 * 3. Messages can be deserialized back to TestRecord (derive.class=true derives from schema)
 * 4. Field values are preserved through round-trip
 */
@QuarkusTest
class ProtobufSerdeTest {

    @Inject
    TestRecordProducer producer;

    @Inject
    TestRecordConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer.clear();
    }

    @Test
    void testProtobufRoundTrip() {
        // Given
        String testId = UUID.randomUUID().toString();
        String testName = "Test Record " + System.currentTimeMillis();

        TestRecord record = TestRecord.newBuilder()
                .setId(testId)
                .setName(testName)
                .setTimestamp(System.currentTimeMillis())
                .putMetadata("key1", "value1")
                .build();

        // When
        producer.send(record);

        // Then - wait for message to be received
        Awaitility.await()
                .atMost(30, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .until(() -> !consumer.getReceived().isEmpty());

        // Verify the received message - it's the actual TestRecord type
        TestRecord received = consumer.getReceived().getFirst();
        assertNotNull(received);

        assertEquals(testId, received.getId());
        assertEquals(testName, received.getName());
        assertEquals("value1", received.getMetadataOrDefault("key1", ""));
    }

    @Test
    void testMultipleMessages() {
        // Send multiple messages
        for (int i = 0; i < 5; i++) {
            producer.send("Message " + i);
        }

        // Wait for all messages
        Awaitility.await()
                .atMost(30, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .until(() -> consumer.getReceived().size() >= 5);

        assertEquals(5, consumer.getReceived().size());
    }
}
