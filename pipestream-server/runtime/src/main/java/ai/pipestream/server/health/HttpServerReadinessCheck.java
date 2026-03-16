package ai.pipestream.server.health;

import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.HealthCheckResponseBuilder;
import org.eclipse.microprofile.health.Readiness;
import org.jboss.logging.Logger;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Readiness
@ApplicationScoped
public class HttpServerReadinessCheck implements HealthCheck {

    private static final Logger LOG = Logger.getLogger(HttpServerReadinessCheck.class);

    @Inject
    PipestreamHealthConfig config;

    @Inject
    Vertx vertx;

    @Override
    public HealthCheckResponse call() {
        HealthCheckResponseBuilder builder = HealthCheckResponse.named("HTTP Server");

        if (!config.enabled() || !config.http().enabled()) {
            return builder.up().withData("status", "disabled").build();
        }

        int port = resolveHttpPort();
        long timeoutMs = config.http().timeout().toMillis();

        WebClientOptions options = new WebClientOptions()
                .setDefaultPort(port)
                .setDefaultHost("localhost")
                .setSsl(config.http().tlsEnabled())
                .setTrustAll(config.http().tlsSkipVerify())
                .setConnectTimeout((int) timeoutMs);

        WebClient client = WebClient.create(vertx, options);
        try {
            CompletableFuture<HttpResponse<Buffer>> future = new CompletableFuture<>();
            String healthPath = resolveHealthLivePath();
            client.get(healthPath)
                    .send()
                    .onSuccess(future::complete)
                    .onFailure(future::completeExceptionally);

            HttpResponse<Buffer> response = future.get(timeoutMs, TimeUnit.MILLISECONDS);
            int statusCode = response.statusCode();
            builder.withData("http.status", statusCode);
            builder.withData("http.port", port);

            if (statusCode == 200) {
                return builder.up().build();
            } else {
                builder.withData("http.body", truncate(response.bodyAsString(), 200));
                return builder.down().build();
            }
        } catch (Exception e) {
            LOG.debug("HTTP health check failed", e);
            builder.withData("error", e.getMessage());
            return builder.down().build();
        } finally {
            client.close();
        }
    }

    private int resolveHttpPort() {
        if (config.http().tlsEnabled()) {
            return ConfigProvider.getConfig()
                    .getOptionalValue("quarkus.http.ssl-port", Integer.class)
                    .orElse(8443);
        }
        return ConfigProvider.getConfig()
                .getOptionalValue("quarkus.http.port", Integer.class)
                .orElse(8080);
    }

    private String resolveHealthLivePath() {
        String httpRootPath = ConfigProvider.getConfig()
                .getOptionalValue("quarkus.http.root-path", String.class)
                .orElse("/");
        String nonAppRootPath = ConfigProvider.getConfig()
                .getOptionalValue("quarkus.http.non-application-root-path", String.class)
                .orElse("q");

        // Normalize httpRootPath
        if (!httpRootPath.startsWith("/")) {
            httpRootPath = "/" + httpRootPath;
        }
        if (httpRootPath.endsWith("/")) {
            httpRootPath = httpRootPath.substring(0, httpRootPath.length() - 1);
        }

        // When non-application-root-path is relative (e.g. "q"), it's relative to root-path
        // When absolute (e.g. "/q"), it's absolute
        if (nonAppRootPath.startsWith("/")) {
            return nonAppRootPath + "/health/live";
        }
        return httpRootPath + "/" + nonAppRootPath + "/health/live";
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) {
            return "";
        }
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
