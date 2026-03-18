package ai.pipestream.quarkus.djl.serving.runtime;

import ai.pipestream.djl.serving.v1.ModelAggregateHealthStatus;
import ai.pipestream.djl.serving.v1.ModelHealth;
import ai.pipestream.quarkus.djl.serving.runtime.config.DjlServingRuntimeConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DjlModelHealthAggregatorTest {

    @Test
    void aggregatesHealthAcrossDirectUrls() throws Exception {
        HttpServer serverA = jsonServer("/models", """
                {"models":[
                  {"modelName":"minilm","status":"READY","modelUrl":"djl://minilm"},
                  {"modelName":"mpnet","status":"LOADING","modelUrl":"djl://mpnet"}
                ]}
                """);
        HttpServer serverB = jsonServer("/models", """
                {"models":[
                  {"modelName":"minilm","status":"READY","modelUrl":"djl://minilm"}
                ]}
                """);

        try {
            String urlA = "http://localhost:" + serverA.getAddress().getPort();
            String urlB = "http://localhost:" + serverB.getAddress().getPort();

            DjlServingRuntimeConfig config = new TestConfig(
                    DjlServingRuntimeConfig.DiscoveryMode.DIRECT_URL,
                    urlA,
                    Optional.of(List.of(urlB)),
                    consulDefaults());

            DjlServingEndpointResolver resolver = new DjlServingEndpointResolver();
            resolver.config = config;
            resolver.objectMapper = new ObjectMapper();

            DjlModelHealthAggregator aggregator = new DjlModelHealthAggregator();
            aggregator.endpointResolver = resolver;
            aggregator.config = config;

            DjlModelHealthAggregator.AggregationResult result = aggregator.aggregateAllModels().await().indefinitely();
            Map<String, ModelHealth> byModel = result.modelHealth().stream()
                    .collect(java.util.stream.Collectors.toMap(ModelHealth::getModelName, mh -> mh));

            assertEquals(2, result.instancesChecked());
            assertEquals(2, result.instancesReachable());
            assertTrue(result.discoverySuccessful());
            assertTrue(byModel.containsKey("minilm"));
            assertEquals(ModelAggregateHealthStatus.MODEL_AGGREGATE_HEALTH_STATUS_HEALTHY, byModel.get("minilm").getStatus());
            assertEquals(ModelAggregateHealthStatus.MODEL_AGGREGATE_HEALTH_STATUS_UNAVAILABLE, byModel.get("mpnet").getStatus());
        } finally {
            serverA.stop(0);
            serverB.stop(0);
        }
    }

    @Test
    void resolvesViaConsulHttpAndReportsPartialReachability() throws Exception {
        HttpServer modelServer = jsonServer("/models", """
                {"models":[
                  {"modelName":"minilm","status":"READY","modelUrl":"djl://minilm"}
                ]}
                """);

        HttpServer consulServer = HttpServer.create(new InetSocketAddress(0), 0);
        consulServer.createContext("/v1/health/service/djl-serving", exchange -> {
            String body = """
                    [
                      {"Service":{"Address":"localhost","Port":%d},"Node":{"Address":"localhost"},"Checks":[]},
                      {"Service":{"Address":"localhost","Port":1},"Node":{"Address":"localhost"},"Checks":[]}
                    ]
                    """.formatted(modelServer.getAddress().getPort());
            writeJson(exchange, body);
        });
        consulServer.start();

        try {
            DjlServingRuntimeConfig.ConsulConfig consulConfig = new TestConsulConfig(
                    "http",
                    "localhost",
                    consulServer.getAddress().getPort(),
                    "djl-serving",
                    Optional.empty(),
                    Optional.empty(),
                    true
            );
            DjlServingRuntimeConfig config = new TestConfig(
                    DjlServingRuntimeConfig.DiscoveryMode.CONSUL_HTTP,
                    "http://unused:8080",
                    Optional.empty(),
                    consulConfig);

            DjlServingEndpointResolver resolver = new DjlServingEndpointResolver();
            resolver.config = config;
            resolver.objectMapper = new ObjectMapper();

            DjlModelHealthAggregator aggregator = new DjlModelHealthAggregator();
            aggregator.endpointResolver = resolver;
            aggregator.config = config;

            DjlModelHealthAggregator.AggregationResult result = aggregator.aggregateAllModels().await().indefinitely();
            Map<String, ModelHealth> byModel = result.modelHealth().stream()
                    .collect(java.util.stream.Collectors.toMap(ModelHealth::getModelName, mh -> mh));

            assertEquals(2, result.instancesChecked());
            assertEquals(1, result.instancesReachable());
            assertFalse(result.unresolvedErrors().isEmpty());
            assertEquals(ModelAggregateHealthStatus.MODEL_AGGREGATE_HEALTH_STATUS_HEALTHY, byModel.get("minilm").getStatus());
        } finally {
            consulServer.stop(0);
            modelServer.stop(0);
        }
    }

    private static HttpServer jsonServer(String path, String body) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext(path, exchange -> writeJson(exchange, body));
        server.start();
        return server;
    }

    private static void writeJson(com.sun.net.httpserver.HttpExchange exchange, String body) throws IOException {
        byte[] payload = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, payload.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(payload);
        }
    }

    private static DjlServingRuntimeConfig.ConsulConfig consulDefaults() {
        return new TestConsulConfig("http", "localhost", 8500, "djl-serving", Optional.empty(), Optional.empty(), true);
    }

    private record TestConfig(
            DjlServingRuntimeConfig.DiscoveryMode discoveryMode,
            String url,
            Optional<List<String>> directUrls,
            DjlServingRuntimeConfig.ConsulConfig consul
    ) implements DjlServingRuntimeConfig {
        @Override
        public boolean enabled() {
            return true;
        }

        @Override
        public Duration requestTimeout() {
            return Duration.ofSeconds(2);
        }

        @Override
        public Duration refreshInterval() {
            return Duration.ofSeconds(30);
        }
    }

    private record TestConsulConfig(
            String scheme,
            String host,
            int port,
            String serviceName,
            Optional<String> tag,
            Optional<String> datacenter,
            boolean passingOnly
    ) implements DjlServingRuntimeConfig.ConsulConfig {
    }
}
