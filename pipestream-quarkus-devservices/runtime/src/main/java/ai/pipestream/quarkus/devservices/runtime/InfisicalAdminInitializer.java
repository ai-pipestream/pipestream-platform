package ai.pipestream.quarkus.devservices.runtime;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Automatically initializes Infisical admin account on first startup.
 * <p>
 * This bean is only registered in dev and test modes via build-time configuration.
 * It will not be instantiated in production builds.
 * </p>
 */
@ApplicationScoped
public class InfisicalAdminInitializer {

    private static final Logger LOG = Logger.getLogger(InfisicalAdminInitializer.class);

    private final InfisicalConfig config;
    private final HttpClient httpClient;

    @Inject
    public InfisicalAdminInitializer(InfisicalConfig config) {
        this.config = config;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    void onStart(@Observes StartupEvent ev) {
        // Safety check - should never happen in prod due to build-time exclusion,
        // but adding as defensive programming
        String profile = System.getProperty("quarkus.profile", "dev");
        if ("prod".equals(profile)) {
            LOG.debug("Infisical admin setup skipped in prod profile");
            return;
        }

        Optional<Boolean> autoSetup = config.adminAutoSetup();
        if (autoSetup.isEmpty() || !autoSetup.get()) {
            LOG.debug("Infisical admin auto-setup is disabled");
            return;
        }

        LOG.info("Infisical admin auto-setup enabled - checking and initializing admin account");

        // Run asynchronously to not block application startup
        CompletableFuture.runAsync(this::initializeAdmin)
                .exceptionally(throwable -> {
                    LOG.warn("Failed to initialize Infisical admin account", throwable);
                    return null;
                });
    }

    private void initializeAdmin() {
        try {
            // Wait for Infisical to be healthy
            if (!waitForInfisical()) {
                LOG.warn("Infisical did not become healthy within timeout - skipping admin setup");
                return;
            }

            // Check if admin already exists
            if (adminExists()) {
                LOG.info("Infisical admin account already exists - skipping creation");
                return;
            }

            // Create admin account
            createAdmin();

        } catch (Exception e) {
            LOG.warn("Error during Infisical admin initialization", e);
        }
    }

    private boolean waitForInfisical() {
        String apiUrl = config.apiUrl().orElse("http://infisical:8080");
        int maxRetries = config.healthCheckRetries().orElse(30);
        int delaySeconds = config.healthCheckDelaySeconds().orElse(2);

        LOG.debugf("Waiting for Infisical to be healthy at %s (max %d retries, %d sec delay)",
                apiUrl, maxRetries, delaySeconds);

        for (int i = 0; i < maxRetries; i++) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(apiUrl + "/"))
                        .timeout(Duration.ofSeconds(5))
                        .GET()
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200 || response.statusCode() == 404) {
                    // 404 is OK - means server is responding (route not found is expected)
                    LOG.debugf("Infisical is healthy (status: %d)", response.statusCode());
                    return true;
                }
            } catch (Exception e) {
                LOG.debugf("Infisical health check attempt %d/%d failed: %s", i + 1, maxRetries, e.getMessage());
            }

            try {
                TimeUnit.SECONDS.sleep(delaySeconds);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        return false;
    }

    private boolean adminExists() {
        String apiUrl = config.apiUrl().orElse("http://infisical:8080");
        String email = config.adminEmail().orElse("admin@pipestream.local");

        try {
            // Check if we can login with the admin credentials
            // Infisical API: POST /api/v1/auth/signup-admin or check /api/v1/users/me
            // For now, we'll try to sign in - if it fails with "user not found", admin doesn't exist
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl + "/api/v1/auth/signin"))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            String.format("{\"email\":\"%s\",\"password\":\"%s\"}",
                                    email, config.adminPassword().orElse("admin-password-change-me"))))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            // If login succeeds (200), admin exists
            // If it fails with 401/404, admin doesn't exist
            if (response.statusCode() == 200) {
                LOG.debug("Admin account exists (login successful)");
                return true;
            } else {
                LOG.debugf("Admin account check: login failed with status %d", response.statusCode());
                return false;
            }
        } catch (Exception e) {
            LOG.debugf("Error checking if admin exists: %s", e.getMessage());
            // Assume admin doesn't exist if we can't check
            return false;
        }
    }

    private void createAdmin() {
        String apiUrl = config.apiUrl().orElse("http://infisical:8080");
        String email = config.adminEmail().orElse("admin@pipestream.local");
        String password = config.adminPassword().orElse("admin-password-change-me");

        try {
            LOG.infof("Creating Infisical admin account: %s", email);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl + "/api/v1/auth/signup-admin"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            String.format("{\"email\":\"%s\",\"password\":\"%s\",\"firstName\":\"Admin\",\"lastName\":\"User\"}",
                                    email, password)))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200 || response.statusCode() == 201) {
                LOG.infof("Successfully created Infisical admin account: %s", email);
            } else {
                LOG.warnf("Failed to create Infisical admin account: status %d, response: %s",
                        response.statusCode(), response.body());
            }
        } catch (Exception e) {
            LOG.warnf("Error creating Infisical admin account: %s", e.getMessage());
        }
    }
}

