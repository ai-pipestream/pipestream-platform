package ai.pipestream.quarkus.dynamicgrpc;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.MethodDescriptor;
import io.vertx.core.Future;
import io.vertx.grpc.client.GrpcClient;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link RoundRobinChannel}. No Quarkus / no real gRPC — pure
 * structural correctness so we can guarantee the round-robin contract that
 * {@code ChannelManager.createChannelSync} relies on for fan-out across
 * multiple HTTP/2 connections per service.
 *
 * <p>Lives in the same package as {@code RoundRobinChannel} so it can use
 * the package-private constructor directly.</p>
 */
class RoundRobinChannelTest {

    /**
     * Every {@code newCall} must land on the next delegate in a strict
     * round-robin sequence. With 4 delegates and 4000 calls each delegate
     * must receive exactly 1000.
     */
    @Test
    void newCall_distributesEvenlyAcrossDelegates() {
        int n = 4;
        int callsPerDelegate = 1000;
        CountingChannel[] delegates = countingChannels(n);
        AtomicInteger[] closeCounts = new AtomicInteger[n];
        GrpcClient[] clients = fakeGrpcClients(n, closeCounts);

        RoundRobinChannel rr = new RoundRobinChannel(delegates, clients);

        int total = n * callsPerDelegate;
        for (int i = 0; i < total; i++) {
            rr.newCall(null, CallOptions.DEFAULT);
        }

        for (int i = 0; i < n; i++) {
            assertThat(delegates[i].newCallCount.get())
                    .as("delegate %d should have received exactly %d calls", i, callsPerDelegate)
                    .isEqualTo(callsPerDelegate);
        }
    }

    /**
     * Concurrent {@code newCall}s from many threads must still distribute
     * evenly. Using {@code AtomicInteger.getAndIncrement()} + {@code floorMod}
     * the bucket assignment is atomic, so even under contention every delegate
     * must receive the same count.
     */
    @Test
    void newCall_isThreadSafeUnderConcurrency() throws InterruptedException {
        int n = 6;
        int threads = 24;
        int callsPerThread = 5_000;
        int totalCalls = threads * callsPerThread;
        // totalCalls must divide evenly by n for the strict equality assertion below
        assertThat(totalCalls % n).isZero();

        CountingChannel[] delegates = countingChannels(n);
        GrpcClient[] clients = fakeGrpcClients(n, new AtomicInteger[n]);
        RoundRobinChannel rr = new RoundRobinChannel(delegates, clients);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < callsPerThread; i++) {
                        rr.newCall(null, CallOptions.DEFAULT);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();

        int expectedPerDelegate = totalCalls / n;
        long sum = 0;
        for (int i = 0; i < n; i++) {
            int actual = delegates[i].newCallCount.get();
            sum += actual;
            assertThat(actual)
                    .as("delegate %d under concurrency", i)
                    .isEqualTo(expectedPerDelegate);
        }
        assertThat(sum).isEqualTo(totalCalls);
    }

    @Test
    void constructor_rejectsEmptyDelegates() {
        assertThatThrownBy(() -> new RoundRobinChannel(new Channel[0], new GrpcClient[0]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one delegate");
        assertThatThrownBy(() -> new RoundRobinChannel(null, new GrpcClient[0]))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void constructor_rejectsMismatchedClientsLength() {
        Channel[] delegates = countingChannels(3);
        GrpcClient[] tooFewClients = fakeGrpcClients(2, new AtomicInteger[2]);
        assertThatThrownBy(() -> new RoundRobinChannel(delegates, tooFewClients))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("same length");
        assertThatThrownBy(() -> new RoundRobinChannel(delegates, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * {@code close()} must call {@code close()} on every delegate that
     * implements {@code AutoCloseable} AND on every {@code GrpcClient}.
     * If we don't release everything, evicting a pool from the
     * {@code ChannelManager} cache leaks N HTTP/2 connection pools.
     */
    @Test
    void close_closesAllDelegatesAndClients() throws Exception {
        int n = 5;
        AtomicInteger[] delegateCloseCounts = new AtomicInteger[n];
        Channel[] delegates = closeableChannels(n, delegateCloseCounts);
        AtomicInteger[] clientCloseCounts = new AtomicInteger[n];
        GrpcClient[] clients = fakeGrpcClients(n, clientCloseCounts);

        RoundRobinChannel rr = new RoundRobinChannel(delegates, clients);
        rr.close();

        for (int i = 0; i < n; i++) {
            assertThat(delegateCloseCounts[i].get())
                    .as("delegate %d should be closed exactly once", i).isEqualTo(1);
            assertThat(clientCloseCounts[i].get())
                    .as("grpcClient %d should be closed exactly once", i).isEqualTo(1);
        }
    }

    /**
     * If one delegate throws on close, {@code close()} must still close the
     * remaining delegates and all GrpcClients — no resource leaks on partial
     * failure. The exception is logged, not propagated.
     */
    @Test
    void close_continuesEvenWhenADelegateThrows() {
        AtomicInteger[] closeCounts = {new AtomicInteger(), new AtomicInteger(), new AtomicInteger()};
        Channel[] delegates = new Channel[]{
                closeableChannel(closeCounts[0], false),
                closeableChannel(closeCounts[1], true),  // throws on close
                closeableChannel(closeCounts[2], false),
        };
        AtomicInteger[] clientCloses = new AtomicInteger[3];
        GrpcClient[] clients = fakeGrpcClients(3, clientCloses);

        RoundRobinChannel rr = new RoundRobinChannel(delegates, clients);
        rr.close();

        assertThat(closeCounts[0].get()).as("delegate 0 closed").isEqualTo(1);
        assertThat(closeCounts[1].get()).as("delegate 1 attempted close").isEqualTo(1);
        assertThat(closeCounts[2].get()).as("delegate 2 closed despite earlier throw").isEqualTo(1);
        for (int i = 0; i < 3; i++) {
            assertThat(clientCloses[i].get())
                    .as("grpcClient %d still closed after delegate failure", i).isEqualTo(1);
        }
    }

    @Test
    void authority_returnsFirstDelegateAuthority() {
        Channel[] delegates = new Channel[]{
                channelWithAuthority("first.example.com:443"),
                channelWithAuthority("second.example.com:443"),
        };
        GrpcClient[] clients = fakeGrpcClients(2, new AtomicInteger[2]);
        RoundRobinChannel rr = new RoundRobinChannel(delegates, clients);

        assertThat(rr.authority()).isEqualTo("first.example.com:443");
    }

    @Test
    void size_returnsDelegateCount() {
        Channel[] delegates = countingChannels(7);
        GrpcClient[] clients = fakeGrpcClients(7, new AtomicInteger[7]);
        RoundRobinChannel rr = new RoundRobinChannel(delegates, clients);
        assertThat(rr.size()).isEqualTo(7);
    }

    // --------------------------------------------------------------------- //
    // helpers
    // --------------------------------------------------------------------- //

    /** Channel that just counts {@code newCall} invocations. */
    private static class CountingChannel extends Channel {
        final AtomicInteger newCallCount = new AtomicInteger();

        @Override
        public <Req, Resp> ClientCall<Req, Resp> newCall(MethodDescriptor<Req, Resp> method,
                                                         CallOptions callOptions) {
            newCallCount.incrementAndGet();
            return null;
        }

        @Override
        public String authority() {
            return "counting-channel";
        }
    }

    private static CountingChannel[] countingChannels(int n) {
        CountingChannel[] arr = new CountingChannel[n];
        for (int i = 0; i < n; i++) {
            arr[i] = new CountingChannel();
        }
        return arr;
    }

    /** Channel that's also AutoCloseable so RoundRobinChannel.close() exercises the close path. */
    private static Channel closeableChannel(AtomicInteger closeCount, boolean throwOnClose) {
        return new CloseableCountingChannel(closeCount, throwOnClose);
    }

    private static Channel[] closeableChannels(int n, AtomicInteger[] outCloseCounts) {
        Channel[] arr = new Channel[n];
        for (int i = 0; i < n; i++) {
            outCloseCounts[i] = new AtomicInteger();
            arr[i] = new CloseableCountingChannel(outCloseCounts[i], false);
        }
        return arr;
    }

    private static class CloseableCountingChannel extends Channel implements AutoCloseable {
        final AtomicInteger closeCount;
        final boolean throwOnClose;

        CloseableCountingChannel(AtomicInteger closeCount, boolean throwOnClose) {
            this.closeCount = closeCount;
            this.throwOnClose = throwOnClose;
        }

        @Override
        public <Req, Resp> ClientCall<Req, Resp> newCall(MethodDescriptor<Req, Resp> m, CallOptions o) {
            return null;
        }

        @Override
        public String authority() {
            return "closeable-channel";
        }

        @Override
        public void close() {
            closeCount.incrementAndGet();
            if (throwOnClose) {
                throw new RuntimeException("simulated delegate close failure");
            }
        }
    }

    private static Channel channelWithAuthority(String authority) {
        return new Channel() {
            @Override
            public <Req, Resp> ClientCall<Req, Resp> newCall(MethodDescriptor<Req, Resp> m, CallOptions o) {
                return null;
            }
            @Override
            public String authority() {
                return authority;
            }
        };
    }

    /**
     * Build a fake {@link GrpcClient} via dynamic proxy. We only need
     * {@code close()} to be observable; everything else returns {@code null}
     * (RoundRobinChannel never calls anything else on the client).
     */
    private static GrpcClient[] fakeGrpcClients(int n, AtomicInteger[] outCloseCounts) {
        GrpcClient[] clients = new GrpcClient[n];
        for (int i = 0; i < n; i++) {
            AtomicInteger counter = new AtomicInteger();
            outCloseCounts[i] = counter;
            clients[i] = (GrpcClient) Proxy.newProxyInstance(
                    GrpcClient.class.getClassLoader(),
                    new Class<?>[]{GrpcClient.class},
                    (proxy, method, args) -> {
                        if ("close".equals(method.getName())) {
                            counter.incrementAndGet();
                            return Future.succeededFuture();
                        }
                        return null;
                    });
        }
        return clients;
    }
}
