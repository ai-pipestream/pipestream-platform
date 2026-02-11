package ai.pipestream.apicurio.registry.protobuf.runtime;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.HealthCheckResponseBuilder;
import org.eclipse.microprofile.health.Readiness;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Readiness health check that verifies the Apicurio Registry is reachable.
 * Pings the registry's /health endpoint and reports DOWN if the registry
 * is unavailable, preventing Kafka producers from failing with opaque errors.
 */
@Readiness
@ApplicationScoped
public class ApicurioRegistryHealthCheck implements HealthCheck {

    private static final Duration TIMEOUT = Duration.ofSeconds(3);

    @ConfigProperty(name = "apicurio.registry.url", defaultValue = "http://localhost:8081/apis/registry/v3")
    String registryUrl;

    @Override
    public HealthCheckResponse call() {
        HealthCheckResponseBuilder builder = HealthCheckResponse.named("Apicurio Registry");
        String healthUrl = deriveHealthUrl(registryUrl);
        builder.withData("url", healthUrl);

        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(TIMEOUT)
                    .build();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(healthUrl))
                    .timeout(TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200 && response.body().contains("\"UP\"")) {
                builder.up();
            } else {
                builder.down();
                builder.withData("status", response.statusCode());
            }
        } catch (Exception e) {
            builder.down();
            builder.withData("error", e.getMessage());
        }
        return builder.build();
    }

    /**
     * Derives the health endpoint URL from the registry API URL.
     * Strips /apis/registry/v3 (or v2) suffix and appends /health.
     */
    static String deriveHealthUrl(String registryUrl) {
        String base = registryUrl;
        int idx = base.indexOf("/apis/registry/");
        if (idx > 0) {
            base = base.substring(0, idx);
        }
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/health";
    }
}
