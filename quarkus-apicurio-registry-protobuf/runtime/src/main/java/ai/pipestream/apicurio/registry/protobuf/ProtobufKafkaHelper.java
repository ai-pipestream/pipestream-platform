package ai.pipestream.apicurio.registry.protobuf;

import com.google.protobuf.Message;
import io.smallrye.reactive.messaging.kafka.api.OutgoingKafkaRecordMetadata;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.reactive.messaging.Emitter;

import java.util.UUID;

/**
 * CDI service for sending Protobuf messages to Kafka with UUID keys.
 *
 * <p>
 * This extension enforces UUID keys for all Kafka messages. This service
 * provides
 * convenient methods for sending messages with proper key metadata.
 * </p>
 *
 * <h2>Usage</h2>
 * 
 * <pre>
 * {@code
 * &#64;Inject
 * ProtobufKafkaHelper kafka;
 *
 * &#64;Inject
 * &#64;Channel("orders-out")
 * Emitter<OrderEvent> emitter;
 *
 * @Inject
 * OrderEventKeyExtractor keyExtractor;
 *
 * // With explicit UUID
 * public void sendOrder(OrderEvent order, UUID orderId) {
 *     kafka.send(emitter, orderId, order);
 * }
 *
 * // With key extractor
 * public void sendOrder(OrderEvent order) {
 *     kafka.send(emitter, order, keyExtractor);
 * }
 * }
 * </pre>
 *
 * @see UuidKeyExtractor
 */
@ApplicationScoped
public class ProtobufKafkaHelper {

    /**
     * Default constructor.
     */
    public ProtobufKafkaHelper() {
    }

    /**
     * Sends a Protobuf message with an explicit UUID key.
     * <p>
     * Use this when you already have the UUID (e.g., from a database,
     * request context, or external system).
     *
     * @param emitter the SmallRye emitter (injected with @Channel)
     * @param key     the UUID key for partitioning
     * @param message the Protobuf message to send
     * @param <T>     the Protobuf message type
     */
    public <T extends Message> void send(Emitter<T> emitter, UUID key, T message) {
        OutgoingKafkaRecordMetadata<UUID> metadata = OutgoingKafkaRecordMetadata.<UUID>builder()
                .withKey(key)
                .build();
        emitter.send(org.eclipse.microprofile.reactive.messaging.Message.of(message)
                .addMetadata(metadata));
    }

    /**
     * Sends a Protobuf message with a key extracted from the message.
     * <p>
     * Use this with a {@link UuidKeyExtractor} implementation that derives
     * the key from the message content.
     *
     * @param emitter      the SmallRye emitter (injected with @Channel)
     * @param message      the Protobuf message to send
     * @param keyExtractor extracts the UUID key from the message
     * @param <T>          the Protobuf message type
     */
    public <T extends Message> void send(Emitter<T> emitter, T message, UuidKeyExtractor<T> keyExtractor) {
        UUID key = keyExtractor.extractKey(message);
        send(emitter, key, message);
    }
}
