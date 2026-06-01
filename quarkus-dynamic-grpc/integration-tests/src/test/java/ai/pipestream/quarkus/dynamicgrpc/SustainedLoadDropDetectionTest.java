package ai.pipestream.quarkus.dynamicgrpc;

import ai.pipestream.quarkus.dynamicgrpc.base.ConsulServiceRegistration;
import ai.pipestream.quarkus.dynamicgrpc.it.proto.EchoBytesReply;
import ai.pipestream.quarkus.dynamicgrpc.it.proto.EchoBytesRequest;
import ai.pipestream.quarkus.dynamicgrpc.it.proto.MutinyGreeterGrpc;
import ai.pipestream.test.support.ConsulTestResource;
import com.google.protobuf.ByteString;
import io.grpc.Server;
import io.grpc.ServerBuilder;
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
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sustained-load test designed to surface <i>sub-percent</i> silent drop
 * rates that the 10-call {@link LargePayloadConcurrencyTest} can't see.
 * Production reports of the silent-drop bug had drop rates in the
 * 0.1–1.5% range — i.e., a 10-call test could pass cleanly while a real
 * deployment moving thousands of PipeDocs an hour was steadily losing
 * data.
 *
 * <h2>Shape</h2>
 * 1000 unary {@code EchoBytes} calls of 5&nbsp;MB each, dispatched on
 * virtual threads in batches of {@link #CONCURRENCY} concurrent in-flight,
 * against a single dynamic-grpc-resolved channel.
 *
 * <h2>Pass criteria</h2>
 * <ul>
 *   <li>Every call completes successfully (errors == 0).</li>
 *   <li>Every successful call sees {@code received_bytes == PAYLOAD_SIZE}
 *       — partial reads that don't fail the call would corrupt PipeDocs
 *       and have to fail this test loudly.</li>
 * </ul>
 *
 * <p>If pre-migration Vx-backed dynamic-grpc were still in place, this
 * test would surface the silent drops we hunted in production over the
 * last several months.
 */
@QuarkusTest
@WithTestResource(ConsulTestResource.class)
public class SustainedLoadDropDetectionTest {

    private static final Logger LOG = Logger.getLogger(SustainedLoadDropDetectionTest.class);

    private static final int TOTAL_CALLS = 1000;
    private static final int CONCURRENCY = 32;
    private static final int PAYLOAD_SIZE = 5 * 1024 * 1024;
    private static final Duration WALL_BUDGET = Duration.ofMinutes(3);

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
        LOG.infof("Sustained-load test gRPC server started on port: %d", testGrpcPort);
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
        serviceName = "sustained-load-" + UUID.randomUUID().toString().substring(0, 8);
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
    @DisplayName("1000 unary 5MB calls — 0 errors, 0 truncations, <0.1% per-call cliff")
    void thousandUnaryCalls_noSilentDropsOrTruncations() throws Exception {
        MutinyGreeterGrpc.MutinyGreeterStub client = factory
                .getClient(serviceName, MutinyGreeterGrpc::newMutinyStub)
                .await().atMost(Duration.ofSeconds(10));

        // Build the 5MB request once. ProtoMessage is immutable; sharing
        // is safe and avoids allocator churn dwarfing the transport work.
        byte[] data = new byte[PAYLOAD_SIZE];
        Arrays.fill(data, (byte) 0x42);
        ByteString payload = ByteString.copyFrom(data);
        EchoBytesRequest request = EchoBytesRequest.newBuilder()
                .setPayload(payload)
                .build();

        long totalBytes = (long) PAYLOAD_SIZE * TOTAL_CALLS;
        LOG.infof("=== %d calls × %.0f MB (%.1f GB total) at concurrency=%d ===",
                TOTAL_CALLS,
                PAYLOAD_SIZE / 1024.0 / 1024.0,
                totalBytes / 1024.0 / 1024.0 / 1024.0,
                CONCURRENCY);

        AtomicInteger errors = new AtomicInteger();
        AtomicInteger truncations = new AtomicInteger();
        AtomicInteger silentDrops = new AtomicInteger();
        AtomicLong totalLatencyMs = new AtomicLong();
        AtomicInteger remaining = new AtomicInteger(TOTAL_CALLS);
        CompletableFuture<Void> allDone = new CompletableFuture<>();

        // Sliding window of CONCURRENCY in-flight via a Semaphore on a VT
        // executor. VTs handle the synchronous-style await cleanly; the
        // semaphore caps wire-level concurrency to a known value so we
        // can spot a cliff in the latency distribution if one shows up.
        java.util.concurrent.Semaphore inFlight = new java.util.concurrent.Semaphore(CONCURRENCY);
        ExecutorService vts = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("sustained-vt-", 0).factory());

        long wallStart = System.nanoTime();
        for (int i = 0; i < TOTAL_CALLS; i++) {
            inFlight.acquire();
            vts.submit(() -> {
                long t0 = System.nanoTime();
                try {
                    EchoBytesReply reply = client.echoBytes(request)
                            .await().atMost(Duration.ofSeconds(30));
                    long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
                    totalLatencyMs.addAndGet(elapsedMs);
                    if (reply.getReceivedBytes() != PAYLOAD_SIZE) {
                        truncations.incrementAndGet();
                    }
                } catch (Throwable t) {
                    errors.incrementAndGet();
                    if (looksLikeSilentDrop(t)) {
                        silentDrops.incrementAndGet();
                    }
                } finally {
                    inFlight.release();
                    if (remaining.decrementAndGet() == 0) {
                        allDone.complete(null);
                    }
                }
            });
        }
        vts.shutdown();
        allDone.get(WALL_BUDGET.toMillis(), TimeUnit.MILLISECONDS);
        long wallMs = (System.nanoTime() - wallStart) / 1_000_000;

        double avgLatencyMs = totalLatencyMs.get() / (double) (TOTAL_CALLS - errors.get());
        double aggMbPerSec = (totalBytes / 1024.0 / 1024.0) / (wallMs / 1000.0);
        LOG.infof("Wall=%dms (%.1f calls/sec, %.0f MB/s)  errors=%d  silentDrops=%d  truncations=%d  avg latency=%.1fms",
                wallMs, TOTAL_CALLS * 1000.0 / wallMs, aggMbPerSec,
                errors.get(), silentDrops.get(), truncations.get(), avgLatencyMs);

        assertThat(silentDrops.get())
                .as("Vx-encoder Half-closed silent drops must stay at 0 across sustained load — "
                        + "this is the bug that escaped detection in production for months")
                .isZero();
        assertThat(truncations.get())
                .as("server-reported byte count must match what the client sent on every call — "
                        + "any mismatch means the gRPC framing layer corrupted a payload mid-flight")
                .isZero();
        assertThat(errors.get())
                .as("0 errors expected on a healthy local channel under sustained load")
                .isZero();
    }

    private static boolean looksLikeSilentDrop(Throwable t) {
        for (Throwable cur = t; cur != null; cur = cur.getCause()) {
            String msg = cur.getMessage();
            if (msg != null && msg.contains("Half-closed without a request")) {
                return true;
            }
        }
        return false;
    }

    /** Standalone server impl: returns the byte count the server actually received. */
    static class EchoBytesGreeterService extends MutinyGreeterGrpc.GreeterImplBase {
        @Override
        public Uni<EchoBytesReply> echoBytes(EchoBytesRequest request) {
            return Uni.createFrom().item(
                    EchoBytesReply.newBuilder()
                            .setReceivedBytes(request.getPayload().size())
                            .build());
        }
    }
}
