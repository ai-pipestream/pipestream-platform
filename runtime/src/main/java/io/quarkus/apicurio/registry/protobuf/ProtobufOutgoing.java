package io.quarkus.apicurio.registry.protobuf;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method as a Kafka producer for Protobuf messages.
 * <p>
 * <b>EXPERIMENTAL:</b> This annotation's transformation to {@code @Outgoing} is not fully
 * functional due to how SmallRye Reactive Messaging scans annotations at build time.
 * For now, use the standard {@code @Outgoing} annotation with Protobuf message types -
 * the extension will auto-detect them and configure the serializer.
 * <p>
 * This annotation is intended to combine the functionality of {@code @Outgoing} with automatic
 * configuration for Protobuf serialization via Apicurio Registry.
 * <p>
 * <b>Recommended approach:</b> Use standard annotations:
 * <pre>{@code
 * @Outgoing("order-events")  // Extension auto-detects Protobuf types
 * public Multi<OrderEventProto> produceEvents() {
 *     return Multi.createFrom().items(...);
 * }
 * }</pre>
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
