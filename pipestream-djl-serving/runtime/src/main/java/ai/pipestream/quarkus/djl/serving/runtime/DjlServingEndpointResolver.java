package ai.pipestream.quarkus.djl.serving.runtime;

import ai.pipestream.quarkus.djl.serving.runtime.config.DjlServingRuntimeConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@ApplicationScoped
public class DjlServingEndpointResolver {
    private static final Logger log = LoggerFactory.getLogger(DjlServingEndpointResolver.class);

    @Inject
    DjlServingRuntimeConfig config;

    @Inject
    ObjectMapper objectMapper;

    public Uni<List<String>> resolveEndpoints() {
        return Uni.createFrom().item(this::resolveEndpointsBlocking)
                .runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    private List<String> resolveEndpointsBlocking() {
        if (config.discoveryMode() == DjlServingRuntimeConfig.DiscoveryMode.CONSUL_HTTP) {
            return resolveFromConsul();
        }
        return resolveDirectUrls();
    }

    private List<String> resolveDirectUrls() {
        Set<String> deduped = new LinkedHashSet<>();
        addIfPresent(deduped, config.url());
        config.directUrls().ifPresent(urls -> urls.forEach(url -> addIfPresent(deduped, url)));
        return List.copyOf(deduped);
    }

    private List<String> resolveFromConsul() {
        var consul = config.consul();
        String serviceName = encode(consul.serviceName());
        StringBuilder query = new StringBuilder();
        query.append("passing=").append(consul.passingOnly());
        consul.tag().ifPresent(tag -> query.append("&tag=").append(encode(tag)));
        consul.datacenter().ifPresent(dc -> query.append("&dc=").append(encode(dc)));

        String url = String.format("%s://%s:%d/v1/health/service/%s?%s",
                consul.scheme(),
                consul.host(),
                consul.port(),
                serviceName,
                query);

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(config.requestTimeout())
                .build();

        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(config.requestTimeout())
                .GET()
                .build();

        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("Consul lookup for DJL endpoints failed with status {}", response.statusCode());
                return List.of();
            }

            JsonNode payload = objectMapper.readTree(response.body());
            if (!payload.isArray()) {
                log.warn("Unexpected Consul response shape for DJL endpoints");
                return List.of();
            }

            Set<String> endpoints = new LinkedHashSet<>();
            for (JsonNode node : payload) {
                String scheme = "http";

                JsonNode service = node.path("Service");
                JsonNode checks = node.path("Checks");
                for (JsonNode check : checks) {
                    String checkId = check.path("CheckID").asText("");
                    if (checkId.contains("https")) {
                        scheme = "https";
                        break;
                    }
                }

                String host = textOrEmpty(service.path("Address"));
                if (host.isBlank()) {
                    host = textOrEmpty(node.path("Node").path("Address"));
                }
                int port = service.path("Port").asInt(0);
                if (host.isBlank() || port <= 0) {
                    continue;
                }
                endpoints.add(String.format("%s://%s:%d", scheme, host, port));
            }

            return List.copyOf(endpoints);
        } catch (Exception e) {
            log.warn("Failed to resolve DJL endpoints from Consul: {}", e.getMessage());
            return List.of();
        }
    }

    private static void addIfPresent(Set<String> target, String url) {
        if (url == null || url.isBlank()) {
            return;
        }
        target.add(url.strip());
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String textOrEmpty(JsonNode node) {
        return node == null ? "" : node.asText("");
    }
}
