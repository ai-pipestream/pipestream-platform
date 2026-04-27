package ai.pipestream.server.deployment;

import ai.pipestream.server.vertx.RunOnVertxContext;
import io.grpc.Context;
import io.quarkus.test.QuarkusUnitTest;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test for the CANCELLED-context class of bugs that brought
 * down the engine and is now biting reactive-Panache services.
 * <p>
 * Symptom: a gRPC handler returns a Uni; the {@code @RunOnVertxContext}
 * interceptor reschedules subscription onto a Vert.x event-loop thread;
 * inside that handler, code makes an outbound gRPC call which fails with
 * {@code CANCELLED: io.grpc.Context was cancelled without error}. Root
 * cause: when subscription hops to a different thread, the new thread's
 * {@code io.grpc.Context.current()} is whatever was last set there — a
 * leftover cancelled Context from a prior request, or the dispatcher's
 * Context that has since been torn down. Outbound clients use
 * {@code Context.current()} to attach to the call, so they inherit the
 * stale cancellation and immediately fail.
 * <p>
 * The fix is to re-root the gRPC Context across the executor hop, the
 * same {@code Context.ROOT.run(...)} pattern the engine had to adopt
 * after dropping Mutiny on its hot paths. This test simulates the
 * exact pattern: capture {@code Context.current().isCancelled()} from
 * inside the Uni's subscription closure on the Vert.x thread. If the
 * interceptor correctly re-roots, it sees a non-cancelled Context;
 * if it doesn't, it sees a cancelled one — the same conditions that
 * make {@code accountValidationService.validateAccountExistsAndActive(...)}
 * fail in connector-admin's createDataSource.
 */
class RunOnVertxContextGrpcContextTest {

    @RegisterExtension
    static final QuarkusUnitTest TEST = new QuarkusUnitTest()
            .overrideConfigKey("quarkus.http.port", "0")
            .overrideConfigKey("quarkus.http.test-port", "0")
            .overrideConfigKey("quarkus.grpc.server.port", "0")
            .overrideConfigKey("pipestream.registration.required", "false")
            .setArchiveProducer(() -> ShrinkWrap.create(JavaArchive.class)
                    .addClass(GrpcContextProbeBean.class));

    @Inject
    GrpcContextProbeBean probe;

    @Test
    void subscriptionDoesNotInheritCancelledGrpcContext() throws Exception {
        // Build a CancellableContext to simulate the inbound gRPC call's
        // Context. We'll attach it on the calling thread (mimicking the
        // gRPC dispatcher), invoke the bean (which constructs a Uni
        // while Context is still attached — SmallRye Context
        // Propagation captures it here), then cancel the Context to
        // mimic the dispatcher's Context.run(...) block ending.
        Context.CancellableContext inboundContext = Context.current().withCancellation();

        AtomicReference<Boolean> seenCancelled = new AtomicReference<>();
        AtomicReference<Context> capturedContext = new AtomicReference<>();

        // Construct the Uni while Context is attached so any captured
        // ThreadContext snapshot includes the inbound Context.
        Uni<Void> uni = inboundContext.call(() ->
                probe.captureGrpcContext(seenCancelled, capturedContext));

        // Now simulate the inbound call ending: cancel the Context
        // BEFORE the Uni gets subscribed and reaches Vert.x event loop.
        // Without the interceptor's Context.ROOT.run(...) wrap, the
        // restored Context on the Vert.x thread will be this cancelled
        // one — the same flow that breaks outbound gRPC clients in
        // production.
        inboundContext.cancel(new RuntimeException("simulated inbound-call torn-down"));
        assertThat(inboundContext.isCancelled())
                .as("precondition: simulated inbound Context cancelled")
                .isTrue();

        uni.await().atMost(Duration.ofSeconds(5));

        assertThat(capturedContext.get())
                .as("subscription must observe a current grpc Context")
                .isNotNull();
        assertThat(seenCancelled.get())
                .as("subscription saw io.grpc.Context as CANCELLED — the symptom that makes "
                        + "outbound gRPC clients (e.g. AccountValidationService) fail with "
                        + "'io.grpc.Context was cancelled without error'. The @RunOnVertxContext "
                        + "interceptor needs to re-root the grpc Context across the Vert.x "
                        + "event-loop hop (Context.ROOT.run(...) wrap inside runSubscriptionOn) — "
                        + "same fix the engine had to adopt when it dropped Mutiny on its hot paths.")
                .isFalse();
    }

    @ApplicationScoped
    @RunOnVertxContext
    public static class GrpcContextProbeBean {
        public Uni<Void> captureGrpcContext(AtomicReference<Boolean> seenCancelled,
                                            AtomicReference<Context> capturedContext) {
            return Uni.createFrom().item(() -> {
                Context current = Context.current();
                capturedContext.set(current);
                seenCancelled.set(current.isCancelled());
                return (Void) null;
            });
        }
    }
}
