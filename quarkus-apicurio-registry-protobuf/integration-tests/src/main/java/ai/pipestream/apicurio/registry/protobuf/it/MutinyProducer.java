package ai.pipestream.apicurio.registry.protobuf.it;

import ai.pipestream.apicurio.registry.protobuf.it.proto.TestRecord;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.operators.multi.processors.BroadcastProcessor;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.reactive.messaging.Outgoing;

import java.util.UUID;

/**
 * Mutiny-based producer that uses {@code @Outgoing} with {@code Multi}.
 * <p>
 * Demonstrates reactive streaming of Protobuf messages to Kafka.
 */
@ApplicationScoped
public class MutinyProducer {

    private final BroadcastProcessor<TestRecord> processor = BroadcastProcessor.create();

    /**
     * Default constructor.
     */
    public MutinyProducer() {
    }

    /**
     * Reactive stream that emits {@link TestRecord} messages to Kafka.
     * The extension auto-detects the Protobuf type and configures the serializer.
     *
     * @return a {@code Multi<TestRecord>} that publishes records to the {@code mutiny-out} channel
     */
    @Outgoing("mutiny-out")
    public Multi<TestRecord> produce() {
        return processor;
    }

    /**
     * Sends a message by emitting it to the processor.
     *
     * @param name the value to set on the {@code name} field of the record
     */
    public void send(String name) {
        TestRecord record = TestRecord.newBuilder()
                .setId(UUID.randomUUID().toString())
                .setName(name)
                .setTimestamp(System.currentTimeMillis())
                .putMetadata("source", "mutiny-producer")
                .build();
        processor.onNext(record);
    }

    /**
     * Sends a pre-built record.
     *
     * @param record the record to emit
     */
    public void send(TestRecord record) {
        processor.onNext(record);
    }
}
