package ai.pipestream.apicurio.registry.protobuf.it;

import ai.pipestream.apicurio.registry.protobuf.it.proto.TestRecord;
import io.smallrye.reactive.messaging.kafka.Record;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Consumer that receives protobuf messages using Record<UUID, V> to verify key support.
 */
@ApplicationScoped
public class RecordConsumer {

    private static final Logger LOG = LoggerFactory.getLogger(RecordConsumer.class);

    private final CopyOnWriteArrayList<Record<UUID, TestRecord>> received = new CopyOnWriteArrayList<>();

    @Incoming("record-in")
    public void consume(Record<UUID, TestRecord> record) {
        LOG.info("Received Record: key={}, value={}", record.key(), record.value().getName());
        received.add(record);
    }

    public CopyOnWriteArrayList<Record<UUID, TestRecord>> getReceived() {
        return received;
    }

    public void clear() {
        received.clear();
    }
}
