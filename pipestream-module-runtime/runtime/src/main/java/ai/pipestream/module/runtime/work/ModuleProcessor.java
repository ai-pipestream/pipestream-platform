package ai.pipestream.module.runtime.work;

import com.google.protobuf.Message;

/**
 * Pure-function interface a module implements to process one
 * {@code WorkUnit}'s payload. Decoupled from the wire and from the
 * gRPC stream lifecycle — implementations see only their concrete
 * input type and return their concrete output type.
 *
 * <p>The {@link ModuleWorkerLoop} framework handles everything else:
 * dialing the engine, opening streams, sending the {@code Hello},
 * unpacking the {@code Any} payload to {@code T}, emitting heartbeats
 * while {@link #process} is running, packing the result back into
 * an {@code Any} for the {@code WorkAck}, and reconnecting on failure.
 *
 * <p>Implementations are expected to be:
 * <ul>
 *   <li><b>Synchronous</b>. The framework runs each {@code process}
 *       call on a dedicated virtual thread inside a stream; blocking
 *       I/O is fine.</li>
 *   <li><b>Idempotent.</b> Kafka redelivery, engine restart, or
 *       network-level retries can cause the same {@code WorkUnit} to
 *       arrive more than once. The implementation must produce an
 *       equivalent result on each invocation.</li>
 *   <li><b>Self-contained on failure.</b> If the input is unprocessable
 *       in a way the module wants the engine to record as a permanent
 *       failure rather than retry, throw a {@link PermanentFailure}.
 *       Any other thrown exception is treated as retryable — the
 *       framework will report {@code RETRYABLE_FAILURE} to the engine
 *       and Kafka will redeliver the record to a fresh stream (likely
 *       on a different module instance).</li>
 * </ul>
 *
 * @param <T> the module's concrete protobuf payload type
 */
@FunctionalInterface
public interface ModuleProcessor<T extends Message> {

    /**
     * Process one work unit. Pure function from input to output.
     *
     * @param input the fully-hydrated input payload (unpacked from the
     *              {@code WorkUnit.payload} {@code Any} by the framework)
     * @return the updated payload to send back to the engine in the
     *         {@code WorkAck.updated_payload} {@code Any}
     * @throws PermanentFailure to record the work unit as permanently
     *         failed at the engine (no Kafka redelivery, no retry)
     * @throws RuntimeException any other exception is treated as a
     *         retryable failure
     */
    T process(T input) throws PermanentFailure;

    /**
     * Thrown by a {@link ModuleProcessor#process} implementation to
     * signal that the input cannot be processed and must not be
     * retried. The framework reports
     * {@code ProcessingStatus.PROCESSING_STATUS_PERMANENT_FAILURE}
     * to the engine with the exception's message as the
     * {@code error_message}, so an operator can see what happened
     * without digging in module logs.
     */
    final class PermanentFailure extends RuntimeException {
        public PermanentFailure(String message) {
            super(message);
        }

        public PermanentFailure(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
