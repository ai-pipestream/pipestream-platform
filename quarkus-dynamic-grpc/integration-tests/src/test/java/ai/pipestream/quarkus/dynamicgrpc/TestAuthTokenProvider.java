package ai.pipestream.quarkus.dynamicgrpc;

import ai.pipestream.quarkus.dynamicgrpc.auth.AuthTokenProvider;
import io.quarkus.arc.profile.IfBuildProfile;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Test implementation of AuthTokenProvider for integration tests.
 * Only active when the "auth-test" profile is enabled.
 */
@ApplicationScoped
@IfBuildProfile("auth-test")
public class TestAuthTokenProvider implements AuthTokenProvider {

    public static final String TEST_TOKEN = "test-jwt-token-12345";

    @Override
    public String getToken() {
        return TEST_TOKEN;
    }
}
