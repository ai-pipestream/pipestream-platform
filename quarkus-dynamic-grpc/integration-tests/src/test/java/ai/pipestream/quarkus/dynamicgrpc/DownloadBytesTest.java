package ai.pipestream.quarkus.dynamicgrpc;

import ai.pipestream.quarkus.dynamicgrpc.base.ConsulServiceRegistration;
import ai.pipestream.quarkus.dynamicgrpc.it.proto.DownloadBytesReply;
import ai.pipestream.quarkus.dynamicgrpc.it.proto.DownloadBytesRequest;
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
 * Server-to-client direction analogue of
 * {@link LargePayloadConcurrencyTest}. Today's regression guard pushes
 * 50&nbsp;MB FROM client TO server; that's the wire path the silent-drop
 * bug originally surfaced on. The opposite direction — server returns a
 * large response — exercises a different code path: the client's
 * inbound read buffers, gRPC's message-decoding flow control, and
 * {@code maxInboundMessageSize}. A regression in the response path
 * wouldn't be caught by the unary-upload test.
 *
 * <h2>Pass criteria</h2>
 * <ul>
 *   <li>0 errors across the concurrent fan-out.</li>
 *   <li>Every response's {@code payload.size()} matches the requested
 *       {@code size_bytes} — short reads would silently corrupt PipeDocs.</li>
 * </ul>
 */
@QuarkusTest
@WithTestResource(ConsulTestResource.class)
public class DownloadBytesTest {

    private static final Logger LOG = Logger.getLogger(DownloadBytesTest.class);

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
                // Server's outbound size cap. We deliberately keep this at
                // 64MB to match production-shape settings — we want the
                // CLIENT inbound limit to be the thing controlling the
                // boundary, not the server outbound.
                .maxInboundMessageSize(64 * 1024 * 1024)
                .addService(new DownloadBytesGreeterService())
                .build()
                .start();
        LOG.infof("DownloadBytes test gRPC server started on port: %d", testGrpcPort);
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
        serviceName = "download-bytes-" + UUID.randomUUID().toString().substring(0, 8);
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
    @DisplayName("10 concurrent 50MB downloads — exact size match, no silent truncation")
    void tenConcurrentLargeDownloads_exactSizeMatch() throws Exception {
        MutinyGreeterGrpc.MutinyGreeterStub client = factory
                .getClient(serviceName, MutinyGreeterGrpc::newMutinyStub)
                .await().atMost(Duration.ofSeconds(10));

        DownloadBytesRequest request = DownloadBytesRequest.newBuilder()
                .setSizeBytes(PAYLOAD_SIZE)
                .build();

        long totalBytes = (long) PAYLOAD_SIZE * CONCURRENT_FAN_OUT;
        LOG.infof("=== %d concurrent server-side %.0f MB downloads (%.0f MB total) ===",
                CONCURRENT_FAN_OUT,
                PAYLOAD_SIZE / 1024.0 / 1024.0,
                totalBytes / 1024.0 / 1024.0);

        List<DownloadResult> results = new CopyOnWriteArrayList<>();
        AtomicInteger remaining = new AtomicInteger(CONCURRENT_FAN_OUT);
        CompletableFuture<Void> allDone = new CompletableFuture<>();

        ExecutorService vts = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("download-vt-", 0).factory());

        long wallStart = System.nanoTime();
        for (int i = 0; i < CONCURRENT_FAN_OUT; i++) {
            final int idx = i;
            vts.submit(() -> {
                long t0 = System.nanoTime();
                try {
                    DownloadBytesReply reply = client.downloadBytes(request)
                            .await().atMost(WALL_BUDGET);
                    long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
                    results.add(DownloadResult.ok(idx, elapsedMs, reply.getPayload()));
                } catch (Throwable t) {
                    long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
                    results.add(DownloadResult.fail(idx, elapsedMs, t));
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
        long shortReads = results.stream()
                .filter(r -> r.error == null)
                .filter(r -> r.payload != null && r.payload.size() != PAYLOAD_SIZE)
                .count();

        double aggMbPerSec = (totalBytes / 1024.0 / 1024.0) / (wallMs / 1000.0);
        LOG.infof("Wall=%dms (%.0f MB/s aggregate)  OK=%d/%d  failures=%d  shortReads=%d",
                wallMs, aggMbPerSec, ok, CONCURRENT_FAN_OUT, failures, shortReads);

        assertThat(failures)
                .as("0 failures expected on the response path under direct-memory-tuned VM")
                .isZero();
        assertThat(shortReads)
                .as("every response.payload.size() must equal the requested PAYLOAD_SIZE — "
                        + "a short read would mean the gRPC framing layer silently truncated "
                        + "an inbound message, which would corrupt PipeDocs in production")
                .isZero();
    }

    /** Standalone server impl: returns a server-built payload of requested size. */
    static class DownloadBytesGreeterService extends MutinyGreeterGrpc.GreeterImplBase {
        @Override
        public Uni<DownloadBytesReply> downloadBytes(DownloadBytesRequest request) {
            byte[] data = new byte[request.getSizeBytes()];
            Arrays.fill(data, (byte) 0x37);
            return Uni.createFrom().item(
                    DownloadBytesReply.newBuilder()
                            .setPayload(ByteString.copyFrom(data))
                            .build());
        }
    }

    private record DownloadResult(int index, long elapsedMs, ByteString payload, Throwable error) {
        static DownloadResult ok(int idx, long ms, ByteString payload) {
            return new DownloadResult(idx, ms, payload, null);
        }
        static DownloadResult fail(int idx, long ms, Throwable err) {
            return new DownloadResult(idx, ms, null, err);
        }
    }
}
