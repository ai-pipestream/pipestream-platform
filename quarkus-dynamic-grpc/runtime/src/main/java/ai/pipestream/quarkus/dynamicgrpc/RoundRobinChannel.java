package ai.pipestream.quarkus.dynamicgrpc;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.MethodDescriptor;
import io.quarkus.grpc.runtime.stork.StorkGrpcChannel;
import io.vertx.grpc.client.GrpcClient;
import org.jboss.logging.Logger;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * A gRPC {@link Channel} that round-robins every {@code newCall} across N
 * underlying delegate channels. Each delegate holds its own Vert.x
 * {@code GrpcClient} → its own HTTP client pool → its own TCP/HTTP/2
 * connection(s) to each resolved service instance.
 *
 * <h2>Why</h2>
 * Vert.x's HTTP/2 client keeps one connection per host by default and
 * multiplexes every gRPC call onto it. On the receiving service that
 * single connection is handled by one Vert.x event loop, so inbound
 * framing serialises on one thread regardless of how many streams the
 * caller opens. With {@code N} round-robined channels the caller holds
 * {@code N} independent connections, fanning inbound work out across
 * {@code N} event loops on the receiver — the difference between one
 * pegged event loop and N busy ones.
 *
 * <h2>Lifecycle</h2>
 * {@link #close()} shuts down every delegate StorkGrpcChannel so the
 * enclosing {@link ChannelManager} can dispose the pool on cache
 * eviction without leaking connections.
 */
final class RoundRobinChannel extends Channel implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(RoundRobinChannel.class);

    private final Channel[] delegates;
    private final GrpcClient[] grpcClients;
    private final AtomicInteger counter = new AtomicInteger();

    RoundRobinChannel(Channel[] delegates, GrpcClient[] grpcClients) {
        if (delegates == null || delegates.length == 0) {
            throw new IllegalArgumentException("RoundRobinChannel requires at least one delegate");
        }
        if (grpcClients == null || grpcClients.length != delegates.length) {
            throw new IllegalArgumentException("grpcClients must be non-null and same length as delegates");
        }
        this.delegates = delegates;
        this.grpcClients = grpcClients;
    }

    int size() {
        return delegates.length;
    }

    @Override
    public <Req, Resp> ClientCall<Req, Resp> newCall(MethodDescriptor<Req, Resp> method,
                                                       CallOptions callOptions) {
        int i = Math.floorMod(counter.getAndIncrement(), delegates.length);
        return delegates[i].newCall(method, callOptions);
    }

    @Override
    public String authority() {
        return delegates[0].authority();
    }

    @Override
    public void close() {
        for (Channel c : delegates) {
            try {
                if (c instanceof StorkGrpcChannel sgc) {
                    sgc.close();
                } else if (c instanceof AutoCloseable ac) {
                    ac.close();
                }
            } catch (Exception e) {
                LOG.tracef("Error closing delegate channel: %s", e.getMessage());
            }
        }
        for (GrpcClient gc : grpcClients) {
            try {
                gc.close();
            } catch (Exception e) {
                LOG.tracef("Error closing GrpcClient: %s", e.getMessage());
            }
        }
    }

    @Override
    public String toString() {
        return "RoundRobinChannel{size=" + delegates.length + ", next="
                + Math.floorMod(counter.get(), delegates.length) + "}";
    }
}
