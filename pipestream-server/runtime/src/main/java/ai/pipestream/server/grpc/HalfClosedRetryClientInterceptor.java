package ai.pipestream.server.grpc;

import io.grpc.Attributes;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCallListener;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import io.quarkus.grpc.GlobalInterceptor;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

/**
 * Platform-wide gRPC client interceptor that papers over the Quarkus
 * {@code BlockingServerInterceptor} race condition fixed by
 * <a href="https://github.com/quarkusio/quarkus/pull/54084">quarkus#54084</a>.
 *
 * <p><b>Problem.</b> When the server's blocking interceptor defers
 * {@code next.startCall} onto a backlogged worker / virtual thread, the
 * HTTP/2 deframer can deliver {@code onHalfClose} before the application
 * listener is allowed to see {@code onMessage}. The underlying
 * {@code UnaryServerCallListener} then sees a half-close with
 * {@code request == null} and the call is closed with
 * {@code Status.INTERNAL: Half-closed without a request}. The application
 * handler <i>never runs</i>, so a fresh attempt with the same request is
 * safe.
 *
 * <p><b>Trigger.</b> Anything that backlogs the VT / worker executor:
 * heavy bulk-OpenSearch tests, large batch Kafka consumes, the first call
 * after a long-idle period. The race shows up as flaky tests on the
 * <i>first</i> gRPC call after the backlog.
 *
 * <p><b>Workaround.</b> This interceptor catches exactly that status code
 * plus description on <b>unary</b> calls and retries the call once. The
 * retry creates a new underlying {@link ClientCall} and replays the
 * original headers, request message, and half-close. By the second
 * attempt the VT executor has caught up and the race window is closed.
 *
 * <p><b>Scope.</b>
 * <ul>
 *   <li>Only unary RPCs (the bug doesn't trigger on long-lived streams,
 *       and replaying every streamed message would be invasive).</li>
 *   <li>Only the exact {@code INTERNAL: Half-closed without a request}
 *       signature. Any other failure passes through unchanged so real
 *       application errors aren't masked.</li>
 *   <li>One retry maximum. A second failure propagates the original
 *       status to the caller.</li>
 * </ul>
 *
 * <p>Marked {@link GlobalInterceptor} so Quarkus wires it onto every
 * {@code @GrpcClient}-injected channel in every service that depends on
 * {@code pipestream-server} — no per-stub configuration.
 *
 * <p><b>Removal.</b> Delete this class once the pipestream-bom is
 * upgraded to a Quarkus release that includes PR #54084. The retry will
 * then be a no-op (the target status never fires), but keeping it adds
 * latency on any real INTERNAL failure that happens to share the
 * description — so prefer to remove it.
 */
@ApplicationScoped
@GlobalInterceptor
public class HalfClosedRetryClientInterceptor implements ClientInterceptor {

    private static final Logger LOG = Logger.getLogger(HalfClosedRetryClientInterceptor.class);

    /**
     * Server-emitted status description that identifies the
     * {@code BlockingServerInterceptor} race condition. Compared with
     * {@link String#equals} on {@link Status#getDescription()} so we
     * don't accidentally retry other INTERNAL failures that share the
     * status code but not the precise wording.
     */
    private static final String TARGET_DESCRIPTION = "Half-closed without a request";

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
            MethodDescriptor<ReqT, RespT> method,
            CallOptions callOptions,
            Channel next) {
        if (method.getType() != MethodDescriptor.MethodType.UNARY) {
            return next.newCall(method, callOptions);
        }
        return new RetryingUnaryCall<>(method, callOptions, next);
    }

    /**
     * Unary call wrapper that buffers the request and, on
     * {@link #TARGET_DESCRIPTION}, transparently re-issues the call once
     * with the same headers + payload.
     */
    private static final class RetryingUnaryCall<ReqT, RespT> extends ClientCall<ReqT, RespT> {

        private final MethodDescriptor<ReqT, RespT> method;
        private final CallOptions callOptions;
        private final Channel channel;

        // Set in start() — must survive long enough to replay.
        private Listener<RespT> applicationListener;
        private Metadata applicationHeaders;
        private int requestedMessages;

        // Set in sendMessage() — must survive long enough to replay.
        // gRPC unary calls send exactly one message; we buffer it.
        private ReqT bufferedRequest;
        private boolean halfClosed;
        private boolean cancelled;

        // Single retry budget — we never retry more than once.
        private boolean retryConsumed;

        // The underlying call that is currently live. Swapped on retry.
        private ClientCall<ReqT, RespT> delegate;

        RetryingUnaryCall(MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel channel) {
            this.method = method;
            this.callOptions = callOptions;
            this.channel = channel;
        }

        @Override
        public void start(Listener<RespT> responseListener, Metadata headers) {
            this.applicationListener = responseListener;
            this.applicationHeaders = headers;
            this.delegate = channel.newCall(method, callOptions);
            this.delegate.start(wrap(responseListener), copyHeaders(headers));
        }

        @Override
        public void request(int numMessages) {
            this.requestedMessages += numMessages;
            delegate.request(numMessages);
        }

        @Override
        public void cancel(String message, Throwable cause) {
            this.cancelled = true;
            delegate.cancel(message, cause);
        }

        @Override
        public void halfClose() {
            this.halfClosed = true;
            delegate.halfClose();
        }

        @Override
        public void sendMessage(ReqT message) {
            this.bufferedRequest = message;
            delegate.sendMessage(message);
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setMessageCompression(boolean enabled) {
            delegate.setMessageCompression(enabled);
        }

        @Override
        public Attributes getAttributes() {
            return delegate.getAttributes();
        }

        private Listener<RespT> wrap(Listener<RespT> downstream) {
            return new ForwardingClientCallListener.SimpleForwardingClientCallListener<RespT>(downstream) {
                @Override
                public void onClose(Status status, Metadata trailers) {
                    if (shouldRetry(status)) {
                        retryConsumed = true;
                        LOG.debugf("Retrying %s once due to server-emitted %s (Quarkus PR #54084 workaround)",
                                method.getFullMethodName(), TARGET_DESCRIPTION);
                        try {
                            replay();
                            return; // retry inherits the close; do not propagate the first one
                        } catch (RuntimeException retryFailure) {
                            LOG.warnf(retryFailure,
                                    "Retry of %s failed to start; surfacing the original failure",
                                    method.getFullMethodName());
                            // fall through to propagate the original status
                        }
                    }
                    super.onClose(status, trailers);
                }
            };
        }

        private boolean shouldRetry(Status status) {
            if (retryConsumed || cancelled) return false;
            if (status.getCode() != Status.Code.INTERNAL) return false;
            return TARGET_DESCRIPTION.equals(status.getDescription());
        }

        private void replay() {
            // Build a fresh call against the same channel; reuse the wrapping
            // listener so a second Half-closed (extremely unlikely) just
            // propagates as a normal failure on the retry budget.
            ClientCall<ReqT, RespT> retryDelegate = channel.newCall(method, callOptions);
            this.delegate = retryDelegate;
            retryDelegate.start(wrap(applicationListener), copyHeaders(applicationHeaders));
            if (requestedMessages > 0) {
                retryDelegate.request(requestedMessages);
            }
            if (bufferedRequest != null) {
                retryDelegate.sendMessage(bufferedRequest);
            }
            if (halfClosed) {
                retryDelegate.halfClose();
            }
        }

        /**
         * Defensive copy — gRPC may mutate headers as part of attempt #1
         * (e.g. adding tracing entries), and we don't want those carrying
         * into the retry. {@link Metadata#merge(Metadata)} is the public
         * API for cloning a headers block.
         */
        private static Metadata copyHeaders(Metadata src) {
            Metadata copy = new Metadata();
            copy.merge(src);
            return copy;
        }
    }
}
