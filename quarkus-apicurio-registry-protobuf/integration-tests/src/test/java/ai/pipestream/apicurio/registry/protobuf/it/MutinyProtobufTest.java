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
 * Integration test for Mutiny-based Protobuf serialization/deserialization.
 * <p>
 * This test verifies:
 * 1. @Outgoing with Multi<TestRecord> works with auto-configured serializer
 * 2. @Incoming with Uni<Void> return works with auto-configured deserializer
 * 3. Reactive streaming of Protobuf messages through Kafka
 */
@QuarkusTest
class MutinyProtobufTest {

    @Inject
    MutinyProducer producer;

    @Inject
    MutinyConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer.clear();
    }

    @Test
    void testMutinyProtobufRoundTrip() {
        // Given
        String testId = UUID.randomUUID().toString();
        String testName = "Mutiny Test " + System.currentTimeMillis();

        TestRecord record = TestRecord.newBuilder()
                .setId(testId)
                .setName(testName)
                .setTimestamp(System.currentTimeMillis())
                .putMetadata("pattern", "mutiny")
                .build();

        // When - send via reactive Multi
        producer.send(record);

        // Then - wait for message via reactive Uni consumer
        Awaitility.await()
                .atMost(30, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .until(() -> !consumer.getReceived().isEmpty());

        // Verify the received message
        TestRecord received = consumer.getReceived().getFirst();
        assertNotNull(received);
        assertEquals(testId, received.getId());
        assertEquals(testName, received.getName());
        assertEquals("mutiny", received.getMetadataOrDefault("pattern", ""));
    }

    @Test
    void testMutinyMultipleMessages() {
        // Send multiple messages via reactive stream
        for (int i = 0; i < 5; i++) {
            producer.send("Mutiny Message " + i);
        }

        // Wait for all messages
        Awaitility.await()
                .atMost(30, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .until(() -> consumer.getReceived().size() >= 5);

        assertEquals(5, consumer.getReceived().size());

        // Verify all have mutiny source metadata
        consumer.getReceived().forEach(record ->
                assertEquals("mutiny-producer", record.getMetadataOrDefault("source", "")));
    }
}
