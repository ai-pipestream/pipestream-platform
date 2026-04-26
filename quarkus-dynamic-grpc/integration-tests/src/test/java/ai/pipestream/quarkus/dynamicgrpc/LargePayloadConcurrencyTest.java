package ai.pipestream.quarkus.dynamicgrpc;

import ai.pipestream.quarkus.dynamicgrpc.base.ConsulServiceRegistration;
import ai.pipestream.quarkus.dynamicgrpc.it.proto.EchoBytesReply;
import ai.pipestream.quarkus.dynamicgrpc.it.proto.EchoBytesRequest;
import ai.pipestream.quarkus.dynamicgrpc.it.proto.MutinyGreeterGrpc;
import ai.pipestream.test.support.ConsulTestResource;
import com.google.protobuf.ByteString;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
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
 * Health check + soft regression guard for the silent-drop bug we hunted
 * across pipestream services for months: under direct-memory pressure
 * the Vert.x-backed gRPC client surfaced buffer-allocation failures as
 * {@code Status.INTERNAL "Half-closed without a request"} instead of a
 * clean {@code RESOURCE_EXHAUSTED} or {@code OutOfMemoryError}. The
 * misleading status pointed at the server framework, hiding the actual
 * client-side root cause for a long time.
 *
 * <h2>About this test's role</h2>
 * The deterministic in-build reproducer lived in the migration commit
 * itself — {@code -XX:MaxDirectMemorySize=256m} + the Vx-backed
 * {@code ChannelManager} → 7/10 calls landed silently as Half-closed.
 * Once the migration to {@code NettyChannelBuilder} +
 * {@link StorkNameResolverProvider} landed and direct-memory was bumped
 * to 2&nbsp;GB to match the production deployment shape (see
 * {@code integration-tests/build.gradle}), the bug's preconditions are
 * no longer met inside this test's JVM. So this test now functions as:
 * <ol>
 *   <li>A <i>healthy-channel smoke test</i>: 10 concurrent 50&nbsp;MB
 *       unary calls must complete cleanly through the Netty channel
 *       and {@link StorkNameResolverProvider}.</li>
 *   <li>A <i>signature-level guard</i>: if anything ever reintroduces
 *       the {@code "Half-closed without a request"} error pattern under
 *       any condition this test happens to hit, {@link #countSilentDrops}
 *       will catch it.</li>
 * </ol>
 *
 * <p>Sub-percent silent drop rates that would have been masked under
 * production-style memory caps are guarded separately by
 * {@code SustainedLoadDropDetectionTest} (1000 calls × 5&nbsp;MB).
 *
 * <h2>Setup</h2>
 * <ul>
 *   <li>Standalone {@link Server} (grpc-java + Netty) implementing
 *       {@code Greeter.EchoBytes} on a random local port — the same
 *       pattern the other dynamic-grpc ITs use.</li>
 *   <li>Service registered in Consul under a per-test logical name.</li>
 *   <li>Client side goes through {@link GrpcClientFactory}, exactly like
 *       production engine→module traffic does.</li>
 * </ul>
 *
 * <h2>Pass criteria</h2>
 * <ul>
 *   <li>{@link #countSilentDrops} == 0 — no result carrying the
 *       {@code INTERNAL "Half-closed without a request"} signature.</li>
 *   <li>10/10 calls succeed (since memory headroom is sufficient under
 *       the test's JVM args, any failure here points at a transport
 *       regression, not a memory-pressure artifact).</li>
 * </ul>
 *
 * <p><b>To re-witness the original bug</b>: temporarily set
 * {@code -XX:MaxDirectMemorySize=256m} in {@code integration-tests/build.gradle}
 * and revert {@link ChannelManager} to the Vx-backed
 * {@code StorkGrpcChannel + GrpcClient.client(vertx, ...)} path. The
 * 2026-04-25 migration commit's pre-fix run captured {@code silentDrops=3/10}.
 */
@QuarkusTest
@WithTestResource(ConsulTestResource.class)
public class LargePayloadConcurrencyTest {

    private static final Logger LOG = Logger.getLogger(LargePayloadConcurrencyTest.class);

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
        try (ServerSocket socket = new ServerSocket(0)) {
            testGrpcPort = socket.getLocalPort();
        }

        testGrpcServer = ServerBuilder.forPort(testGrpcPort)
                // 64MB max inbound matches the connector-intake-service
                // production setting; without this the server-side framework
                // would reject the 50MB request before transport pressure
                // ever shows up, masking the actual bug we're hunting.
                .maxInboundMessageSize(64 * 1024 * 1024)
                .addService(new EchoBytesGreeterService())
                .build()
                .start();

        LOG.infof("LargePayload test gRPC server started on port: %d", testGrpcPort);
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
        serviceName = "large-payload-" + UUID.randomUUID().toString().substring(0, 8);
        consulRegistration.registerService(serviceName, serviceName + "-1", "127.0.0.1", testGrpcPort);
        LOG.infof("Registered %s in Consul at 127.0.0.1:%d", serviceName, testGrpcPort);
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
    @DisplayName("10 concurrent 50MB unary calls must produce 0 silent drops (#51129 regression guard)")
    void tenConcurrentLargeUnaryCalls_noSilentDrops() throws Exception {
        MutinyGreeterGrpc.MutinyGreeterStub client = factory
                .getClient(serviceName, MutinyGreeterGrpc::newMutinyStub)
                .await().atMost(Duration.ofSeconds(10));

        // One identical 50MB request reused across all VTs. Protobuf is
        // immutable so sharing is safe and avoids 500MB of redundant
        // ByteString allocation churn.
        byte[] payloadBytes = new byte[PAYLOAD_SIZE];
        Arrays.fill(payloadBytes, (byte) 0x42);
        EchoBytesRequest request = EchoBytesRequest.newBuilder()
                .setPayload(ByteString.copyFrom(payloadBytes))
                .build();

        long totalBytes = (long) PAYLOAD_SIZE * CONCURRENT_FAN_OUT;
        LOG.infof("=== %d concurrent %.0f MB unary calls (%.0f MB total) via dynamic-grpc ===",
                CONCURRENT_FAN_OUT,
                PAYLOAD_SIZE / 1024.0 / 1024.0,
                totalBytes / 1024.0 / 1024.0);

        List<CallResult> results = new CopyOnWriteArrayList<>();
        AtomicInteger remaining = new AtomicInteger(CONCURRENT_FAN_OUT);
        CompletableFuture<Void> allDone = new CompletableFuture<>();

        ExecutorService vts = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("large-payload-vt-", 0).factory());

        long wallStart = System.nanoTime();
        for (int i = 0; i < CONCURRENT_FAN_OUT; i++) {
            final int idx = i;
            vts.submit(() -> {
                long t0 = System.nanoTime();
                try {
                    EchoBytesReply reply = client.echoBytes(request)
                            .await().atMost(WALL_BUDGET);
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

        LOG.infof("Wall=%dms  OK=%d/%d  failures=%d  silentDrops(Half-closed)=%d",
                wallMs, ok, CONCURRENT_FAN_OUT, failures, silentDrops);
        results.stream()
                .filter(r -> r.error != null)
                .limit(5)
                .forEach(r -> LOG.warnf("  call %d failed in %dms: %s",
                        r.index, r.elapsedMs, summarize(r.error)));

        assertThat(results)
                .as("must capture exactly one observation per call")
                .hasSize(CONCURRENT_FAN_OUT);

        // The bug: Vx-backed dynamic-grpc surfaces direct-buffer pressure
        // as INTERNAL "Half-closed without a request" instead of a clean
        // RESOURCE_EXHAUSTED. Today this is non-zero. After the Netty
        // migration it must be zero.
        assertThat(silentDrops)
                .as("Vx-backed dynamic-grpc masks direct-buffer OOM as INTERNAL "
                        + "\"Half-closed without a request\" — after the Netty migration "
                        + "this must be zero (failures with clear OOM/RESOURCE_EXHAUSTED status are fine)")
                .isZero();
    }

    /** Counts results whose error matches the silent-drop bug signature. */
    private static long countSilentDrops(List<CallResult> results) {
        return results.stream()
                .filter(r -> r.error != null)
                .filter(LargePayloadConcurrencyTest::isSilentDropSignature)
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

    /**
     * Standalone grpc-java service. Returns the byte count it actually
     * received so a truncation bug would surface as
     * {@code received_bytes != PAYLOAD_SIZE}, separate from the Half-closed
     * symptom we're hunting.
     */
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
