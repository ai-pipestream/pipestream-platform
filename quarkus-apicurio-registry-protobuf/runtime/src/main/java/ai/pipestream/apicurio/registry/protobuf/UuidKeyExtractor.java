package ai.pipestream.apicurio.registry.protobuf;

import com.google.protobuf.Message;

import java.util.UUID;

/**
 * Extracts a UUID key from a Protobuf message for Kafka partitioning.
 *
 * <p>
 * Implementations define how to derive a UUID key from a message. Common
 * strategies include:
 * </p>
 * <ul>
 * <li>Extracting an existing UUID field from the message (e.g.,
 * {@code message.getId()}).</li>
 * <li>Generating a new random UUID for each message.</li>
 * <li>Deriving a UUID from message content (e.g., UUIDv5 from a natural
 * key).</li>
 * </ul>
 *
 * <h2>Example Implementation</h2>
 * 
 * <pre>{@code
 * public class OrderEventKeyExtractor implements UuidKeyExtractor<OrderEvent> {
 *     @Override
 *     public UUID extractKey(OrderEvent message) {
 *         // Assuming OrderEvent has a UUID stored as string
 *         return UUID.fromString(message.getOrderId());
 *     }
 * }
 * }</pre>
 *
 * <div class="note">
 * <strong>Note:</strong> This interface is designed to be moved to
 * {@code pipeline-commons}
 * for reuse across the platform. Implementations should be defined in your
 * application
 * or shared libraries.
 * </div>
 *
 * @param <T> the Protobuf message type
 * @see ProtobufKafkaHelper
 */
@FunctionalInterface
public interface UuidKeyExtractor<T extends Message> {

    /**
     * Extracts or generates a UUID key for the given message.
     * <p>
     * The returned UUID is used as the Kafka record key, which determines:
     * <ul>
     * <li>Partition assignment (messages with same key go to same partition)</li>
     * <li>Ordering guarantees (same-key messages are ordered within a
     * partition)</li>
     * <li>Log compaction behavior (latest value per key is retained)</li>
     * </ul>
     *
     * @param message the Protobuf message to extract a key from
     * @return the UUID key for this message (must not be null)
     * @throws IllegalArgumentException if the message cannot produce a valid key
     */
    UUID extractKey(T message);
}
