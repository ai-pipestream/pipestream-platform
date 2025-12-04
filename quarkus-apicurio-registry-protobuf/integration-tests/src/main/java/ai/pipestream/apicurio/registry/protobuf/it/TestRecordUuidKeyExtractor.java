package ai.pipestream.apicurio.registry.protobuf.it;

import ai.pipestream.apicurio.registry.protobuf.UuidKeyExtractor;
import ai.pipestream.apicurio.registry.protobuf.it.proto.TestRecord;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.UUID;

/**
 * Sample implementation of {@link UuidKeyExtractor} for TestRecord messages.
 * <p>
 * This extractor parses the UUID from the TestRecord's {@code id} field,
 * which is stored as a string in the Protobuf message.
 * <p>
 * <b>Usage:</b>
 * 
 * <pre>
 * {
 *     &#64;code
 *     &#64;Inject
 *     TestRecordUuidKeyExtractor keyExtractor;
 *
 *     &#64;Inject
 *     ProtobufEmitterFactory factory;
 *
 *     @Inject
 *     &#64;Channel("test-out")
 *     Emitter<TestRecord> rawEmitter;
 * <p>
 *     // Create emitter with default key extractor
 *     ProtobufEmitter<TestRecord> emitter = factory.wrap(rawEmitter, "test-out", keyExtractor);
 * <p>
 *     // Messages automatically use the id field as the partition key
 *     emitter.send(testRecord);
 * }
 * </pre>
 * <p>
 * <b>Note:</b> In a real application, this class would typically be defined
 * in {@code pipeline-commons} and reused across services.
 */
@SuppressWarnings("JavadocDeclaration")
@ApplicationScoped
public class TestRecordUuidKeyExtractor implements UuidKeyExtractor<TestRecord> {

    /**
     * Default constructor.
     */
    public TestRecordUuidKeyExtractor() {
    }

    /**
     * Extracts the UUID key from the TestRecord's id field.
     *
     * @param message the TestRecord to extract the key from
     * @return the UUID parsed from the message's id field
     * @throws IllegalArgumentException if the id field is not a valid UUID string
     */
    @Override
    public UUID extractKey(TestRecord message) {
        String id = message.getId();
        // noinspection ConstantValue
        if (id == null || id.isEmpty()) {
            throw new IllegalArgumentException("TestRecord.id is required for key extraction");
        }
        return UUID.fromString(id);
    }
}
