package ai.pipestream.apicurio.registry.protobuf.it;

import ai.pipestream.apicurio.registry.protobuf.it.proto.TestRecord;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Mutiny-based consumer that uses {@code @Incoming} with {@code Uni} return type.
 * <p>
 * Demonstrates reactive consumption of Protobuf messages from Kafka.
 */
@ApplicationScoped
public class MutinyConsumer {

    private static final Logger LOG = LoggerFactory.getLogger(MutinyConsumer.class);

    private final CopyOnWriteArrayList<TestRecord> received = new CopyOnWriteArrayList<>();

    /**
     * Default constructor.
     */
    public MutinyConsumer() {
    }

    /**
     * Reactive consumer that processes TestRecord messages.
     * Returns {@code Uni<Void>} to support async acknowledgment.
     * The extension auto-detects the Protobuf type and configures the deserializer.
     *
     * <p>
     * Note that using {@code Uni} indicates asynchronous processing; once the returned
     * {@code Uni} completes, the message is acknowledged.
     *
     * @param message the received {@link TestRecord}
     * @return a {@code Uni<Void>} completing when processing is finished
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

    /**
     * Returns the received records.
     *
     * @return a thread-safe list containing all received records
     */
    public CopyOnWriteArrayList<TestRecord> getReceived() {
        return received;
    }

    /**
     * Clears the collection of received records.
     */
    public void clear() {
        received.clear();
    }
}
