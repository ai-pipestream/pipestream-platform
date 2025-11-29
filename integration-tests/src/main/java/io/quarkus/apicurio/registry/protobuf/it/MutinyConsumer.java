package io.quarkus.apicurio.registry.protobuf.it;

import io.quarkus.apicurio.registry.protobuf.it.proto.TestRecord;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Mutiny-based consumer that uses @Incoming with Uni return type.
 * Demonstrates reactive consumption of Protobuf messages from Kafka.
 */
@ApplicationScoped
public class MutinyConsumer {

    private static final Logger LOG = LoggerFactory.getLogger(MutinyConsumer.class);

    private final CopyOnWriteArrayList<TestRecord> received = new CopyOnWriteArrayList<>();

    /**
     * Reactive consumer that processes TestRecord messages.
     * Returns Uni<Void> to support async acknowledgment.
     * The extension auto-detects the Protobuf type and configures the deserializer.
     */
    @Incoming("mutiny-in")
    public Uni<Void> consume(TestRecord message) {
        return Uni.createFrom().item(message)
                .invoke(record -> {
                    LOG.info("Mutiny received: id={}, name={}", record.getId(), record.getName());
                    received.add(record);
                })
                .replaceWithVoid();
    }

    public CopyOnWriteArrayList<TestRecord> getReceived() {
        return received;
    }

    public void clear() {
        received.clear();
    }
}
