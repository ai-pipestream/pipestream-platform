package ai.pipestream.apicurio.registry.protobuf.it;

import ai.pipestream.apicurio.registry.protobuf.it.proto.TestRecord;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Consumer that receives protobuf messages from Kafka.
 * Uses standard @Incoming - the extension auto-detects Protobuf types.
 */
@ApplicationScoped
public class TestRecordConsumer {

    private static final Logger LOG = LoggerFactory.getLogger(TestRecordConsumer.class);

    private final CopyOnWriteArrayList<TestRecord> received = new CopyOnWriteArrayList<>();

    /**
     * Default constructor.
     */
    public TestRecordConsumer() {
    }

    /**
     * Consumes a TestRecord message.
     *
     * @param message the received message
     */
    @Incoming("test-in")
    public void consume(TestRecord message) {
        LOG.info("Received message: id={}, name={}", message.getId(), message.getName());
        received.add(message);
    }

    /**
     * Gets the list of received messages.
     *
     * @return the list of received messages
     */
    public CopyOnWriteArrayList<TestRecord> getReceived() {
        return received;
    }

    /**
     * Clears the list of received messages.
     */
    public void clear() {
        received.clear();
    }
}
