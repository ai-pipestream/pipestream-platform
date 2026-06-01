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
import io.grpc.stub.ClientCallStreamObserver;
import io.grpc.stub.ClientResponseObserver;
import io.grpc.stub.StreamObserver;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.mutiny.Multi;
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
 * Streaming-shaped sister of {@link LargePayloadConcurrencyTest}. Same
 * underlying concern — the silent-drop bug we hunted for months lived in
 * {@code BridgeMessageEncoder}, on the path that encodes outbound proto
 * messages — but client-streaming hits that path repeatedly per RPC
 * instead of once per RPC. If the bug ever re-emerges in a streaming-only
 * variant, the unary regression guard wouldn't catch it.
 *
 * <h2>Shape</h2>
 * <ul>
 *   <li>Standalone grpc-java {@link Server} on a random port hosting
 *       {@link UploadStreamGreeterService}.</li>
 *   <li>Service registered in Consul, resolved through
 *       {@link GrpcClientFactory} → migrated {@code ChannelManager} →
 *       Netty channel via {@link StorkNameResolverProvider}.</li>
 *   <li>{@link #STREAMS} concurrent virtual threads, each pushing
 *       {@link #MESSAGES_PER_STREAM} payload messages of
 *       {@link #PAYLOAD_SIZE} bytes onto its own client-streaming RPC,
 *       awaiting the server's single response on half-close.</li>
 * </ul>
 *
 * <h2>Pass criteria</h2>
 * <ul>
 *   <li>Every stream completes without {@code Half-closed without a request}
 *       (the silent-drop signature).</li>
 *   <li>Every stream's {@code response.received_bytes} equals what the
 *       client actually sent — catches mid-stream truncation that wouldn't
 *       fail the call but would silently corrupt PipeDoc payloads.</li>
 * </ul>
 */
@QuarkusTest
@WithTestResource(ConsulTestResource.class)
public class StreamingLargePayloadTest {

    private static final Logger LOG = Logger.getLogger(StreamingLargePayloadTest.class);

    private static final int STREAMS = 5;
    private static final int MESSAGES_PER_STREAM = 5;
    private static final int PAYLOAD_SIZE = 5 * 1024 * 1024;
    private static final long EXPECTED_PER_STREAM_BYTES = (long) PAYLOAD_SIZE * MESSAGES_PER_STREAM;
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
                .addService(new UploadStreamGreeterService())
                .build()
                .start();
        LOG.infof("Streaming test gRPC server started on port: %d", testGrpcPort);
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
        serviceName = "stream-payload-" + UUID.randomUUID().toString().substring(0, 8);
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
    @DisplayName("Concurrent client-streaming uploads must produce 0 silent drops or truncations")
    void concurrentStreamingUploads_noSilentDropsOrTruncations() throws Exception {
        // Use the raw async stub so we can drive client-streaming directly via
        // a ClientCallStreamObserver. (The Mutiny streaming surface hides the
        // ready-handler signal we need to honor HTTP/2 flow control properly.)
        Channel channel = factory.getChannel(serviceName)
                .await().atMost(Duration.ofSeconds(10));
        GreeterGrpc.GreeterStub asyncStub = GreeterGrpc.newStub(channel);

        // Warm the channel + server with one no-op stream so the first
        // measured stream isn't paying first-stream-allocation cost.
        runOneStream(asyncStub, /*streamIndex*/ -1, /*messages*/ 1);

        long totalBytes = (long) STREAMS * EXPECTED_PER_STREAM_BYTES;
        LOG.infof("=== %d concurrent streams, %d msgs/stream, %.0f MB/msg, %.0f MB total ===",
                STREAMS, MESSAGES_PER_STREAM,
                PAYLOAD_SIZE / 1024.0 / 1024.0,
                totalBytes / 1024.0 / 1024.0);

        List<StreamResult> results = new CopyOnWriteArrayList<>();
        AtomicInteger remaining = new AtomicInteger(STREAMS);
        CompletableFuture<Void> allDone = new CompletableFuture<>();

        ExecutorService vts = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("stream-vt-", 0).factory());

        long wallStart = System.nanoTime();
        for (int i = 0; i < STREAMS; i++) {
            final int idx = i;
            vts.submit(() -> {
                long t0 = System.nanoTime();
                try {
                    long received = runOneStream(asyncStub, idx, MESSAGES_PER_STREAM);
                    long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
                    results.add(StreamResult.ok(idx, elapsedMs, received));
                } catch (Throwable t) {
                    long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
                    results.add(StreamResult.fail(idx, elapsedMs, t));
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
                .filter(r -> r.receivedBytes != EXPECTED_PER_STREAM_BYTES)
                .count();

        LOG.infof("Wall=%dms  OK=%d/%d  failures=%d  silentDrops=%d  truncations=%d",
                wallMs, ok, STREAMS, failures, silentDrops, truncations);
        results.stream()
                .filter(r -> r.error != null)
                .limit(3)
                .forEach(r -> LOG.warnf("  stream %d failed in %dms: %s",
                        r.index, r.elapsedMs, summarize(r.error)));

        assertThat(results)
                .as("must capture exactly one observation per stream")
                .hasSize(STREAMS);
        assertThat(silentDrops)
                .as("Vx-encoder Half-closed silent drops must stay at 0 after the Netty migration")
                .isZero();
        assertThat(truncations)
                .as("server must report correct byte count per stream — mid-stream truncation "
                        + "would cause silent PipeDoc corruption in production")
                .isZero();
        assertThat(failures)
                .as("0 failures expected on a healthy local channel")
                .isZero();
    }

    /**
     * Drives one client-streaming RPC: opens, pushes {@code messages}
     * payloads honoring {@link ClientCallStreamObserver#isReady()}, then
     * half-closes and awaits the server's single reply. Returns the server's
     * reported {@code received_bytes}.
     */
    private long runOneStream(GreeterGrpc.GreeterStub stub, int idx, int messages) throws Exception {
        byte[] data = new byte[PAYLOAD_SIZE];
        Arrays.fill(data, (byte) 0x42);
        ByteString payload = ByteString.copyFrom(data);
        EchoBytesRequest msg = EchoBytesRequest.newBuilder().setPayload(payload).build();

        CompletableFuture<EchoBytesReply> respFuture = new CompletableFuture<>();
        java.util.concurrent.atomic.AtomicReference<ClientCallStreamObserver<EchoBytesRequest>> reqRef =
                new java.util.concurrent.atomic.AtomicReference<>();

        stub.uploadStream(new ClientResponseObserver<EchoBytesRequest, EchoBytesReply>() {
            @Override
            public void beforeStart(ClientCallStreamObserver<EchoBytesRequest> requestStream) {
                reqRef.set(requestStream);
            }

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

        ClientCallStreamObserver<EchoBytesRequest> reqObserver = reqRef.get();
        if (reqObserver == null) {
            throw new IllegalStateException("ClientResponseObserver.beforeStart never fired");
        }

        for (int m = 0; m < messages; m++) {
            // Honor HTTP/2 flow control — same pattern as the gRPC manual
            // flow-control docs. Without this, fast producers OOM the local
            // write buffer at scale (which is what the bug we just fixed
            // would silently mask).
            int sleeps = 0;
            while (!reqObserver.isReady()) {
                Thread.sleep(1);
                if (++sleeps > 60_000) {
                    throw new IllegalStateException("stream " + idx + " never became ready");
                }
            }
            reqObserver.onNext(msg);
        }
        reqObserver.onCompleted();

        EchoBytesReply reply = respFuture.get(WALL_BUDGET.toMillis(), TimeUnit.MILLISECONDS);
        return reply.getReceivedBytes();
    }

    private static long countSilentDrops(List<StreamResult> results) {
        return results.stream()
                .filter(r -> r.error != null)
                .filter(StreamingLargePayloadTest::isSilentDropSignature)
                .count();
    }

    private static boolean isSilentDropSignature(StreamResult r) {
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

    /** Standalone server impl: sums payload sizes across the stream. */
    static class UploadStreamGreeterService extends MutinyGreeterGrpc.GreeterImplBase {
        @Override
        public Uni<EchoBytesReply> uploadStream(Multi<EchoBytesRequest> request) {
            return request
                    .onItem().transform(req -> (long) req.getPayload().size())
                    .collect().with(java.util.stream.Collectors.summingLong(Long::longValue))
                    .onItem().transform(total -> EchoBytesReply.newBuilder()
                            .setReceivedBytes(total)
                            .build());
        }
    }

    private record StreamResult(int index, long elapsedMs, long receivedBytes, Throwable error) {
        static StreamResult ok(int idx, long ms, long received) {
            return new StreamResult(idx, ms, received, null);
        }
        static StreamResult fail(int idx, long ms, Throwable err) {
            return new StreamResult(idx, ms, -1L, err);
        }
    }
}
