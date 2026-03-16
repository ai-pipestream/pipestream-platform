package ai.pipestream.server.security;

import io.grpc.Context;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.Collections;
import java.util.Set;

/**
 * Reads security identity from {@link io.grpc.Context} — works identically for gRPC and HTTP
 * because both the gRPC interceptor and the JAX-RS filter populate the same context keys.
 */
@ApplicationScoped
public class PipestreamSecurityContext {

    private static final Logger LOG = Logger.getLogger(PipestreamSecurityContext.class);

    public static final Context.Key<String> ACCOUNT_ID_KEY = Context.key("pipestream-account-id");
    public static final Context.Key<Boolean> IS_ADMIN_KEY = Context.key("pipestream-is-admin");
    public static final Context.Key<Set<String>> ROLES_KEY = Context.key("pipestream-roles");

    public String accountId() {
        String id = ACCOUNT_ID_KEY.get();
        return id != null ? id : "";
    }

    public boolean isAdmin() {
        Boolean admin = IS_ADMIN_KEY.get();
        return admin != null && admin;
    }

    public Set<String> roles() {
        Set<String> r = ROLES_KEY.get();
        return r != null ? r : Collections.emptySet();
    }

    public boolean isAuthenticated() {
        return !accountId().isEmpty();
    }
}
