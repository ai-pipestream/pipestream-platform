package io.quarkus.apicurio.registry.protobuf;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a field or parameter for injection of a Kafka Protobuf channel (Emitter).
 * <p>
 * <b>EXPERIMENTAL:</b> This annotation's transformation to {@code @Channel} is not fully
 * functional due to how SmallRye Reactive Messaging scans annotations at build time.
 * For now, use the standard {@code @Channel} annotation with Protobuf message types -
 * the extension will auto-detect them and configure the serializer.
 * <p>
 * This annotation is intended to combine the functionality of {@code @Channel} with automatic
 * configuration for Protobuf serialization via Apicurio Registry.
 * <p>
 * <b>Recommended approach:</b> Use standard annotations:
 * <pre>{@code
 * @Inject
 * @Channel("order-events")  // Extension auto-detects Protobuf types
 * Emitter<OrderEventProto> orderEmitter;
 *
 * public void sendOrder(OrderEventProto event) {
 *     orderEmitter.send(event);
 * }
 * }</pre>
 *
 * @see ProtobufIncoming
 * @see ProtobufOutgoing
 */
@Target({ElementType.FIELD, ElementType.PARAMETER})
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
