package ai.pipestream.apicurio.registry.protobuf;

import com.google.protobuf.Message;
import org.jboss.logging.Logger;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A key extractor that generates random UUIDs for each message.
 *
 * <div class="warning">
 * <strong>WARNING: This extractor should NOT be used in production!</strong>
 * </div>
 *
 * <p>
 * Random UUIDs break several important Kafka patterns:
 * </p>
 * <ul>
 * <li><strong>Idempotency:</strong> Replaying the same message generates a
 * different key.</li>
 * <li><strong>Ordering:</strong> Related messages may land on different
 * partitions.</li>
 * <li><strong>Compaction:</strong> Log compaction becomes meaningless.</li>
 * <li><strong>Deduplication:</strong> Cannot detect duplicate messages.</li>
 * </ul>
 *
 * <p>
 * Instead, derive your UUID from:
 * </p>
 * <ul>
 * <li>A natural business key (order ID, customer ID, etc.).</li>
 * <li>A deterministic hash (UUIDv5 from content).</li>
 * <li>An external system's identifier.</li>
 * </ul>
 *
 * <p>
 * This class exists for:
 * </p>
 * <ul>
 * <li>Quick prototyping and testing.</li>
 * <li>Fire-and-forget events where replay doesn't matter.</li>
 * <li>Demonstrating what NOT to do in production.</li>
 * </ul>
 *
 * @param <T> the Protobuf message type
 * @see UuidKeyExtractor
 */
@SuppressWarnings("unused")
public class RandomUuidKeyExtractor<T extends Message> implements UuidKeyExtractor<T> {

    private static final Logger LOG = Logger.getLogger(RandomUuidKeyExtractor.class);

    private static final AtomicBoolean WARNING_LOGGED = new AtomicBoolean(false);

    /**
     * Creates a new random UUID extractor.
     * <p>
     * Logs a warning on first use reminding developers not to use this in
     * production.
     */
    public RandomUuidKeyExtractor() {
        logWarningOnce();
    }

    private void logWarningOnce() {
        if (WARNING_LOGGED.compareAndSet(false, true)) {
            LOG.warn("=".repeat(80));
            LOG.warn("RandomUuidKeyExtractor is being used!  STUPID DATA SCIENTIST ALERT!");
            LOG.warn("This generates random UUIDs which breaks idempotency and replay.");
            LOG.warn("DO NOT USE IN PRODUCTION - implement UuidKeyExtractor for your message type.");
            LOG.warn("=".repeat(80));
        }
    }

    /**
     * Generates a random UUID for the message.
     * <p>
     * The message content is ignored - a new random UUID is generated each time.
     *
     * @param message the Protobuf message (ignored)
     * @return a random UUID
     */
    @Override
    public UUID extractKey(T message) {
        return UUID.randomUUID();
    }

    /**
     * Creates a RandomUuidKeyExtractor for any message type.
     * <p>
     * Convenience factory method for type inference.
     *
     * @param <T> the Protobuf message type
     * @return a new RandomUuidKeyExtractor
     */
    public static <T extends Message> RandomUuidKeyExtractor<T> create() {
        return new RandomUuidKeyExtractor<>();
    }
}
