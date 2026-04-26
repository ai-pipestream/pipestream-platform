package ai.pipestream.quarkus.dynamicgrpc;

import ai.pipestream.quarkus.dynamicgrpc.base.ConsulServiceRegistration;
import ai.pipestream.quarkus.dynamicgrpc.it.proto.EchoBytesReply;
import ai.pipestream.quarkus.dynamicgrpc.it.proto.EchoBytesRequest;
import ai.pipestream.quarkus.dynamicgrpc.it.proto.GreeterGrpc;
import ai.pipestream.quarkus.dynamicgrpc.it.proto.MutinyGreeterGrpc;
import ai.pipestream.test.support.ConsulTestResource;
import com.google.protobuf.ByteString;
import io.grpc.Channel;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression guard for the <b>raw async-callback stub</b> path —
 * {@code GreeterGrpc.newStub(channel)} with a {@link StreamObserver}
 * callback — which is pipestream-engine's preferred non-Mutiny gRPC
 * client shape. Mutiny stubs in {@link LargePayloadConcurrencyTest}
 * cover the same wire underneath but wrap the call in a {@code Uni}
 * adapter; this test exercises the unwrapped surface directly so a
 * regression in the channel's interaction with the standard
 * {@code grpc-stub} async API would surface here even if the Mutiny
 * adapter happened to mask it.
 *
 * <p>Same payload shape as {@link LargePayloadConcurrencyTest}:
 * 10 concurrent VT × 50&nbsp;MB unary. Pass criteria identical:
 * 0 silent drops carrying the {@code "Half-closed without a request"}
 * signature, 0 error responses, every server-reported received_bytes
 * equals the sent size.
 */
@QuarkusTest
@WithTestResource(ConsulTestResource.class)
public class AsyncStubLargePayloadTest {

    private static final Logger LOG = Logger.getLogger(AsyncStubLargePayloadTest.class);

    private static final int PAYLOAD_SIZE = 50 * 1024 * 1024;
    private static final int CONCURRENT_FAN_OUT = 10;
    private static final Duration WALL_BUDGET = Duration.ofMinutes(2);

    private static Server testGrpcServer;
    private static int testGrpcPort;

    @Inject
    GrpcClientFactory factory;

    @ConfigProperty(name = "quarkus.dynamic-grpc.consul.host")
    String consulHost;

    @ConfigProperty(name = "quarkus.dynamic-grpc.consul.port")
    int consulPort;

    private ConsulServiceRegistration consulRegistration;
    private String serviceName;

    @BeforeAll
    static void startTestServer() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            testGrpcPort = s.getLocalPort();
        }
        testGrpcServer = ServerBuilder.forPort(testGrpcPort)
                .maxInboundMessageSize(64 * 1024 * 1024)
                .addService(new EchoBytesGreeterService())
                .build()
                .start();
        LOG.infof("AsyncStub test gRPC server started on port: %d", testGrpcPort);
    }

    @AfterAll
    static void stopTestServer() throws InterruptedException {
        if (testGrpcServer != null) {
            testGrpcServer.shutdown();
            testGrpcServer.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @BeforeEach
    void setup() {
        consulRegistration = new ConsulServiceRegistration(consulHost, consulPort);
        serviceName = "async-stub-" + UUID.randomUUID().toString().substring(0, 8);
        consulRegistration.registerService(serviceName, serviceName + "-1", "127.0.0.1", testGrpcPort);
    }

    @AfterEach
    void cleanup() {
        if (consulRegistration != null && serviceName != null) {
            try {
                consulRegistration.deregisterService(serviceName + "-1");
            } catch (Exception ignore) {
                /* shutting down */
            }
        }
    }

    @Test
    @DisplayName("10 concurrent 50MB unary calls via raw async-callback stub — 0 silent drops")
    void tenConcurrentLargeUnaryCalls_asyncStub_noSilentDrops() throws Exception {
        // Use the raw grpc-java async-callback stub (no Mutiny wrapper).
        Channel channel = factory.getChannel(serviceName)
                .await().atMost(Duration.ofSeconds(10));
        GreeterGrpc.GreeterStub asyncStub = GreeterGrpc.newStub(channel);

        byte[] data = new byte[PAYLOAD_SIZE];
        Arrays.fill(data, (byte) 0x42);
        EchoBytesRequest request = EchoBytesRequest.newBuilder()
                .setPayload(ByteString.copyFrom(data))
                .build();

        long totalBytes = (long) PAYLOAD_SIZE * CONCURRENT_FAN_OUT;
        LOG.infof("=== %d concurrent %.0f MB async-stub unary calls (%.0f MB total) ===",
                CONCURRENT_FAN_OUT,
                PAYLOAD_SIZE / 1024.0 / 1024.0,
                totalBytes / 1024.0 / 1024.0);

        List<CallResult> results = new CopyOnWriteArrayList<>();
        AtomicInteger remaining = new AtomicInteger(CONCURRENT_FAN_OUT);
        CompletableFuture<Void> allDone = new CompletableFuture<>();

        ExecutorService vts = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("async-stub-vt-", 0).factory());

        long wallStart = System.nanoTime();
        for (int i = 0; i < CONCURRENT_FAN_OUT; i++) {
            final int idx = i;
            vts.submit(() -> {
                long t0 = System.nanoTime();
                try {
                    EchoBytesReply reply = invokeUnary(asyncStub, request);
                    long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
                    results.add(CallResult.ok(idx, elapsedMs, reply.getReceivedBytes()));
                } catch (Throwable t) {
                    long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
                    results.add(CallResult.fail(idx, elapsedMs, t));
                } finally {
                    if (remaining.decrementAndGet() == 0) {
                        allDone.complete(null);
                    }
                }
            });
        }
        vts.shutdown();
        allDone.get(WALL_BUDGET.toMillis(), TimeUnit.MILLISECONDS);
        long wallMs = (System.nanoTime() - wallStart) / 1_000_000;

        long ok = results.stream().filter(r -> r.error == null).count();
        long failures = results.size() - ok;
        long silentDrops = countSilentDrops(results);
        long truncations = results.stream()
                .filter(r -> r.error == null)
                .filter(r -> r.receivedBytes != PAYLOAD_SIZE)
                .count();

        double aggMbPerSec = (totalBytes / 1024.0 / 1024.0) / (wallMs / 1000.0);
        LOG.infof("Wall=%dms (%.0f MB/s)  OK=%d/%d  failures=%d  silentDrops=%d  truncations=%d",
                wallMs, aggMbPerSec, ok, CONCURRENT_FAN_OUT, failures, silentDrops, truncations);
        results.stream()
                .filter(r -> r.error != null)
                .limit(3)
                .forEach(r -> LOG.warnf("  call %d failed in %dms: %s",
                        r.index, r.elapsedMs, summarize(r.error)));

        assertThat(results)
                .as("must capture exactly one observation per call")
                .hasSize(CONCURRENT_FAN_OUT);
        assertThat(silentDrops)
                .as("raw async-callback path must not surface Half-closed silent drops "
                        + "on the migrated dynamic-grpc channel")
                .isZero();
        assertThat(truncations)
                .as("server received_bytes must match what client sent on every call")
                .isZero();
        assertThat(failures)
                .as("0 failures expected on a healthy local channel")
                .isZero();
    }

    /**
     * Standard grpc-java async-callback unary pattern: pass a
     * {@link StreamObserver} into the stub method, complete a
     * {@link CompletableFuture} from the callbacks, and block the
     * (virtual) thread on it. No Mutiny in the data path.
     */
    private static EchoBytesReply invokeUnary(GreeterGrpc.GreeterStub stub,
                                              EchoBytesRequest request) throws Exception {
        CompletableFuture<EchoBytesReply> respFuture = new CompletableFuture<>();
        stub.echoBytes(request, new StreamObserver<>() {
            @Override
            public void onNext(EchoBytesReply value) {
                if (!respFuture.isDone()) {
                    respFuture.complete(value);
                }
            }

            @Override
            public void onError(Throwable t) {
                respFuture.completeExceptionally(t);
            }

            @Override
            public void onCompleted() {
                if (!respFuture.isDone()) {
                    respFuture.completeExceptionally(
                            new IllegalStateException("server completed without sending a response"));
                }
            }
        });
        return respFuture.get(WALL_BUDGET.toMillis(), TimeUnit.MILLISECONDS);
    }

    private static long countSilentDrops(List<CallResult> results) {
        return results.stream()
                .filter(r -> r.error != null)
                .filter(AsyncStubLargePayloadTest::isSilentDropSignature)
                .count();
    }

    private static boolean isSilentDropSignature(CallResult r) {
        for (Throwable cur = r.error; cur != null; cur = cur.getCause()) {
            if (cur instanceof StatusRuntimeException sre
                    && sre.getStatus().getCode() == Status.Code.INTERNAL) {
                String msg = sre.getStatus().getDescription();
                if (msg != null && msg.contains("Half-closed without a request")) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String summarize(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName() + ": " + root.getMessage();
    }

    /** Standalone server impl: returns the byte count actually received. */
    static class EchoBytesGreeterService extends MutinyGreeterGrpc.GreeterImplBase {
        @Override
        public Uni<EchoBytesReply> echoBytes(EchoBytesRequest request) {
            return Uni.createFrom().item(
                    EchoBytesReply.newBuilder()
                            .setReceivedBytes(request.getPayload().size())
                            .build());
        }
    }

    private record CallResult(int index, long elapsedMs, long receivedBytes, Throwable error) {
        static CallResult ok(int idx, long ms, long received) {
            return new CallResult(idx, ms, received, null);
        }
        static CallResult fail(int idx, long ms, Throwable err) {
            return new CallResult(idx, ms, -1L, err);
        }
    }
}
