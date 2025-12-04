package ai.pipestream.apicurio.registry.protobuf.it;

import ai.pipestream.apicurio.registry.protobuf.ProtobufKafkaHelper;
import ai.pipestream.apicurio.registry.protobuf.it.proto.TestRecord;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;

import java.util.UUID;

/**
 * Producer demonstrating UUID key usage with Protobuf messages.
 * <p>
 * Shows three patterns:
 * <ul>
 *   <li>Explicit UUID key</li>
 *   <li>Key extracted from message via {@link TestRecordUuidKeyExtractor}</li>
 *   <li>Inline lambda extractor</li>
 * </ul>
 */
@ApplicationScoped
public class UuidKeyProducer {

    @Inject
    @Channel("uuid-key-out")                         // (1) Standard SmallRye channel
    Emitter<TestRecord> emitter;

    @Inject
    ProtobufKafkaHelper kafka;                       // (2) CDI service for sending

    @Inject
    TestRecordUuidKeyExtractor keyExtractor;         // (3) Reusable key extractor

    /**
     * Sends a message with an explicit UUID key.
     * Use when you have the UUID from an external source.
     */
    public void sendWithExplicitKey(UUID key, TestRecord record) {
        kafka.send(emitter, key, record);            // (4) Service with explicit key
    }

    /**
     * Sends a message with key extracted via injected extractor.
     * Use for consistent key extraction across your service.
     */
    public void sendWithExtractor(TestRecord record) {
        kafka.send(emitter, record, keyExtractor);   // (5) Service with extractor
    }

    /**
     * Sends a message with inline key extraction.
     * Use for one-off cases or simple extractions.
     */
    public void sendWithInlineExtractor(TestRecord record) {
        kafka.send(emitter, record,
                r -> UUID.fromString(r.getId()));    // (6) Inline lambda extractor
    }
}
