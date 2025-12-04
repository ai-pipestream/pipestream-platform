package ai.pipestream.apicurio.registry.protobuf;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method as a Kafka producer for Protobuf messages.
 *
 * <div class="warning">
 * <strong>EXPERIMENTAL:</strong> This annotation's transformation to
 * {@code @Outgoing} is currently limited
 * due to build-time scanning constraints in SmallRye Reactive Messaging.
 * </div>
 *
 * <p>
 * This annotation is intended to combine the functionality of {@code @Outgoing}
 * with automatic
 * configuration for Protobuf serialization via Apicurio Registry.
 * </p>
 *
 * <h2>Recommended Approach</h2>
 * <p>
 * It is currently recommended to use the standard {@code @Outgoing} annotation
 * with Protobuf message types.
 * The extension will automatically detect the Protobuf types and configure the
 * serializer.
 * </p>
 *
 * <pre>{@code
 * &#64;Outgoing("order-events")  // Extension auto-detects Protobuf types
 * public Multi<OrderEventProto> produceEvents() {
 *     return Multi.createFrom().items(...);
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
 * @see ProtobufChannel
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ProtobufOutgoing {

    /**
     * The name of the channel (topic).
     * This will be used as the value for {@code @Outgoing}.
     *
     * @return the channel name
     */
    String value();
}
