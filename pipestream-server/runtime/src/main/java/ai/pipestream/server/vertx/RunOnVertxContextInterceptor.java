package ai.pipestream.server.vertx;

import io.quarkus.vertx.core.runtime.context.VertxContextSafetyToggle;
import io.smallrye.common.vertx.VertxContext;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.vertx.core.Context;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;
import org.jboss.logging.Logger;

/**
 * CDI interceptor that subscribes {@code Uni}/{@code Multi} return values on
 * a Vert.x duplicated context, so downstream Hibernate Reactive code finds
 * a current Vert.x context regardless of which thread the gRPC handler was
 * invoked on. Activated by {@link RunOnVertxContext}.
 * <p>
 * No-ops when:
 * <ul>
 *   <li>there is already a current Vert.x context (no need to wrap)</li>
 *   <li>the method's return value is not a {@link Uni} or {@link Multi}</li>
 * </ul>
 * Both cases let synchronous and already-on-Vert.x calls flow through
 * without an extra event-loop hop.
 */
@RunOnVertxContext
@Interceptor
@Priority(Interceptor.Priority.PLATFORM_BEFORE)
public class RunOnVertxContextInterceptor {

    private static final Logger LOG = Logger.getLogger(RunOnVertxContextInterceptor.class);

    @Inject
    io.vertx.mutiny.core.Vertx vertx;

    @AroundInvoke
    public Object intercept(InvocationContext ctx) throws Exception {
        Object result = ctx.proceed();
        if (io.vertx.core.Vertx.currentContext() != null) {
            // Already on a Vert.x context — Hibernate Reactive will be happy.
            return result;
        }
        if (result instanceof Uni<?> uni) {
            return wrapUni(uni);
        }
        if (result instanceof Multi<?> multi) {
            return wrapMulti(multi);
        }
        return result;
    }

    private Uni<?> wrapUni(Uni<?> original) {
        Context safeContext = newSafeContext();
        // Two things have to happen across this hop:
        //   1. The runnable runs on a Vert.x event-loop thread so
        //      Hibernate Reactive sees a current Vert.x context.
        //   2. The runnable runs under io.grpc.Context.ROOT so any
        //      outbound gRPC client call inside the chain captures ROOT
        //      instead of the inbound call's Context (which may already
        //      be cancelled by the time the Vert.x thread picks up).
        // Without #2, SmallRye Context Propagation can restore the
        // inbound Context onto the Vert.x thread, and a subsequent
        // outbound stub call fails with
        // 'CANCELLED: io.grpc.Context was cancelled without error' —
        // the same bug pattern the engine had to address by dropping
        // Mutiny on its outbound call sites.
        return original.runSubscriptionOn(runnable -> safeContext.runOnContext(v ->
                io.grpc.Context.ROOT.run(runnable)));
    }

    private Multi<?> wrapMulti(Multi<?> original) {
        Context safeContext = newSafeContext();
        return original.runSubscriptionOn(runnable -> safeContext.runOnContext(v ->
                io.grpc.Context.ROOT.run(runnable)));
    }

    private Context newSafeContext() {
        // A duplicated context isolates request-scoped Vert.x locals (e.g. the
        // Hibernate Reactive session) from any sibling work happening on the
        // same root context — same pattern Quarkus's own VertxContextSupport uses.
        Context ctx = VertxContext.getOrCreateDuplicatedContext(vertx.getDelegate());
        VertxContextSafetyToggle.setContextSafe(ctx, true);
        return ctx;
    }
}
