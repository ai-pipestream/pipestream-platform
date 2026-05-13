package ai.pipestream.server.work;

import ai.pipestream.module.work.v1.Hello;
import ai.pipestream.module.work.v1.ModuleWorkServiceGrpc;
import ai.pipestream.module.work.v1.ProcessingStatus;
import ai.pipestream.module.work.v1.WorkAck;
import ai.pipestream.module.work.v1.WorkRequest;
import ai.pipestream.module.work.v1.WorkResponse;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import io.grpc.stub.StreamObserver;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One bidi {@code Work} stream's full lifecycle: open → Hello → wait
 * for {@code WorkUnit} → run the module → Ack → wait for
 * {@code AckConfirmed} → close. Returns an {@link Outcome} describing
 * how the session ended, so the caller (the loop) can decide whether
 * to reset its backoff or escalate.
 *
 * <p>A session is single-use. The {@link ModuleWorkerLoop} constructs
 * one, runs it, and constructs a fresh one for the next iteration.
 *
 * @param <T> the module's concrete payload type
 */
final class WorkStreamSession<T extends Message> {

    private static final Logger LOG = Logger.getLogger(WorkStreamSession.class);

    /** What happened on this session — drives the loop's next decision. */
    enum Outcome {
        /** Work was processed and acked successfully. */
        SUCCESS,
        /** Engine reported no work was available within its server-side wait. */
        NO_WORK_AVAILABLE,
        /** Module processing returned a non-success status; engine recorded it. */
        FAILED_BY_MODULE,
        /** Stream failed before completing (network error, engine restart, watchdog close). */
        STREAM_ERROR
    }

    private final ModuleWorkServiceGrpc.ModuleWorkServiceStub asyncStub;
    private final ModuleProcessor<T> processor;
    private final PayloadCodec<T> codec;
    private final WorkerLoopConfig config;
    private final long ackTimeoutMillis;

    WorkStreamSession(ModuleWorkServiceGrpc.ModuleWorkServiceStub asyncStub,
                      ModuleProcessor<T> processor,
                      PayloadCodec<T> codec,
                      WorkerLoopConfig config) {
        this.asyncStub = Objects.requireNonNull(asyncStub, "asyncStub");
        this.processor = Objects.requireNonNull(processor, "processor");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.config = Objects.requireNonNull(config, "config");
        // The engine's NoWorkAvailable wait is 5s by default. We poll
        // for the first response with a generous timeout to absorb
        // that plus typical RTT.
        this.ackTimeoutMillis = Duration.ofSeconds(30).toMillis();
    }

    /**
     * Run the session to completion. Blocks the calling (virtual)
     * thread until the stream terminates one way or another. Never
     * throws — abnormal outcomes are returned as
     * {@link Outcome#STREAM_ERROR}.
     */
    Outcome run() {
        BlockingQueue<WorkResponse> responses = new LinkedBlockingQueue<>();
        AtomicReference<Throwable> streamError = new AtomicReference<>();
        AtomicReference<Boolean> serverCompleted = new AtomicReference<>(Boolean.FALSE);
        Object writeLock = new Object();

        StreamObserver<WorkResponse> responseObserver = new StreamObserver<>() {
            @Override public void onNext(WorkResponse value) { responses.add(value); }
            @Override public void onError(Throwable t) { streamError.set(t); serverCompleted.set(Boolean.TRUE); }
            @Override public void onCompleted() { serverCompleted.set(Boolean.TRUE); }
        };

        StreamObserver<WorkRequest> requestObserver;
        try {
            requestObserver = asyncStub.work(responseObserver);
        } catch (RuntimeException e) {
            LOG.debugf(e, "failed to open Work stream");
            return Outcome.STREAM_ERROR;
        }

        // --- Send Hello ---
        WorkRequest hello = WorkRequest.newBuilder()
                .setHello(Hello.newBuilder()
                        .setCluster(config.cluster())
                        .setGraphId(config.graphId())
                        .setNodeId(config.nodeId())
                        .setModuleId(config.moduleId())
                        .setInstanceId(instanceId())
                        .build())
                .build();
        synchronized (writeLock) {
            try {
                requestObserver.onNext(hello);
            } catch (RuntimeException e) {
                LOG.debugf(e, "Hello send failed");
                return Outcome.STREAM_ERROR;
            }
        }

        // --- Receive WorkUnit (or NoWorkAvailable) ---
        WorkResponse first;
        try {
            first = responses.poll(ackTimeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            closeQuietly(requestObserver);
            return Outcome.STREAM_ERROR;
        }
        if (first == null) {
            LOG.warnf("no WorkUnit/NoWorkAvailable received within %dms; treating as stream error",
                    ackTimeoutMillis);
            closeQuietly(requestObserver);
            return Outcome.STREAM_ERROR;
        }
        if (first.hasNoWork()) {
            // Engine had nothing buffered. Close cleanly; the loop
            // sleeps briefly and reopens.
            closeQuietly(requestObserver);
            return Outcome.NO_WORK_AVAILABLE;
        }
        if (!first.hasWorkUnit()) {
            LOG.warnf("first WorkResponse must be WorkUnit or NoWorkAvailable; got %s",
                    first.getPayloadCase());
            closeQuietly(requestObserver);
            return Outcome.STREAM_ERROR;
        }

        // --- Process while heartbeating ---
        String workUnitId = first.getWorkUnit().getWorkUnitId();
        T input;
        try {
            input = codec.unpack(first.getWorkUnit().getPayload());
        } catch (InvalidProtocolBufferException e) {
            LOG.errorf(e, "WorkUnit payload could not be unpacked as %s (typeUrl=%s)",
                    codec.messageClass().getSimpleName(),
                    first.getWorkUnit().getPayload().getTypeUrl());
            // Tell the engine the payload was unprocessable. The engine
            // records permanent failure and moves on; the module didn't
            // do anything wrong — the engine sent the wrong type.
            sendAckPermanent(requestObserver, writeLock, workUnitId,
                    "WorkUnit payload type mismatch: " + e.getMessage());
            return finalizeOutcome(
                    drainAckConfirmed(responses, requestObserver, streamError),
                    ProcessingStatus.PROCESSING_STATUS_PERMANENT_FAILURE);
        }

        WorkAck ack = runProcessor(requestObserver, writeLock, workUnitId, input);
        synchronized (writeLock) {
            try {
                requestObserver.onNext(WorkRequest.newBuilder().setAck(ack).build());
            } catch (RuntimeException e) {
                LOG.debugf(e, "WorkAck send failed");
                return Outcome.STREAM_ERROR;
            }
        }

        // --- Wait for AckConfirmed + clean close ---
        return finalizeOutcome(
                drainAckConfirmed(responses, requestObserver, streamError),
                ack.getStatus());
    }

    /**
     * Reconcile a clean-stream-close outcome with the status the
     * module reported. A SUCCESS stream close coupled with a
     * non-success ack means the work didn't actually succeed —
     * surface that as FAILED_BY_MODULE so the loop can distinguish
     * "transport problem, back off" from "module said no, don't
     * back off."
     */
    private Outcome finalizeOutcome(Outcome drainOutcome, ProcessingStatus ackStatus) {
        if (drainOutcome != Outcome.SUCCESS) {
            return drainOutcome;
        }
        return ackStatus == ProcessingStatus.PROCESSING_STATUS_SUCCESS
                ? Outcome.SUCCESS
                : Outcome.FAILED_BY_MODULE;
    }

    /**
     * Invoke the module's processor with heartbeats running. Maps
     * thrown exceptions to the appropriate {@link ProcessingStatus}.
     */
    private WorkAck runProcessor(StreamObserver<WorkRequest> requestObserver,
                                 Object writeLock,
                                 String workUnitId,
                                 T input) {
        try (HeartbeatPump pump = new HeartbeatPump(requestObserver, writeLock, config.heartbeatInterval())) {
            pump.start();
            T output;
            try {
                output = processor.process(input);
            } catch (ModuleProcessor.PermanentFailure pf) {
                return WorkAck.newBuilder()
                        .setWorkUnitId(workUnitId)
                        .setStatus(ProcessingStatus.PROCESSING_STATUS_PERMANENT_FAILURE)
                        .setErrorMessage(pf.getMessage() == null ? "" : pf.getMessage())
                        .build();
            } catch (RuntimeException retryable) {
                LOG.warnf(retryable, "Retryable module failure for work_unit %s", workUnitId);
                return WorkAck.newBuilder()
                        .setWorkUnitId(workUnitId)
                        .setStatus(ProcessingStatus.PROCESSING_STATUS_RETRYABLE_FAILURE)
                        .setErrorMessage(retryable.getMessage() == null
                                ? retryable.getClass().getSimpleName()
                                : retryable.getMessage())
                        .build();
            }
            return WorkAck.newBuilder()
                    .setWorkUnitId(workUnitId)
                    .setStatus(ProcessingStatus.PROCESSING_STATUS_SUCCESS)
                    .setUpdatedPayload(codec.pack(output))
                    .build();
        }
    }

    private void sendAckPermanent(StreamObserver<WorkRequest> requestObserver,
                                  Object writeLock,
                                  String workUnitId,
                                  String message) {
        synchronized (writeLock) {
            try {
                requestObserver.onNext(WorkRequest.newBuilder()
                        .setAck(WorkAck.newBuilder()
                                .setWorkUnitId(workUnitId)
                                .setStatus(ProcessingStatus.PROCESSING_STATUS_PERMANENT_FAILURE)
                                .setErrorMessage(message)
                                .build())
                        .build());
            } catch (RuntimeException ignored) {
                // already closed; the surrounding code path handles cleanup
            }
        }
    }

    /**
     * Wait for the engine's {@code AckConfirmed} (or stream close) and
     * tell the loop how the session ended.
     */
    private Outcome drainAckConfirmed(BlockingQueue<WorkResponse> responses,
                                       StreamObserver<WorkRequest> requestObserver,
                                       AtomicReference<Throwable> streamError) {
        try {
            WorkResponse confirmation = responses.poll(ackTimeoutMillis, TimeUnit.MILLISECONDS);
            closeQuietly(requestObserver);
            if (streamError.get() != null) {
                LOG.debugf(streamError.get(), "stream errored before AckConfirmed");
                return Outcome.STREAM_ERROR;
            }
            if (confirmation == null) {
                LOG.warnf("AckConfirmed not received within %dms", ackTimeoutMillis);
                return Outcome.STREAM_ERROR;
            }
            if (!confirmation.hasAckConfirmed()) {
                LOG.warnf("expected AckConfirmed, got %s", confirmation.getPayloadCase());
                return Outcome.STREAM_ERROR;
            }
            return Outcome.SUCCESS;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            closeQuietly(requestObserver);
            return Outcome.STREAM_ERROR;
        }
    }

    private static void closeQuietly(StreamObserver<WorkRequest> requestObserver) {
        try {
            requestObserver.onCompleted();
        } catch (RuntimeException ignored) {
            // Stream already closed by engine or by an earlier error
            // path — best-effort close, no-op on the second attempt.
        }
    }

    private static String instanceId() {
        String host = System.getenv("HOSTNAME");
        return host != null && !host.isEmpty() ? host : "unknown-host";
    }
}
