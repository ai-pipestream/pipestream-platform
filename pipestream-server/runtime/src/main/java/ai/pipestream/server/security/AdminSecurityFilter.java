package ai.pipestream.server.security;

import io.grpc.Context;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;

import java.util.Collections;
import java.util.Set;

/**
 * JAX-RS filter that reads {@code x-account-id} from HTTP headers and populates
 * the same {@link io.grpc.Context} keys used by {@link PipestreamSecurityContext}.
 *
 * <p>This ensures that REST endpoints can share the same security context
 * abstraction as gRPC calls.
 */
@Provider
public class AdminSecurityFilter implements ContainerRequestFilter {

    private static final Logger LOG = Logger.getLogger(AdminSecurityFilter.class);

    @Inject
    PipestreamSecurityConfig config;

    @Override
    public void filter(ContainerRequestContext requestContext) {
        String accountId = requestContext.getHeaderString("x-account-id");
        boolean isAdmin = false;
        Set<String> roles = Collections.emptySet();

        if (accountId == null || accountId.isBlank()) {
            if (config.adminFallbackEnabled()) {
                accountId = "admin";
                isAdmin = true;
                roles = Set.of("admin");
                LOG.debugf("No x-account-id header on HTTP request; admin fallback active for %s %s",
                        requestContext.getMethod(), requestContext.getUriInfo().getRequestUri());
            } else {
                accountId = "";
            }
        } else {
            isAdmin = "admin".equals(accountId);
            roles = isAdmin ? Set.of("admin") : Collections.emptySet();
        }

        // Attach to gRPC Context so PipestreamSecurityContext.accountId() works
        Context grpcCtx = Context.current()
                .withValue(PipestreamSecurityContext.ACCOUNT_ID_KEY, accountId)
                .withValue(PipestreamSecurityContext.IS_ADMIN_KEY, isAdmin)
                .withValue(PipestreamSecurityContext.ROLES_KEY, roles);
        grpcCtx.attach();
    }
}
