package ai.pipestream.quarkus.djl.serving.runtime;

import ai.pipestream.djl.serving.v1.ModelAggregateHealthStatus;
import ai.pipestream.djl.serving.v1.ModelHealth;
import ai.pipestream.quarkus.djl.serving.runtime.client.ConsulDiscoveryClient;
import ai.pipestream.quarkus.djl.serving.runtime.client.DjlRestClientFactory;
import ai.pipestream.quarkus.djl.serving.runtime.client.DjlServingModelsEndpointClient;
import ai.pipestream.quarkus.djl.serving.runtime.config.DjlServingRuntimeConfig;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DjlModelHealthAggregatorTest {

    @Test
    void aggregatesHealthAcrossDirectUrls() {
        String urlA = "http://instance-a:8080";
        String urlB = "http://instance-b:8080";
        DjlServingRuntimeConfig config = new TestConfig(
                DjlServingRuntimeConfig.DiscoveryMode.DIRECT_URL,
                urlA,
                Optional.of(List.of(urlB)),
                consulDefaults());
        
        StubClientFactory factory = new StubClientFactory(
                config,
                endpoint -> switch (endpoint) {
                    case "http://instance-a:8080" -> Uni.createFrom().item(new JsonObject("""
                {"models":[
                  {"modelName":"minilm","status":"READY","modelUrl":"djl://minilm"},
                  {"modelName":"mpnet","status":"LOADING","modelUrl":"djl://mpnet"}
                ]}
                """));
                    case "http://instance-b:8080" -> Uni.createFrom().item(new JsonObject("""
                {"models":[
                  {"modelName":"minilm","status":"READY","modelUrl":"djl://minilm"}
                ]}
                """));
                    default -> Uni.createFrom().failure(new IllegalStateException("Unknown endpoint " + endpoint));
                },
                (serviceName, passingOnly, tag, datacenter) -> Uni.createFrom().item(new JsonArray())
        );

        DjlServingEndpointResolver resolver = new DjlServingEndpointResolver();
        resolver.config = config;
        resolver.restClientFactory = factory;

        DjlModelHealthAggregator aggregator = new DjlModelHealthAggregator();
        aggregator.endpointResolver = resolver;
        aggregator.restClientFactory = factory;

        DjlModelHealthAggregator.AggregationResult result = aggregator.aggregateAllModels().await().indefinitely();
        Map<String, ModelHealth> byModel = result.modelHealth().stream()
                .collect(java.util.stream.Collectors.toMap(ModelHealth::getModelName, mh -> mh));

        assertEquals(2, result.instancesChecked());
        assertEquals(2, result.instancesReachable());
        assertTrue(result.discoverySuccessful());
        assertTrue(byModel.containsKey("minilm"));
        assertEquals(ModelAggregateHealthStatus.MODEL_AGGREGATE_HEALTH_STATUS_HEALTHY, byModel.get("minilm").getStatus());
        assertEquals(ModelAggregateHealthStatus.MODEL_AGGREGATE_HEALTH_STATUS_UNAVAILABLE, byModel.get("mpnet").getStatus());
    }

    @Test
    void resolvesViaConsulHttpAndReportsPartialReachability() {
        DjlServingRuntimeConfig.ConsulConfig consulConfig = new TestConsulConfig(
                "http",
                "localhost",
                8500,
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

        StubClientFactory factory = new StubClientFactory(
                config,
                endpoint -> {
                    if ("http://reachable:8080".equals(endpoint)) {
                        return Uni.createFrom().item(new JsonObject("""
                {"models":[
                  {"modelName":"minilm","status":"READY","modelUrl":"djl://minilm"}
                ]}
                        """));
                    }
                    return Uni.createFrom().failure(new IllegalStateException("connect failed"));
                },
                (serviceName, passingOnly, tag, datacenter) -> Uni.createFrom().item(new JsonArray("""
                        [
                          {"Service":{"Address":"reachable","Port":8080},"Node":{"Address":"reachable"},"Checks":[]},
                          {"Service":{"Address":"unreachable","Port":8080},"Node":{"Address":"unreachable"},"Checks":[]}
                        ]
                        """))
        );

        DjlServingEndpointResolver resolver = new DjlServingEndpointResolver();
        resolver.config = config;
        resolver.restClientFactory = factory;

        DjlModelHealthAggregator aggregator = new DjlModelHealthAggregator();
        aggregator.endpointResolver = resolver;
        aggregator.restClientFactory = factory;

        DjlModelHealthAggregator.AggregationResult result = aggregator.aggregateAllModels().await().indefinitely();
        Map<String, ModelHealth> byModel = result.modelHealth().stream()
                .collect(java.util.stream.Collectors.toMap(ModelHealth::getModelName, mh -> mh));

        assertEquals(2, result.instancesChecked());
        assertEquals(1, result.instancesReachable());
        assertFalse(result.unresolvedErrors().isEmpty());
        assertEquals(ModelAggregateHealthStatus.MODEL_AGGREGATE_HEALTH_STATUS_HEALTHY, byModel.get("minilm").getStatus());
    }

    @Test
    void reportsDegradedHealthWhenOnlySomeInstancesAreReady() {
        String urlA = "http://instance-a:8080";
        String urlB = "http://instance-b:8080";
        DjlServingRuntimeConfig config = new TestConfig(
                DjlServingRuntimeConfig.DiscoveryMode.DIRECT_URL,
                urlA,
                Optional.of(List.of(urlB)),
                consulDefaults());

        StubClientFactory factory = new StubClientFactory(
                config,
                endpoint -> switch (endpoint) {
                    case "http://instance-a:8080" -> Uni.createFrom().item(new JsonObject("""
                {"models":[
                  {"modelName":"minilm","status":"READY"}
                ]}
                """));
                    case "http://instance-b:8080" -> Uni.createFrom().item(new JsonObject("""
                {"models":[
                  {"modelName":"minilm","status":"LOADING"}
                ]}
                """));
                    default -> Uni.createFrom().failure(new IllegalStateException("Unknown endpoint " + endpoint));
                },
                (serviceName, passingOnly, tag, datacenter) -> Uni.createFrom().item(new JsonArray())
        );

        DjlServingEndpointResolver resolver = new DjlServingEndpointResolver();
        resolver.config = config;
        resolver.restClientFactory = factory;

        DjlModelHealthAggregator aggregator = new DjlModelHealthAggregator();
        aggregator.endpointResolver = resolver;
        aggregator.restClientFactory = factory;

        DjlModelHealthAggregator.AggregationResult result = aggregator.aggregateAllModels().await().indefinitely();
        Map<String, ModelHealth> byModel = result.modelHealth().stream()
                .collect(java.util.stream.Collectors.toMap(ModelHealth::getModelName, mh -> mh));

        assertEquals(ModelAggregateHealthStatus.MODEL_AGGREGATE_HEALTH_STATUS_DEGRADED, byModel.get("minilm").getStatus());
        assertEquals(2, byModel.get("minilm").getInstancesReporting());
        assertEquals(1, byModel.get("minilm").getHealthyInstances());
    }

    @Test
    void reportsUnavailableWhenNoInstancesAreReady() {
        String urlA = "http://instance-a:8080";
        DjlServingRuntimeConfig config = new TestConfig(
                DjlServingRuntimeConfig.DiscoveryMode.DIRECT_URL,
                urlA,
                Optional.empty(),
                consulDefaults());

        StubClientFactory factory = new StubClientFactory(
                config,
                endpoint -> Uni.createFrom().item(new JsonObject("""
                {"models":[
                  {"modelName":"minilm","status":"LOADING"}
                ]}
                """)),
                (serviceName, passingOnly, tag, datacenter) -> Uni.createFrom().item(new JsonArray())
        );

        DjlServingEndpointResolver resolver = new DjlServingEndpointResolver();
        resolver.config = config;
        resolver.restClientFactory = factory;

        DjlModelHealthAggregator aggregator = new DjlModelHealthAggregator();
        aggregator.endpointResolver = resolver;
        aggregator.restClientFactory = factory;

        DjlModelHealthAggregator.AggregationResult result = aggregator.aggregateAllModels().await().indefinitely();
        Map<String, ModelHealth> byModel = result.modelHealth().stream()
                .collect(java.util.stream.Collectors.toMap(ModelHealth::getModelName, mh -> mh));

        assertEquals(ModelAggregateHealthStatus.MODEL_AGGREGATE_HEALTH_STATUS_UNAVAILABLE, byModel.get("minilm").getStatus());
        assertEquals(1, byModel.get("minilm").getInstancesReporting());
        assertEquals(0, byModel.get("minilm").getHealthyInstances());
    }

    @Test
    void includesUnexpectedModelsWhenRequested() {
        String urlA = "http://instance-a:8080";
        DjlServingRuntimeConfig config = new TestConfig(
                DjlServingRuntimeConfig.DiscoveryMode.DIRECT_URL,
                urlA,
                Optional.empty(),
                consulDefaults());

        StubClientFactory factory = new StubClientFactory(
                config,
                endpoint -> Uni.createFrom().item(new JsonObject("""
                {"models":[
                  {"modelName":"custom-model","status":"READY"}
                ]}
                """)),
                (serviceName, passingOnly, tag, datacenter) -> Uni.createFrom().item(new JsonArray())
        );

        DjlServingEndpointResolver resolver = new DjlServingEndpointResolver();
        resolver.config = config;
        resolver.restClientFactory = factory;

        DjlModelHealthAggregator aggregator = new DjlModelHealthAggregator();
        aggregator.endpointResolver = resolver;
        aggregator.restClientFactory = factory;

        // aggregateAllModels uses includeUnexpectedModels = true
        DjlModelHealthAggregator.AggregationResult result = aggregator.aggregateAllModels().await().indefinitely();
        Map<String, ModelHealth> byModel = result.modelHealth().stream()
                .collect(java.util.stream.Collectors.toMap(ModelHealth::getModelName, mh -> mh));

        assertTrue(byModel.containsKey("custom-model"));
        assertFalse(byModel.get("custom-model").getExpected());
        assertEquals(ModelAggregateHealthStatus.MODEL_AGGREGATE_HEALTH_STATUS_HEALTHY, byModel.get("custom-model").getStatus());
    }

    @Test
    void filtersModelsWhenRequested() {
        String urlA = "http://instance-a:8080";
        DjlServingRuntimeConfig config = new TestConfig(
                DjlServingRuntimeConfig.DiscoveryMode.DIRECT_URL,
                urlA,
                Optional.empty(),
                consulDefaults());

        StubClientFactory factory = new StubClientFactory(
                config,
                endpoint -> Uni.createFrom().item(new JsonObject("""
                {"models":[
                  {"modelName":"minilm","status":"READY"},
                  {"modelName":"mpnet","status":"READY"}
                ]}
                """)),
                (serviceName, passingOnly, tag, datacenter) -> Uni.createFrom().item(new JsonArray())
        );

        DjlServingEndpointResolver resolver = new DjlServingEndpointResolver();
        resolver.config = config;
        resolver.restClientFactory = factory;

        DjlModelHealthAggregator aggregator = new DjlModelHealthAggregator();
        aggregator.endpointResolver = resolver;
        aggregator.restClientFactory = factory;

        DjlModelHealthAggregator.AggregationResult result = aggregator.aggregateModelHealth(List.of("minilm"), false).await().indefinitely();
        Map<String, ModelHealth> byModel = result.modelHealth().stream()
                .collect(java.util.stream.Collectors.toMap(ModelHealth::getModelName, mh -> mh));

        assertTrue(byModel.containsKey("minilm"));
        assertFalse(byModel.containsKey("mpnet"));
        assertEquals(1, result.modelHealth().size());
    }

    private static final class StubClientFactory extends DjlRestClientFactory {
        private final Function<String, Uni<JsonObject>> djlLookup;
        private final ConsulLookup consulLookup;

        private StubClientFactory(DjlServingRuntimeConfig config, Function<String, Uni<JsonObject>> djlLookup, ConsulLookup consulLookup) {
            super(config);
            this.djlLookup = djlLookup;
            this.consulLookup = consulLookup;
        }

        @Override
        public DjlServingModelsEndpointClient djlClient(String baseUrl) {
            return () -> djlLookup.apply(baseUrl);
        }

        @Override
        public ConsulDiscoveryClient consulClient(String baseUrl) {
            return (serviceName, passingOnly, tag, datacenter) -> consulLookup.lookup(serviceName, passingOnly, tag, datacenter);
        }
    }

    @FunctionalInterface
    private interface ConsulLookup {
        Uni<JsonArray> lookup(String serviceName, boolean passingOnly, String tag, String datacenter);
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
