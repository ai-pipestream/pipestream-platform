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
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ramp-up worker scaling: idle queue keeps {@code minConcurrency} pollers;
 * successful work spawns workers up to {@code concurrency}.
 */
class ModuleWorkerLoopTest {

    private String serverName;
    private Server server;
    private ManagedChannel channel;
    private final AtomicInteger workUnitsServed = new AtomicInteger();

    @BeforeEach
    void startServer() throws Exception {
        serverName = "module-worker-loop-" + UUID.randomUUID();
        server = InProcessServerBuilder.forName(serverName)
                .directExecutor()
                .addService(new AlwaysNoWorkEngine())
                .build()
                .start();
        channel = InProcessChannelBuilder.forName(serverName).directExecutor().build();
    }

    @AfterEach
    void stopServer() throws Exception {
        if (channel != null) {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
        if (server != null) {
            server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void idleQueue_startsOnlyMinWorkers_notMaxConcurrency() throws Exception {
        ModuleWorkerLoop<Hello> loop = newLoop(minWorkers(1), maxWorkers(8));
        loop.onStart(new StartupEvent());
        Thread.sleep(300);
        assertThat(loop.snapshot().activeWorkers())
                .as("idle queue should not open max concurrency streams")
                .isEqualTo(1);
        loop.onStop(new ShutdownEvent());
    }

    @Test
    void successfulWork_rampsUpTowardMaxConcurrency() throws Exception {
        server.shutdownNow().awaitTermination(2, TimeUnit.SECONDS);
        server = InProcessServerBuilder.forName(serverName)
                .directExecutor()
                .addService(new AlwaysWorkEngine(workUnitsServed))
                .build()
                .start();

        ModuleWorkerLoop<Hello> loop = newLoop(minWorkers(1), maxWorkers(4));
        loop.onStart(new StartupEvent());

        int peak = 0;
        for (int i = 0; i < 100; i++) {
            peak = Math.max(peak, loop.snapshot().activeWorkers());
            if (peak >= 2) {
                break;
            }
            Thread.sleep(50);
        }
        loop.onStop(new ShutdownEvent());
        assertThat(peak)
                .as("successful units should spawn additional workers")
                .isGreaterThanOrEqualTo(2);
        assertThat(workUnitsServed.get()).isGreaterThanOrEqualTo(2);
    }

    private ModuleWorkerLoop<Hello> newLoop(int min, int max) {
        ModuleWorkServiceGrpc.ModuleWorkServiceStub stub =
                ModuleWorkServiceGrpc.newStub(channel);
        return new ModuleWorkerLoop<>(
                Hello.class,
                input -> input,
                stub,
                rampConfig(min, max));
    }

    private static WorkerLoopConfig rampConfig(int min, int max) {
        return new WorkerLoopConfig() {
            @Override public boolean enabled() { return true; }
            @Override public String moduleId() { return "echo"; }
            @Override public int concurrency() { return max; }
            @Override public int minConcurrency() { return min; }
            @Override public Duration heartbeatInterval() { return Duration.ofMinutes(5); }
            @Override public Duration reconnectInitialDelay() { return Duration.ofMillis(10); }
            @Override public Duration reconnectMaxDelay() { return Duration.ofMillis(100); }
            @Override public Duration noWorkRetryAfter() { return Duration.ofMillis(50); }
            @Override public Duration firstResponseTimeout() { return Duration.ofSeconds(5); }
        };
    }

    private static int minWorkers(int n) { return n; }
    private static int maxWorkers(int n) { return n; }

    private static final class AlwaysNoWorkEngine extends ModuleWorkServiceGrpc.ModuleWorkServiceImplBase {
        @Override
        public StreamObserver<WorkRequest> work(StreamObserver<WorkResponse> responses) {
            return new StreamObserver<>() {
                @Override
                public void onNext(WorkRequest req) {
                    if (req.hasHello()) {
                        responses.onNext(WorkResponse.newBuilder()
                                .setNoWork(NoWorkAvailable.newBuilder().setRetryAfterMs(50).build())
                                .build());
                        responses.onCompleted();
                    }
                }

                @Override public void onError(Throwable t) { }
                @Override public void onCompleted() { }
            };
        }
    }

    private static final class AlwaysWorkEngine extends ModuleWorkServiceGrpc.ModuleWorkServiceImplBase {
        private final AtomicInteger served;

        AlwaysWorkEngine(AtomicInteger served) {
            this.served = served;
        }

        @Override
        public StreamObserver<WorkRequest> work(StreamObserver<WorkResponse> responses) {
            return new StreamObserver<>() {
                @Override
                public void onNext(WorkRequest req) {
                    if (req.hasHello()) {
                        Hello payload = Hello.newBuilder()
                                .setModuleId("echo")
                                .build();
                        responses.onNext(WorkResponse.newBuilder()
                                .setWorkUnit(WorkUnit.newBuilder()
                                        .setWorkUnitId("wu-" + UUID.randomUUID())
                                        .setPayload(Any.pack(payload))
                                        .build())
                                .build());
                        return;
                    }
                    if (req.hasAck()) {
                        responses.onNext(WorkResponse.newBuilder()
                                .setAckConfirmed(AckConfirmed.newBuilder()
                                        .setWorkUnitId(req.getAck().getWorkUnitId())
                                        .setAccepted(true)
                                        .build())
                                .build());
                        responses.onCompleted();
                        served.incrementAndGet();
                    }
                }

                @Override public void onError(Throwable t) { }
                @Override public void onCompleted() { }
            };
        }
    }
}
