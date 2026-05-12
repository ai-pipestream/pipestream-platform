package ai.pipestream.server.grpc.admission;

import io.grpc.ForwardingServerCall;
import io.grpc.ForwardingServerCallListener;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.quarkus.grpc.GlobalInterceptor;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Module-owned demand-based admission control for the data-plane gRPC
 * service ({@code ai.pipestream.data.module.v1.PipeStepProcessorService}).
 *
 * <p>The module declares how many concurrent {@code processData} calls it
 * can handle via the {@code pipestream.module.max-concurrent} config
 * property. When the in-flight count reaches that limit, additional
 * incoming calls are rejected immediately with
 * {@link Status#RESOURCE_EXHAUSTED} so the calling engine sees an
 * unambiguous "I am full" signal and can back off (the engine already
 * retries on {@code RESOURCE_EXHAUSTED} with exponential backoff).
 *
 * <p>This is the "module knows its own capacity" half of the
 * backpressure design described in
 * {@code pipestream-engine/docs/architecture/14-backpressure-and-admission.md}.
 * The engine has its own per-stream in-flight cap on the consumer side;
 * this interceptor adds the receiving-side cap so the module bounds its
 * own concurrency regardless of how many engine instances are sending,
 * how many streams feed it, or how aggressive the upstream batch is.
 *
 * <p><b>Scope of the gate:</b> only methods on the data-plane
 * {@code PipeStepProcessorService} are gated. Health checks, admin/registration
 * RPCs, reflection, and other infrastructure traffic pass through
 * unchanged — they are not the load source we are guarding against, and
 * blocking a liveness probe under load would defeat the point.
 *
 * <p><b>Disabled by default.</b> When {@code pipestream.module.max-concurrent}
 * is {@code 0} (the platform default), the interceptor is a no-op so
 * existing services see no behaviour change until they opt in. A module
 * opts in by adding a single line to its {@code application.properties}:
 * <pre>{@code
 * pipestream.module.max-concurrent=5
 * }</pre>
 * The value should reflect what the module can <em>actually</em> sustain
 * in parallel (semantic-graph and embedder typically ~5; chunker/parser
 * higher). Calibration methodology lives in the engine repo's
 * backpressure design doc.
 *
 * <p>Registered via {@link GlobalInterceptor} so it applies to every
 * gRPC server method without per-service annotation. Permit release is
 * idempotent (an AtomicBoolean per call) so the same permit cannot be
 * released twice if both {@code close()} and {@code onCancel()} fire,
 * and so a release in the error path of {@code next.startCall} does not
 * double-release.
 */
@ApplicationScoped
@GlobalInterceptor
public class ConcurrencyGateInterceptor implements ServerInterceptor {

    private static final Logger LOG = Logger.getLogger(ConcurrencyGateInterceptor.class);

    /**
     * Fully-qualified gRPC service name of the data-plane processor.
     * Only methods on this service are gated; everything else (health,
     * admin, reflection) passes through.
     */
    private static final String GATED_SERVICE = "ai.pipestream.data.module.v1.PipeStepProcessorService";

    @ConfigProperty(name = "pipestream.module.max-concurrent", defaultValue = "0")
    int maxConcurrent;

    /**
     * Optional informational tag exposed in the rejection message so an
     * operator looking at engine retry logs can tell which module hit its
     * cap. Defaults to the registered service name when present.
     */
    @ConfigProperty(name = "pipestream.registration.service-name", defaultValue = "module")
    String moduleName;

    private Semaphore permits;

    /**
     * Counts incoming attempts and rejections. Logged on first reject and
     * periodically thereafter so flap is visible without spamming. The
     * counters are best-effort — under contention some increments can race,
     * but the magnitude is what matters, not the exact tally.
     */
    private final AtomicLong attempts = new AtomicLong();
    private final AtomicLong rejections = new AtomicLong();

    @PostConstruct
    void init() {
        if (maxConcurrent <= 0) {
            LOG.infof("ConcurrencyGateInterceptor disabled (pipestream.module.max-concurrent=%d) on module=%s",
                    maxConcurrent, moduleName);
            permits = null;
            return;
        }
        permits = new Semaphore(maxConcurrent, true);
        LOG.infof("ConcurrencyGateInterceptor enabled: module=%s max-concurrent=%d (RESOURCE_EXHAUSTED returned past this)",
                moduleName, maxConcurrent);
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call,
            Metadata headers,
            ServerCallHandler<ReqT, RespT> next) {

        // Only gate data-plane calls. Health checks, admin, reflection,
        // and registration traffic pass through unchanged — they are not
        // the saturation source and blocking liveness probes when busy
        // would just make a struggling module look dead to Consul.
        String service = call.getMethodDescriptor().getServiceName();
        if (permits == null || service == null || !service.equals(GATED_SERVICE)) {
            return next.startCall(call, headers);
        }

        attempts.incrementAndGet();
        if (!permits.tryAcquire()) {
            long rejected = rejections.incrementAndGet();
            // Log first rejection at INFO, subsequent at DEBUG so a busy
            // module doesn't flood the log. The engine sees the gRPC
            // status code regardless and that's the actionable signal.
            if (rejected == 1 || rejected % 100 == 0) {
                LOG.infof("ConcurrencyGate REJECT: module=%s in-flight=%d/%d total-rejects=%d method=%s",
                        moduleName, maxConcurrent, maxConcurrent, rejected,
                        call.getMethodDescriptor().getFullMethodName());
            } else {
                LOG.debugf("ConcurrencyGate REJECT (cumulative %d) method=%s",
                        rejected, call.getMethodDescriptor().getFullMethodName());
            }
            call.close(
                    Status.RESOURCE_EXHAUSTED
                            .withDescription("module " + moduleName
                                    + " at concurrency limit (" + maxConcurrent + ")"),
                    new Metadata());
            // Return a no-op listener; the call is already closed so no
            // request bytes from the client should be observed past this
            // point. gRPC frameworks handle the rest.
            return new ServerCall.Listener<>() {};
        }

        // Permit acquired. Wrap both the outgoing ServerCall and the
        // returned Listener so that whichever path terminates the call
        // (handler-driven close, client cancel, transport error) releases
        // exactly once.
        AtomicBoolean released = new AtomicBoolean(false);
        Runnable releaseOnce = () -> {
            if (released.compareAndSet(false, true)) {
                permits.release();
            }
        };

        ServerCall<ReqT, RespT> wrappedCall =
                new ForwardingServerCall.SimpleForwardingServerCall<>(call) {
                    @Override
                    public void close(Status status, Metadata trailers) {
                        try {
                            super.close(status, trailers);
                        } finally {
                            releaseOnce.run();
                        }
                    }
                };

        try {
            ServerCall.Listener<ReqT> delegate = next.startCall(wrappedCall, headers);
            return new ForwardingServerCallListener.SimpleForwardingServerCallListener<>(delegate) {
                @Override
                public void onCancel() {
                    try {
                        super.onCancel();
                    } finally {
                        releaseOnce.run();
                    }
                }

                @Override
                public void onComplete() {
                    // Note: in practice close() on the wrapped call has
                    // already released the permit by the time onComplete
                    // fires. The double-release guard makes this safe;
                    // this override exists so a handler that completes
                    // without ever invoking close() still releases.
                    try {
                        super.onComplete();
                    } finally {
                        releaseOnce.run();
                    }
                }
            };
        } catch (RuntimeException e) {
            // If next.startCall threw, the handler never started and the
            // listener path will not run. Release the permit here so we
            // do not slowly leak permits on a misbehaving handler.
            releaseOnce.run();
            throw e;
        }
    }
}
