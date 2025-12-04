package ai.pipestream.apicurio.registry.protobuf.it;

import ai.pipestream.apicurio.registry.protobuf.it.proto.TestRecord;
import io.smallrye.reactive.messaging.kafka.api.IncomingKafkaRecordMetadata;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Consumer demonstrating UUID key access with Protobuf messages.
 * <p>
 * Shows how to receive messages and access the UUID partition key.
 */
@ApplicationScoped
public class UuidKeyConsumer {

    private static final Logger LOG = LoggerFactory.getLogger(UuidKeyConsumer.class);

    // Store received messages with their keys for testing
    private final Map<UUID, TestRecord> receivedByKey = new ConcurrentHashMap<>();

    /**
     * Receives messages with access to the UUID key via metadata.
     * <p>
     * Uses {@link Message} wrapper to access Kafka metadata including the key.
     *
     * @param message the received message wrapper
     * @return a completion stage indicating message processing is complete
     */
    @Incoming("uuid-key-in") // (1) Channel name
    @SuppressWarnings("unchecked") // Raw type from getMetadata is safe - types erased at runtime
    public CompletionStage<Void> consume(Message<TestRecord> message) {
        TestRecord record = message.getPayload(); // (2) Get Protobuf payload

        // Access Kafka metadata to get the UUID key
        IncomingKafkaRecordMetadata<UUID, TestRecord> metadata = // (3) Get Kafka metadata
                message.getMetadata(IncomingKafkaRecordMetadata.class)
                        .orElse(null);

        UUID key = null;
        if (metadata != null) {
            key = metadata.getKey(); // (4) Extract UUID key
            LOG.info("Received message: key={}, id={}, name={}",
                    key, record.getId(), record.getName());
        } else {
            LOG.warn("Received message without Kafka metadata: id={}", record.getId());
        }

        receivedByKey.put(key != null ? key : UUID.randomUUID(), record);

        return message.ack(); // (5) Acknowledge message
    }

    /**
     * Returns all received messages indexed by their UUID key.
     *
     * @return a map of received messages keyed by UUID
     */
    public Map<UUID, TestRecord> getReceivedByKey() {
        return receivedByKey;
    }

    /**
     * Gets a specific message by its UUID key.
     *
     * @param key the UUID key to look up
     * @return the message associated with the key, or null if not found
     */
    public TestRecord getByKey(UUID key) {
        return receivedByKey.get(key);
    }

    /**
     * Clears the received messages.
     */
    public void clear() {
        receivedByKey.clear();
    }
}
