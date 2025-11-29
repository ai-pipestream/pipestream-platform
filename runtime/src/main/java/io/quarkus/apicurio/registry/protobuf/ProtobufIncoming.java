package io.quarkus.apicurio.registry.protobuf;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method as a Kafka consumer for Protobuf messages.
 * <p>
 * <b>EXPERIMENTAL:</b> This annotation's transformation to {@code @Incoming} is not fully
 * functional due to how SmallRye Reactive Messaging scans annotations at build time.
 * For now, use the standard {@code @Incoming} annotation with Protobuf message types -
 * the extension will auto-detect them and configure the deserializer.
 * <p>
 * This annotation is intended to combine the functionality of {@code @Incoming} with automatic
 * configuration for Protobuf deserialization via Apicurio Registry.
 * <p>
 * <b>Recommended approach:</b> Use standard annotations:
 * <pre>{@code
 * @Incoming("orders")  // Extension auto-detects Protobuf types
 * public void processOrder(OrderProto order) {
 *     // Process the protobuf message
 * }
 * }</pre>
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
