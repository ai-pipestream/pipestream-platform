package ai.pipestream.server.vertx;

import jakarta.interceptor.InterceptorBinding;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a CDI bean (or method) whose {@code Uni}-returning calls must be
 * subscribed on a Vert.x duplicated context.
 * <p>
 * Needed for Hibernate Reactive Panache services that run under the
 * separate Netty gRPC server: the gRPC handler thread is a Netty
 * event-loop thread without a current Vert.x context, and Hibernate
 * Reactive throws {@code IllegalStateException: No current Vertx context found}
 * the moment it tries to open a session. With the unified Vert.x server
 * the handler ran on a Vert.x event loop natively; under a separate
 * Netty server we have to dispatch onto a Vert.x context ourselves.
 * <p>
 * Apply at the class level on a Mutiny gRPC service implementation that
 * uses Hibernate Reactive Panache (or any other component that requires
 * a Vert.x context):
 * <pre>{@code
 * @ApplicationScoped
 * @RunOnVertxContext
 * public class MyServiceImpl extends MutinyMyServiceGrpc.MyServiceImplBase {
 *     // every Uni returned by this class is subscribed on a safe duplicated
 *     // Vert.x context, so Hibernate Reactive sees a current context.
 * }
 * }</pre>
 * Cheap when there is already a current Vert.x context: the interceptor
 * detects that case and returns the original Uni unchanged.
 */
@InterceptorBinding
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Inherited
public @interface RunOnVertxContext {
}
