package ai.pipestream.apicurio.registry.protobuf;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method as a Kafka consumer for Protobuf messages.
 *
 * <div class="warning">
 * <strong>EXPERIMENTAL:</strong> This annotation's transformation to
 * {@code @Incoming} is currently limited
 * due to build-time scanning constraints in SmallRye Reactive Messaging.
 * </div>
 *
 * <p>
 * This annotation is intended to combine the functionality of {@code @Incoming}
 * with automatic
 * configuration for Protobuf deserialization via Apicurio Registry.
 * </p>
 *
 * <h2>Recommended Approach</h2>
 * <p>
 * It is currently recommended to use the standard {@code @Incoming} annotation
 * with Protobuf message types.
 * The extension will automatically detect the Protobuf types and configure the
 * deserializer.
 * </p>
 *
 * <pre>{@code
 * @Incoming("orders") // Extension auto-detects Protobuf types
 * public void processOrder(OrderProto order) {
 *     // Process the protobuf message
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
 * @see ProtobufOutgoing
 * @see ProtobufChannel
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ProtobufIncoming {

    /**
     * The name of the channel (topic).
     * This will be used as the value for {@code @Incoming}.
     *
     * @return the channel name
     */
    String value();
}
