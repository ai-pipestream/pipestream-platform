package io.quarkus.apicurio.registry.protobuf.it;

import io.quarkus.apicurio.registry.protobuf.it.proto.TestRecord;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.operators.multi.processors.BroadcastProcessor;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.reactive.messaging.Outgoing;

import java.util.UUID;

/**
 * Mutiny-based producer that uses @Outgoing with Multi.
 * Demonstrates reactive streaming of Protobuf messages to Kafka.
 */
@ApplicationScoped
public class MutinyProducer {

    private final BroadcastProcessor<TestRecord> processor = BroadcastProcessor.create();

    /**
     * Reactive stream that emits TestRecord messages to Kafka.
     * The extension auto-detects the Protobuf type and configures the serializer.
     */
    @Outgoing("mutiny-out")
    public Multi<TestRecord> produce() {
        return processor;
    }

    /**
     * Send a message by emitting it to the processor.
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
     * Send a pre-built record.
     */
    public void send(TestRecord record) {
        processor.onNext(record);
    }
}
