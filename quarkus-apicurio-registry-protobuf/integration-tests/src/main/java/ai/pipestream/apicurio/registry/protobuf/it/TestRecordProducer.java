package ai.pipestream.apicurio.registry.protobuf.it;

import ai.pipestream.apicurio.registry.protobuf.it.proto.TestRecord;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;

import java.util.UUID;

/**
 * Producer that sends TestRecord protobuf messages to Kafka.
 * Uses standard @Channel - the extension auto-detects Protobuf types.
 */
@ApplicationScoped
public class TestRecordProducer {

    @Inject
    @Channel("test-out")
    Emitter<TestRecord> emitter;

    /**
     * Default constructor.
     */
    public TestRecordProducer() {
    }

    /**
     * Sends a TestRecord with the given name.
     *
     * @param name the name to include in the record
     */
    public void send(String name) {
        TestRecord record = TestRecord.newBuilder()
                .setId(UUID.randomUUID().toString())
                .setName(name)
                .setTimestamp(System.currentTimeMillis())
                .putMetadata("source", "integration-test")
                .build();

        emitter.send(record);
    }

    /**
     * Sends a TestRecord.
     *
     * @param record the record to send
     */
    public void send(TestRecord record) {
        emitter.send(record);
    }
}
