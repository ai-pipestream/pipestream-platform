package ai.pipestream.test.support;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import com.redis.testcontainers.RedisContainer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Shared test resource for setting up Infisical/KMS container for secret management testing.
 * <p>
 * Provides Infisical instance for testing KMS credential storage and retrieval.
 * Can be used by any service that needs KMS testing infrastructure.
 */
public class KmsTestResource implements QuarkusTestResourceLifecycleManager {

    private static final String DEFAULT_IMAGE = "infisical/infisical:latest";
    private static final String ADMIN_EMAIL = "admin@pipestream.local";
    private static final String ADMIN_PASSWORD = "admin-password-change-me";
    private static final String ENCRYPTION_KEY = "d57fe96e9ac0b03e50e2e078bb916d63";
    private static final String AUTH_SECRET = "PgviZ6tQbBwPKgv4JqsjqKec9XEfh0iCFPlSHrnV0jM=";

    private GenericContainer<?> infisical;
    private RedisContainer redis;
    private String apiUrl;
    private String authToken;

    @Override
    public Map<String, String> start() {
        // Start Redis (required by Infisical)
        redis = new RedisContainer(DockerImageName.parse("redis:8-alpine"));
        redis.start();

        String redisUrl = redis.getRedisURI();

        // Start Infisical
        infisical = new GenericContainer<>(DockerImageName.parse(DEFAULT_IMAGE))
                .withExposedPorts(3000, 8080)
                .withEnv("REDIS_URL", redisUrl)
                .withEnv("ENCRYPTION_KEY", ENCRYPTION_KEY)
                .withEnv("AUTH_SECRET", AUTH_SECRET)
                .withEnv("SITE_URL", "http://infisical:8080")
                .waitingFor(Wait.forHttp("/").forPort(8080).withStartupTimeout(Duration.ofSeconds(120)));

        infisical.start();

        apiUrl = "http://" + infisical.getHost() + ":" + infisical.getMappedPort(8080);

        // Wait for Infisical to be ready and initialize admin account
        waitForInfisical();
        initializeAdmin();

        Map<String, String> config = new HashMap<>();
        config.put("kms.api.url", apiUrl);
        config.put("kms.api.token", authToken != null ? authToken : "");
        return config;
    }

    private void waitForInfisical() {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        for (int i = 0; i < 30; i++) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(apiUrl + "/"))
                        .timeout(Duration.ofSeconds(5))
                        .GET()
                        .build();

                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200 || response.statusCode() == 404) {
                    return; // Server is responding
                }
            } catch (Exception e) {
                // Continue waiting
            }

            try {
                TimeUnit.SECONDS.sleep(2);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void initializeAdmin() {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        try {
            // Try to sign in first
            HttpRequest signInRequest = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl + "/api/v1/auth/signin"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            String.format("{\"email\":\"%s\",\"password\":\"%s\"}", ADMIN_EMAIL, ADMIN_PASSWORD)))
                    .build();

            HttpResponse<String> signInResponse = client.send(signInRequest, HttpResponse.BodyHandlers.ofString());

            if (signInResponse.statusCode() == 200) {
                // Admin exists, extract token from response
                // Note: Actual token extraction would depend on Infisical API response format
                authToken = "admin-token"; // Placeholder - would extract from JSON response
                return;
            }

            // Admin doesn't exist, create it
            HttpRequest signUpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl + "/api/v1/auth/signup-admin"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            String.format("{\"email\":\"%s\",\"password\":\"%s\",\"firstName\":\"Admin\",\"lastName\":\"User\"}",
                                    ADMIN_EMAIL, ADMIN_PASSWORD)))
                    .build();

            HttpResponse<String> signUpResponse = client.send(signUpRequest, HttpResponse.BodyHandlers.ofString());

            if (signUpResponse.statusCode() == 200 || signUpResponse.statusCode() == 201) {
                // Admin created, now sign in to get token
                HttpResponse<String> newSignInResponse = client.send(signInRequest, HttpResponse.BodyHandlers.ofString());
                if (newSignInResponse.statusCode() == 200) {
                    authToken = "admin-token"; // Placeholder - would extract from JSON response
                }
            }
        } catch (Exception e) {
            // Log but don't fail - KMS might not be critical for all tests
        }
    }

    /**
     * Get the Infisical API URL.
     *
     * @return API URL or null if container is not running
     */
    public String getApiUrl() {
        return apiUrl;
    }

    /**
     * Get the authentication token for Infisical API.
     *
     * @return auth token or null if not authenticated
     */
    public String getAuthToken() {
        return authToken;
    }

    /**
     * Store a secret in KMS.
     * <p>
     * This is a placeholder method - actual implementation would use Infisical API
     * to store secrets in projects/environments.
     *
     * @param key secret key/name
     * @param value secret value
     * @return KMS reference that can be used to retrieve the secret
     */
    public String storeSecret(String key, String value) {
        // Placeholder - would use Infisical API to store secret
        // Return format: "kms://project/environment/secret-name"
        return "kms://test-project/test-environment/" + key;
    }

    /**
     * Retrieve a secret from KMS using a KMS reference.
     *
     * @param kmsRef KMS reference (e.g., "kms://project/environment/secret-name")
     * @return secret value or null if not found
     */
    public String retrieveSecret(String kmsRef) {
        // Placeholder - would use Infisical API to retrieve secret
        return null;
    }

    @Override
    public void stop() {
        if (infisical != null) {
            infisical.stop();
        }
        if (redis != null) {
            redis.stop();
        }
    }
}
