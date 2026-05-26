package ai.pipestream.module.runtime.work;

import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;

import java.util.Objects;

/**
 * Thin wrapper around {@link Any#pack} / {@link Any#unpack} that
 * captures the concrete message class at construction time so the
 * surrounding loop code doesn't have to pass {@code Class<T>} on every
 * call.
 *
 * <p>Stateless after construction. Safe to share across threads.
 *
 * @param <T> concrete protobuf message type
 */
public final class PayloadCodec<T extends Message> {

    private final Class<T> messageClass;

    public PayloadCodec(Class<T> messageClass) {
        this.messageClass = Objects.requireNonNull(messageClass, "messageClass");
    }

    /**
     * Pack a typed message into an {@link Any} for the wire.
     *
     * @param value the message to pack
     * @return an {@code Any} carrying the message bytes plus its type URL
     */
    public Any pack(T value) {
        return Any.pack(Objects.requireNonNull(value, "value"));
    }

    /**
     * Unpack an {@link Any} arriving on the wire into the expected
     * concrete type. Throws if the {@code Any.type_url} doesn't match
     * the codec's message class or the bytes don't decode as the
     * expected message — the surrounding code treats this as a
     * protocol violation by the sender, since modules are configured
     * for exactly one payload type.
     *
     * @param wire the {@code Any} field from a {@code WorkUnit} or
     *             {@code WorkAck}
     * @return the typed message
     * @throws InvalidProtocolBufferException if the wire payload's
     *         type doesn't match {@code T} or the bytes can't be parsed
     */
    public T unpack(Any wire) throws InvalidProtocolBufferException {
        return Objects.requireNonNull(wire, "wire").unpack(messageClass);
    }

    /**
     * @return the message class this codec was constructed with;
     *         useful in diagnostics
     */
    public Class<T> messageClass() {
        return messageClass;
    }
}
