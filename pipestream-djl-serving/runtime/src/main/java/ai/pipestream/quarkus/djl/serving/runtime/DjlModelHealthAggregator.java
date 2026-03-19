package ai.pipestream.quarkus.djl.serving.runtime;

import ai.pipestream.djl.serving.v1.ModelAggregateHealthStatus;
import ai.pipestream.djl.serving.v1.ModelHealth;
import ai.pipestream.quarkus.djl.serving.runtime.client.DjlRestClientFactory;
import com.google.protobuf.Timestamp;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@ApplicationScoped
public class DjlModelHealthAggregator {
    private static final Logger log = LoggerFactory.getLogger(DjlModelHealthAggregator.class);

    @Inject
    DjlServingEndpointResolver endpointResolver;

    @Inject
    DjlRestClientFactory restClientFactory;

    public Uni<AggregationResult> aggregateModelHealth(Collection<String> filterModels, boolean includeUnexpectedModels) {
        return endpointResolver.resolveEndpoints()
                .flatMap(endpoints -> {
                    if (endpoints.isEmpty()) {
                        return Uni.createFrom().item(buildResult(List.of(), List.of(), filterModels, includeUnexpectedModels));
                    }
                    List<Uni<InstanceResult>> checks = endpoints.stream()
                            .map(this::fetchModelsFromEndpoint)
                            .toList();
                    return Uni.combine().all().unis(checks)
                            .with(items -> items.stream()
                                    .map(InstanceResult.class::cast)
                                    .toList())
                            .map(results -> buildResult(endpoints, results, filterModels, includeUnexpectedModels));
                });
    }

    public Uni<AggregationResult> aggregateAllModels() {
        return aggregateModelHealth(List.of(), true);
    }

    private Uni<InstanceResult> fetchModelsFromEndpoint(String endpoint) {
        return restClientFactory.djlClient(endpoint)
                .listModels()
                .map(body -> {
                    Map<String, DjlModelRegistry.ModelStatus> parsed = DjlServingModelsParser.parseModels(body, Instant.now());
                    return InstanceResult.success(endpoint, parsed);
                })
                .onFailure().recoverWithItem(error -> {
                    log.debug("Failed to fetch model list from {}: {}", endpoint, error.getMessage());
                    String message = error.getMessage();
                    if (message == null || message.isBlank()) {
                        message = error.getClass().getSimpleName();
                    }
                    return InstanceResult.failure(endpoint, message);
                });
    }

    private AggregationResult buildResult(List<String> resolvedEndpoints,
                                          List<InstanceResult> instanceResults,
                                          Collection<String> filterModels,
                                          boolean includeUnexpectedModels) {
        Instant now = Instant.now();
        int instancesChecked = resolvedEndpoints.size();
        int instancesReachable = (int) instanceResults.stream().filter(InstanceResult::success).count();

        Set<String> expected = new HashSet<>(DjlExpectedModels.EXPECTED_MODELS);
        Set<String> requestedFilter = filterModels == null ? Set.of() : filterModels.stream()
                .map(DjlServingModelsParser::normalizeModelName)
                .collect(java.util.stream.Collectors.toSet());

        Map<String, String> observedCanonicalNames = new HashMap<>();
        Map<String, ModelCounters> countersByNormalizedModel = new HashMap<>();

        for (InstanceResult result : instanceResults) {
            if (!result.success()) {
                continue;
            }

            for (DjlModelRegistry.ModelStatus status : result.models().values()) {
                String normalized = DjlServingModelsParser.normalizeModelName(status.name());
                observedCanonicalNames.putIfAbsent(normalized, status.name());
                ModelCounters counters = countersByNormalizedModel.computeIfAbsent(normalized, key -> new ModelCounters());
                counters.instancesReporting++;
                if (status.isReady()) {
                    counters.healthyInstances++;
                }
            }
        }

        Set<String> allModelKeys = new HashSet<>();
        expected.forEach(name -> allModelKeys.add(DjlServingModelsParser.normalizeModelName(name)));
        if (includeUnexpectedModels) {
            allModelKeys.addAll(countersByNormalizedModel.keySet());
        }

        List<ModelHealth> modelHealth = new ArrayList<>();
        List<String> observedModels = new ArrayList<>();

        for (String modelKey : allModelKeys) {
            String displayName = observedCanonicalNames.getOrDefault(modelKey, findExpectedName(modelKey));
            if (!requestedFilter.isEmpty() && !requestedFilter.contains(modelKey)) {
                continue;
            }

            boolean isExpected = expected.stream()
                    .anyMatch(expectedName -> DjlServingModelsParser.normalizeModelName(expectedName).equals(modelKey));

            ModelCounters counters = countersByNormalizedModel.getOrDefault(modelKey, new ModelCounters());
            ModelAggregateHealthStatus aggregateStatus = aggregateStatus(
                    instancesChecked, instancesReachable, counters.instancesReporting, counters.healthyInstances);

            if (counters.instancesReporting > 0) {
                observedModels.add(displayName);
            }

            String message = String.format("model=%s reachable=%d reporting=%d healthy=%d checked=%d",
                    displayName, instancesReachable, counters.instancesReporting, counters.healthyInstances, instancesChecked);

            modelHealth.add(ModelHealth.newBuilder()
                    .setModelName(displayName)
                    .setExpected(isExpected)
                    .setStatus(aggregateStatus)
                    .setInstancesChecked(instancesChecked)
                    .setInstancesReachable(instancesReachable)
                    .setInstancesReporting(counters.instancesReporting)
                    .setHealthyInstances(counters.healthyInstances)
                    .setObservedAt(toTimestamp(now))
                    .setMessage(message)
                    .build());
        }

        modelHealth.sort(Comparator.comparing(ModelHealth::getModelName));
        observedModels.sort(String::compareTo);

        List<String> errors = instanceResults.stream()
                .filter(result -> !result.success() && result.error() != null && !result.error().isBlank())
                .map(result -> result.endpoint() + ": " + result.error())
                .toList();

        return new AggregationResult(
                DjlExpectedModels.EXPECTED_MODELS,
                observedModels,
                modelHealth,
                instancesChecked,
                instancesReachable,
                instancesChecked > 0,
                now,
                errors
        );
    }

    private static String findExpectedName(String modelKey) {
        for (String expectedName : DjlExpectedModels.EXPECTED_MODELS) {
            if (DjlServingModelsParser.normalizeModelName(expectedName).equals(modelKey)) {
                return expectedName;
            }
        }
        return modelKey;
    }

    private static ModelAggregateHealthStatus aggregateStatus(int instancesChecked,
                                                              int instancesReachable,
                                                              int instancesReporting,
                                                              int healthyInstances) {
        if (instancesChecked == 0 || instancesReachable == 0) {
            return ModelAggregateHealthStatus.MODEL_AGGREGATE_HEALTH_STATUS_UNKNOWN;
        }
        if (instancesReporting == 0) {
            return ModelAggregateHealthStatus.MODEL_AGGREGATE_HEALTH_STATUS_UNAVAILABLE;
        }
        if (healthyInstances == instancesReporting && instancesReporting == instancesReachable) {
            return ModelAggregateHealthStatus.MODEL_AGGREGATE_HEALTH_STATUS_HEALTHY;
        }
        if (healthyInstances > 0) {
            return ModelAggregateHealthStatus.MODEL_AGGREGATE_HEALTH_STATUS_DEGRADED;
        }
        return ModelAggregateHealthStatus.MODEL_AGGREGATE_HEALTH_STATUS_UNAVAILABLE;
    }

    private static Timestamp toTimestamp(Instant instant) {
        return Timestamp.newBuilder()
                .setSeconds(instant.getEpochSecond())
                .setNanos(instant.getNano())
                .build();
    }

    private static final class ModelCounters {
        int instancesReporting;
        int healthyInstances;
    }

    private record InstanceResult(
            String endpoint,
            boolean success,
            Map<String, DjlModelRegistry.ModelStatus> models,
            String error) {
        static InstanceResult success(String endpoint, Map<String, DjlModelRegistry.ModelStatus> models) {
            return new InstanceResult(endpoint, true, models, null);
        }

        static InstanceResult failure(String endpoint, String error) {
            return new InstanceResult(endpoint, false, Map.of(), error);
        }
    }

    public record AggregationResult(
            List<String> expectedModels,
            List<String> observedModels,
            List<ModelHealth> modelHealth,
            int instancesChecked,
            int instancesReachable,
            boolean discoverySuccessful,
            Instant observedAt,
            List<String> unresolvedErrors) {
    }
}
