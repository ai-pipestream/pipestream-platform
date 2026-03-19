package ai.pipestream.quarkus.djl.serving.runtime;

import ai.pipestream.quarkus.djl.serving.runtime.config.DjlServingRuntimeConfig;
import ai.pipestream.quarkus.djl.serving.runtime.client.ConsulDiscoveryClient;
import ai.pipestream.quarkus.djl.serving.runtime.client.DjlRestClientFactory;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@ApplicationScoped
public class DjlServingEndpointResolver {
    private static final Logger log = LoggerFactory.getLogger(DjlServingEndpointResolver.class);

    @Inject
    DjlServingRuntimeConfig config;

    @Inject
    DjlRestClientFactory restClientFactory;

    public Uni<List<String>> resolveEndpoints() {
        if (config.discoveryMode() == DjlServingRuntimeConfig.DiscoveryMode.CONSUL_HTTP) {
            return resolveFromConsul();
        }
        return Uni.createFrom().item(resolveDirectUrls());
    }

    private List<String> resolveDirectUrls() {
        Set<String> deduped = new LinkedHashSet<>();
        addIfPresent(deduped, config.url());
        config.directUrls().ifPresent(urls -> urls.forEach(url -> addIfPresent(deduped, url)));
        return List.copyOf(deduped);
    }

    private Uni<List<String>> resolveFromConsul() {
        var consul = config.consul();
        String consulBaseUrl = String.format("%s://%s:%d", consul.scheme(), consul.host(), consul.port());
        ConsulDiscoveryClient client = restClientFactory.consulClient(consulBaseUrl);

        return client.listServiceInstances(consul.serviceName(), consul.passingOnly(), consul.tag().orElse(null), consul.datacenter().orElse(null))
                .map(this::mapConsulPayloadToEndpoints)
                .onFailure().recoverWithItem(error -> {
                    log.warn("Failed to resolve DJL endpoints from Consul: {}", error.getMessage());
                    return List.of();
                });
    }

    private List<String> mapConsulPayloadToEndpoints(JsonArray payload) {
        Set<String> endpoints = new LinkedHashSet<>();
        if (payload == null) {
            return List.of();
        }

        for (int i = 0; i < payload.size(); i++) {
            JsonObject node = payload.getJsonObject(i);
            if (node == null) {
                continue;
            }
            JsonObject service = node.getJsonObject("Service", new JsonObject());
            JsonArray checks = node.getJsonArray("Checks", new JsonArray());

            String scheme = "http";
            for (int c = 0; c < checks.size(); c++) {
                JsonObject check = checks.getJsonObject(c);
                if (check != null && check.getString("CheckID", "").contains("https")) {
                    scheme = "https";
                    break;
                }
            }

            String host = service.getString("Address", "");
            if (host.isBlank()) {
                JsonObject nodeInfo = node.getJsonObject("Node", new JsonObject());
                host = nodeInfo.getString("Address", "");
            }
            int port = service.getInteger("Port", 0);
            if (host.isBlank() || port <= 0) {
                continue;
            }
            endpoints.add(String.format("%s://%s:%d", scheme, host, port));
        }

        return List.copyOf(endpoints);
    }

    private static void addIfPresent(Set<String> target, String url) {
        if (url == null || url.isBlank()) {
            return;
        }
        target.add(url.strip());
    }
}
