package ai.pipestream.quarkus.dynamicgrpc;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.Context;
import io.grpc.ForwardingClientCall;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;

/**
 * Pins outbound gRPC calls to {@link Context#ROOT} so a call that runs
 * inside an async loader (Caffeine cache, Mutiny chain, fire-and-forget VT)
 * does not get cancelled when its caller's gRPC server-side {@link Context}
 * unwinds.
 *
 * <p>The classic failure mode this prevents is
 * {@code CANCELLED: io.grpc.Context was cancelled without error} surfacing
 * inside a Caffeine async cache loader: the loader's gRPC call binds to
 * whatever {@link Context#current()} was at start, the calling Vertx /
 * gRPC handler finishes and cancels its Context, and the in-flight call
 * dies mid-way.
 *
 * <p>grpc-java captures {@link Context#current()} at
 * {@link ClientCall#start(ClientCall.Listener, Metadata)} time, NOT at
 * {@code newCall} time, so the interceptor must hop into {@code ROOT}
 * inside {@code start} — wrapping {@code newCall} alone would not move
 * the captured Context.
 */
public final class ContextDetachingInterceptor implements ClientInterceptor {

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
            MethodDescriptor<ReqT, RespT> method,
            CallOptions callOptions,
            Channel next) {
        return new ForwardingClientCall.SimpleForwardingClientCall<>(
                next.newCall(method, callOptions)) {
            @Override
            public void start(Listener<RespT> responseListener, Metadata headers) {
                Context previous = Context.ROOT.attach();
                try {
                    super.start(responseListener, headers);
                } finally {
                    Context.ROOT.detach(previous);
                }
            }
        };
    }
}
