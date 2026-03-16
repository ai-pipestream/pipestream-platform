package ai.pipestream.server.security;

import io.grpc.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Collections;
import java.util.Set;

/**
 * gRPC server interceptor that reads {@code x-account-id} from metadata
 * and populates {@link io.grpc.Context} keys for {@link PipestreamSecurityContext}.
 *
 * <p>In dev mode with {@code pipestream.security.admin-fallback-enabled=true},
 * a missing header defaults to an admin identity.
 */
@ApplicationScoped
public class AdminSecurityInterceptor implements ServerInterceptor {

    private static final Logger LOG = Logger.getLogger(AdminSecurityInterceptor.class);
    private static final Metadata.Key<String> ACCOUNT_ID_HEADER =
            Metadata.Key.of("x-account-id", Metadata.ASCII_STRING_MARSHALLER);

    @Inject
    PipestreamSecurityConfig config;

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call,
            Metadata headers,
            ServerCallHandler<ReqT, RespT> next) {

        String accountId = headers.get(ACCOUNT_ID_HEADER);
        boolean isAdmin = false;
        Set<String> roles = Collections.emptySet();

        if (accountId == null || accountId.isBlank()) {
            if (config.adminFallbackEnabled()) {
                accountId = "admin";
                isAdmin = true;
                roles = Set.of("admin");
                LOG.debugf("No x-account-id header; admin fallback active for %s",
                        call.getMethodDescriptor().getFullMethodName());
            } else {
                accountId = "";
            }
        } else {
            // "admin" account ID is treated as admin
            isAdmin = "admin".equals(accountId);
            roles = isAdmin ? Set.of("admin") : Collections.emptySet();
        }

        Context ctx = Context.current()
                .withValue(PipestreamSecurityContext.ACCOUNT_ID_KEY, accountId)
                .withValue(PipestreamSecurityContext.IS_ADMIN_KEY, isAdmin)
                .withValue(PipestreamSecurityContext.ROLES_KEY, roles);

        return Contexts.interceptCall(ctx, call, headers, next);
    }
}
