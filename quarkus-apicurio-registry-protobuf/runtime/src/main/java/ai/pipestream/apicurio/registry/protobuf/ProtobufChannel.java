package ai.pipestream.apicurio.registry.protobuf;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a field or parameter for injection of a Kafka Protobuf channel
 * (Emitter).
 *
 * <div class="warning">
 * <strong>EXPERIMENTAL:</strong> This annotation's transformation to
 * {@code @Channel} is currently limited
 * due to build-time scanning constraints in SmallRye Reactive Messaging.
 * </div>
 *
 * <p>
 * This annotation is intended to combine the functionality of {@code @Channel}
 * with automatic
 * configuration for Protobuf serialization via Apicurio Registry.
 * </p>
 *
 * <h2>Recommended Approach</h2>
 * <p>
 * It is currently recommended to use the standard {@code @Channel} annotation
 * with Protobuf message types.
 * The extension will automatically detect the Protobuf types and configure the
 * serializer.
 * </p>
 *
 * <pre>{@code
 * @Inject
 * @Channel("order-events") // Extension auto-detects Protobuf types
 * Emitter<OrderEventProto> orderEmitter;
 *
 * public void sendOrder(OrderEventProto event) {
 *     orderEmitter.send(event);
 * }
 * }</pre>
 *
 * <h3>Required Configuration</h3>
 * <p>
 * To ensure correct operation, the following properties must be set in
 * {@code application.properties}:
 * </p>
 * <ul>
 * <li>{@code mp.messaging.connector.smallrye-kafka.apicurio.registry.url} - The
 * URL of the Apicurio Registry.</li>
 * <li>{@code kafka.bootstrap.servers} - The Kafka bootstrap servers.</li>
 * </ul>
 *
 * @see ProtobufIncoming
 * @see ProtobufOutgoing
 */
@Target({ ElementType.FIELD, ElementType.PARAMETER })
@Retention(RetentionPolicy.RUNTIME)
public @interface ProtobufChannel {

    /**
     * The name of the channel (topic).
     * This will be used as the value for {@code @Channel}.
     *
     * @return the channel name
     */
    String value();
}
