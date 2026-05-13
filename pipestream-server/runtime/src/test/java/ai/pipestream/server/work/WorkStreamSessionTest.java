package ai.pipestream.server.work;

import ai.pipestream.module.work.v1.AckConfirmed;
import ai.pipestream.module.work.v1.Hello;
import ai.pipestream.module.work.v1.ModuleWorkServiceGrpc;
import ai.pipestream.module.work.v1.NoWorkAvailable;
import ai.pipestream.module.work.v1.ProcessingStatus;
import ai.pipestream.module.work.v1.WorkAck;
import ai.pipestream.module.work.v1.WorkRequest;
import ai.pipestream.module.work.v1.WorkResponse;
import ai.pipestream.module.work.v1.WorkUnit;
import com.google.protobuf.Any;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests {@link WorkStreamSession}'s protocol state machine against a
 * fake in-process gRPC server that scripts the engine side of the
 * conversation. Exercises the wire end-to-end (real gRPC channel, real
 * bidi semantics) without needing Kafka, Redis, or a live engine.
 *
 * <p>Each test instance brings up its own in-process server bound to a
 * unique name, runs the session, then tears the channel down. The
 * fake engine records the {@code WorkRequest}s it observed so tests
 * can assert what the session sent.
 */
class WorkStreamSessionTest {

    private String serverName;
    private Server server;
    private ManagedChannel channel;
    private FakeEngine fakeEngine;
    private ModuleWorkServiceGrpc.ModuleWorkServiceStub asyncStub;

    @BeforeEach
    void startServer() throws Exception {
        serverName = "work-stream-session-test-" + UUID.randomUUID();
        fakeEngine = new FakeEngine();
        server = InProcessServerBuilder.forName(serverName)
                .directExecutor()
                .addService(fakeEngine)
                .build()
                .start();
        channel = InProcessChannelBuilder.forName(serverName)
                .directExecutor()
                .build();
        asyncStub = ModuleWorkServiceGrpc.newStub(channel);
    }

    @AfterEach
    void stopServer() throws Exception {
        channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }

    @Test
    void happyPath_ReturnsSuccess_SendsWellFormedAck() throws Exception {
        // Script the fake engine: respond to Hello with a WorkUnit
        // carrying a Hello message as the Any payload (any concrete
        // message works for the test; we use Hello to avoid pulling
        // engine types onto the test classpath).
        Hello inputPayload = Hello.newBuilder().setCluster("payload-in").build();
        Hello outputPayload = Hello.newBuilder().setCluster("payload-out").build();
        fakeEngine.respondTo(Hello.class, hello ->
                WorkResponse.newBuilder()
                        .setWorkUnit(WorkUnit.newBuilder()
                                .setWorkUnitId("wu-1")
                                .setPayload(Any.pack(inputPayload))
                                .build())
                        .build());
        fakeEngine.respondTo(WorkAck.class, ack ->
                WorkResponse.newBuilder()
                        .setAckConfirmed(AckConfirmed.newBuilder()
                                .setWorkUnitId(ack.getWorkUnitId())
                                .setAccepted(true)
                                .build())
                        .build());

        ModuleProcessor<Hello> processor = input -> {
            assertThat(input).isEqualTo(inputPayload);
            return outputPayload;
        };
        WorkStreamSession.Outcome outcome = newSession(processor).run();

        assertThat(outcome).isEqualTo(WorkStreamSession.Outcome.SUCCESS);
        assertThat(fakeEngine.requests).as("session must send Hello then WorkAck").hasSize(2);
        assertThat(fakeEngine.requests.get(0).hasHello())
                .as("first request is Hello").isTrue();
        assertThat(fakeEngine.requests.get(1).hasAck())
                .as("second request is WorkAck").isTrue();
        WorkAck observed = fakeEngine.requests.get(1).getAck();
        assertThat(observed.getStatus()).isEqualTo(ProcessingStatus.PROCESSING_STATUS_SUCCESS);
        assertThat(observed.getWorkUnitId()).isEqualTo("wu-1");
        assertThat(observed.getUpdatedPayload().unpack(Hello.class))
                .as("WorkAck.updated_payload must be the processor's output, packed as Any")
                .isEqualTo(outputPayload);
    }

    @Test
    void noWorkAvailable_ReturnsNoWorkOutcome_NoProcessorCall() {
        fakeEngine.respondTo(Hello.class, hello ->
                WorkResponse.newBuilder()
                        .setNoWork(NoWorkAvailable.newBuilder().setRetryAfterMs(500).build())
                        .build());

        boolean[] processorWasCalled = {false};
        ModuleProcessor<Hello> processor = input -> {
            processorWasCalled[0] = true;
            return input;
        };

        WorkStreamSession.Outcome outcome = newSession(processor).run();

        assertThat(outcome).isEqualTo(WorkStreamSession.Outcome.NO_WORK_AVAILABLE);
        assertThat(processorWasCalled[0])
                .as("processor must NOT be invoked when the engine returns NoWorkAvailable")
                .isFalse();
    }

    @Test
    void permanentFailure_AcksWithPermanentStatus() throws Exception {
        Hello inputPayload = Hello.newBuilder().setCluster("doomed").build();
        fakeEngine.respondTo(Hello.class, hello ->
                WorkResponse.newBuilder()
                        .setWorkUnit(WorkUnit.newBuilder()
                                .setWorkUnitId("wu-perm")
                                .setPayload(Any.pack(inputPayload))
                                .build())
                        .build());
        fakeEngine.respondTo(WorkAck.class, ack ->
                WorkResponse.newBuilder()
                        .setAckConfirmed(AckConfirmed.newBuilder()
                                .setWorkUnitId(ack.getWorkUnitId())
                                .setAccepted(true)
                                .build())
                        .build());

        ModuleProcessor<Hello> processor = input -> {
            throw new ModuleProcessor.PermanentFailure("this doc is malformed");
        };

        WorkStreamSession.Outcome outcome = newSession(processor).run();

        assertThat(outcome)
                .as("an ack with non-SUCCESS status maps to FAILED_BY_MODULE so the loop "
                        + "can reset its backoff (it wasn't a transport problem)")
                .isEqualTo(WorkStreamSession.Outcome.FAILED_BY_MODULE);
        WorkAck observed = fakeEngine.requests.stream()
                .filter(WorkRequest::hasAck)
                .findFirst()
                .orElseThrow()
                .getAck();
        assertThat(observed.getStatus())
                .isEqualTo(ProcessingStatus.PROCESSING_STATUS_PERMANENT_FAILURE);
        assertThat(observed.getErrorMessage()).isEqualTo("this doc is malformed");
        assertThat(observed.hasUpdatedPayload())
                .as("WorkAck on failure must NOT carry an updated_payload")
                .isFalse();
    }

    @Test
    void retryableFailure_AcksWithRetryableStatus() {
        fakeEngine.respondTo(Hello.class, hello ->
                WorkResponse.newBuilder()
                        .setWorkUnit(WorkUnit.newBuilder()
                                .setWorkUnitId("wu-retry")
                                .setPayload(Any.pack(Hello.getDefaultInstance()))
                                .build())
                        .build());
        fakeEngine.respondTo(WorkAck.class, ack ->
                WorkResponse.newBuilder()
                        .setAckConfirmed(AckConfirmed.newBuilder()
                                .setWorkUnitId(ack.getWorkUnitId()).setAccepted(true).build())
                        .build());

        ModuleProcessor<Hello> processor = input -> {
            throw new RuntimeException("downstream blip");
        };

        WorkStreamSession.Outcome outcome = newSession(processor).run();
        assertThat(outcome).isEqualTo(WorkStreamSession.Outcome.FAILED_BY_MODULE);
        WorkAck observed = fakeEngine.requests.stream()
                .filter(WorkRequest::hasAck).findFirst().orElseThrow().getAck();
        assertThat(observed.getStatus())
                .as("an unchecked exception (not PermanentFailure) maps to RETRYABLE_FAILURE")
                .isEqualTo(ProcessingStatus.PROCESSING_STATUS_RETRYABLE_FAILURE);
        assertThat(observed.getErrorMessage()).isEqualTo("downstream blip");
    }

    @Test
    void mismatchedPayloadType_AcksWithPermanentStatus_NeverInvokesProcessor() {
        // Engine sends a WorkUnit whose Any contains a WorkAck (not a
        // Hello). The session's codec is Hello.class, so unpack will
        // fail.
        fakeEngine.respondTo(Hello.class, hello ->
                WorkResponse.newBuilder()
                        .setWorkUnit(WorkUnit.newBuilder()
                                .setWorkUnitId("wu-mismatched")
                                .setPayload(Any.pack(WorkAck.newBuilder().setWorkUnitId("bogus").build()))
                                .build())
                        .build());
        fakeEngine.respondTo(WorkAck.class, ack ->
                WorkResponse.newBuilder()
                        .setAckConfirmed(AckConfirmed.newBuilder()
                                .setWorkUnitId(ack.getWorkUnitId()).setAccepted(true).build())
                        .build());

        boolean[] processorWasCalled = {false};
        ModuleProcessor<Hello> processor = input -> {
            processorWasCalled[0] = true;
            return input;
        };

        WorkStreamSession.Outcome outcome = newSession(processor).run();

        assertThat(processorWasCalled[0])
                .as("processor must NOT see a payload that doesn't match its declared type")
                .isFalse();
        WorkAck observed = fakeEngine.requests.stream()
                .filter(WorkRequest::hasAck).findFirst().orElseThrow().getAck();
        assertThat(observed.getStatus())
                .as("type-mismatched payloads are recorded as PERMANENT_FAILURE since "
                        + "redelivering wouldn't help — the engine sent the wrong type")
                .isEqualTo(ProcessingStatus.PROCESSING_STATUS_PERMANENT_FAILURE);
        assertThat(outcome).isEqualTo(WorkStreamSession.Outcome.FAILED_BY_MODULE);
    }

    @Test
    void streamErrorBeforeWorkUnit_ReturnsStreamError() {
        fakeEngine.respondTo(Hello.class, hello ->
                WorkResponse.newBuilder().build()); // server-side error simulation
        fakeEngine.simulateErrorAfterHello = true;

        WorkStreamSession.Outcome outcome = newSession(input -> input).run();
        assertThat(outcome).isEqualTo(WorkStreamSession.Outcome.STREAM_ERROR);
    }

    private WorkStreamSession<Hello> newSession(ModuleProcessor<Hello> processor) {
        return new WorkStreamSession<>(asyncStub, processor, new PayloadCodec<>(Hello.class), testConfig());
    }

    private static WorkerLoopConfig testConfig() {
        return new WorkerLoopConfig() {
            @Override public boolean enabled() { return true; }
            @Override public String cluster() { return "test-c"; }
            @Override public String graphId() { return "test-g"; }
            @Override public String nodeId() { return "test-n"; }
            @Override public String moduleId() { return "test-m"; }
            @Override public int concurrency() { return 1; }
            @Override public Duration heartbeatInterval() { return Duration.ofSeconds(60); } // suppress heartbeats in tests
            @Override public Duration reconnectInitialDelay() { return Duration.ofMillis(50); }
            @Override public Duration reconnectMaxDelay() { return Duration.ofSeconds(1); }
            @Override public Duration noWorkRetryAfter() { return Duration.ofMillis(10); }
        };
    }

    /**
     * In-process fake of the engine's {@code ModuleWorkService}. Tests
     * register response factories keyed by the inbound request type;
     * the fake records every WorkRequest it sees so assertions can
     * inspect what the session sent.
     */
    private static final class FakeEngine extends ModuleWorkServiceGrpc.ModuleWorkServiceImplBase {
        final List<WorkRequest> requests = new CopyOnWriteArrayList<>();
        volatile java.util.function.Function<Hello, WorkResponse> onHello;
        volatile java.util.function.Function<WorkAck, WorkResponse> onAck;
        volatile boolean simulateErrorAfterHello = false;

        @SuppressWarnings("unchecked")
        <T> void respondTo(Class<T> requestType, java.util.function.Function<T, WorkResponse> handler) {
            if (requestType == Hello.class) {
                this.onHello = (java.util.function.Function<Hello, WorkResponse>) handler;
            } else if (requestType == WorkAck.class) {
                this.onAck = (java.util.function.Function<WorkAck, WorkResponse>) handler;
            } else {
                throw new IllegalArgumentException("Unsupported request type: " + requestType);
            }
        }

        @Override
        public StreamObserver<WorkRequest> work(StreamObserver<WorkResponse> responseObserver) {
            return new StreamObserver<>() {
                @Override
                public void onNext(WorkRequest req) {
                    requests.add(req);
                    if (req.hasHello() && onHello != null) {
                        if (simulateErrorAfterHello) {
                            responseObserver.onError(new RuntimeException("simulated"));
                            return;
                        }
                        responseObserver.onNext(onHello.apply(req.getHello()));
                    } else if (req.hasAck() && onAck != null) {
                        responseObserver.onNext(onAck.apply(req.getAck()));
                        responseObserver.onCompleted();
                    }
                    // Heartbeats are silently accepted (no response).
                }

                @Override public void onError(Throwable t) {}
                @Override public void onCompleted() {}
            };
        }
    }
}
