package ai.pipestream.server.deployment;

import ai.pipestream.server.vertx.RunOnVertxContext;
import io.quarkus.test.QuarkusUnitTest;
import io.smallrye.mutiny.Uni;
import io.vertx.core.Context;
import io.vertx.core.Vertx;
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
 * Locks in CDI bean discovery for the {@link RunOnVertxContext}
 * interceptor.
 * <p>
 * Bug context: when use-separate-server=true (the platform default after
 * the engine-refactor migration), gRPC handlers in services like
 * connector-admin and opensearch-manager run on Netty event-loop threads
 * with no current Vert.x context, and Hibernate Reactive hangs the
 * moment it tries to open a session. The {@code @RunOnVertxContext}
 * interceptor wraps {@code Uni}/{@code Multi} return values to subscribe
 * on a Vert.x duplicated context.
 * <p>
 * The interceptor only fires when CDI bean discovery picks it up — for a
 * Quarkus extension that means an explicit {@code AdditionalBeanBuildItem}
 * registration in the deployment processor. This test guards against
 * that registration regressing: a probe bean annotated with
 * {@code @RunOnVertxContext} must observe a non-null current context at
 * Uni-subscription time, even though the call is initiated from a thread
 * that has none.
 */
class RunOnVertxContextInterceptorTest {

    @RegisterExtension
    static final QuarkusUnitTest TEST = new QuarkusUnitTest()
            // Random ephemeral ports — process-compose may have the
            // platform defaults bound on this machine.
            .overrideConfigKey("quarkus.http.port", "0")
            .overrideConfigKey("quarkus.http.test-port", "0")
            .overrideConfigKey("quarkus.grpc.server.port", "0")
            // We don't want the test app to actually call out to a
            // platform-registration service.
            .overrideConfigKey("pipestream.registration.required", "false")
            .setArchiveProducer(() -> ShrinkWrap.create(JavaArchive.class)
                    .addClass(ContextProbeBean.class));

    @Inject
    ContextProbeBean probe;

    @Test
    void interceptorIsRegistered_uniSubscribesOnVertxContext() {
        AtomicReference<Context> capturedContext = new AtomicReference<>();
        AtomicReference<String> capturedThread = new AtomicReference<>();

        // Subscribe from a non-Vert.x thread (the JUnit caller) so that
        // any Vert.x context observed downstream MUST come from the
        // interceptor's runSubscriptionOn dispatch.
        probe.captureContext(capturedContext, capturedThread)
                .await().atMost(Duration.ofSeconds(5));

        assertThat(capturedContext.get())
                .as("@RunOnVertxContext should run the Uni's subscription on a Vert.x duplicated context, "
                        + "but no current context was visible at subscription time. "
                        + "Most likely the interceptor is missing from PipestreamServerProcessor's "
                        + "AdditionalBeanBuildItem registration — without that, CDI never discovers it "
                        + "and Hibernate Reactive blows up on every reactive-service gRPC call.")
                .isNotNull();

        assertThat(capturedThread.get())
                .as("dispatch should be on a Vert.x event-loop thread, not the test thread")
                .startsWith("vert.x");
    }

    @ApplicationScoped
    @RunOnVertxContext
    public static class ContextProbeBean {
        public Uni<Void> captureContext(AtomicReference<Context> ctx, AtomicReference<String> thread) {
            // Build a Uni whose subscription captures the runtime
            // execution context. If the interceptor is absent, this
            // runs on the caller's thread (no Vert.x context).
            return Uni.createFrom().item(() -> {
                ctx.set(Vertx.currentContext());
                thread.set(Thread.currentThread().getName());
                return (Void) null;
            });
        }
    }
}
