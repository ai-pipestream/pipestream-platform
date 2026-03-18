package ai.pipestream.quarkus.djl.serving.runtime;

import ai.pipestream.quarkus.djl.serving.runtime.client.DjlServingClient;
import ai.pipestream.quarkus.djl.serving.runtime.config.DjlServingRuntimeConfig;
import io.quarkus.scheduler.Scheduled;
import io.vertx.core.json.JsonObject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Live registry of models registered in DJL Serving.
 * <p>
 * Polls the DJL Serving management API on a schedule and tracks which models
 * are READY. Consumers (e.g. the embedder, the frontend) should use this
 * registry to determine which models are actually available rather than
 * relying on the static enum.
 */
@ApplicationScoped
public class DjlModelRegistry {

    private static final Logger log = LoggerFactory.getLogger(DjlModelRegistry.class);

    private final Instance<DjlServingClient> clientInstance;
    private final DjlServingRuntimeConfig config;

    /** model name -> status (e.g. "READY", "LOADING") */
    private final Map<String, ModelStatus> models = new ConcurrentHashMap<>();

    private volatile Instant lastRefresh = Instant.EPOCH;
    private volatile boolean djlServingReachable = false;

    public record ModelStatus(String name, String status, String modelUrl, Instant lastSeen) {
        public boolean isReady() {
            return "READY".equalsIgnoreCase(status) || "Healthy".equalsIgnoreCase(status);
        }
    }

    @Inject
    public DjlModelRegistry(@RestClient Instance<DjlServingClient> clientInstance,
                            DjlServingRuntimeConfig config) {
        this.clientInstance = clientInstance;
        this.config = config;
    }

    /**
     * Scheduled health check — polls DJL Serving every 30 seconds.
     */
    @Scheduled(every = "{pipestream.djl-serving.refresh-interval:30s}", delayed = "5s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void scheduledRefresh() {
        refresh();
    }

    /**
     * Refreshes the model registry by querying DJL Serving's /models endpoint.
     */
    public void refresh() {
        try {
            if (clientInstance.isUnsatisfied()) {
                log.debug("DJL Serving client not yet available, skipping refresh");
                return;
            }
            DjlServingClient client = clientInstance.get();
            JsonObject response = client.listModels()
                    .await().atMost(config.requestTimeout());

            Map<String, ModelStatus> parsedModels = DjlServingModelsParser.parseModels(response, Instant.now());
            if (parsedModels.isEmpty()) {
                log.warn("DJL Serving /models response had no models");
                djlServingReachable = true;
                return;
            }

            Set<String> seen = ConcurrentHashMap.newKeySet();
            for (ModelStatus model : parsedModels.values()) {
                String name = model.name();
                String status = model.status();
                seen.add(name);

                ModelStatus prev = models.get(name);
                models.put(name, model);

                // Log state changes
                if (prev == null) {
                    log.info("Model '{}' discovered: status={}", name, status);
                } else if (!prev.status().equals(status)) {
                    log.info("Model '{}' status changed: {} -> {}", name, prev.status(), status);
                }
            }

            // Remove models that are no longer registered
            models.keySet().removeIf(name -> {
                if (!seen.contains(name)) {
                    log.info("Model '{}' no longer registered in DJL Serving", name);
                    return true;
                }
                return false;
            });

            djlServingReachable = true;
            lastRefresh = Instant.now();
            log.debug("Model registry refreshed: {} models ({} ready)",
                    models.size(), getReadyModelNames().size());

        } catch (Exception e) {
            djlServingReachable = false;
            log.warn("Failed to refresh model registry: {}", e.getMessage());
        }
    }

    /**
     * Returns the set of model names that are currently READY.
     */
    public Set<String> getReadyModelNames() {
        Set<String> ready = ConcurrentHashMap.newKeySet();
        for (ModelStatus ms : models.values()) {
            if (ms.isReady()) {
                ready.add(ms.name());
            }
        }
        return Collections.unmodifiableSet(ready);
    }

    /**
     * Returns all model statuses (ready or not).
     */
    public Map<String, ModelStatus> getAllModels() {
        return Collections.unmodifiableMap(models);
    }

    /**
     * Checks whether a specific model is registered and READY.
     */
    public boolean isModelReady(String modelName) {
        ModelStatus ms = models.get(modelName);
        return ms != null && ms.isReady();
    }

    /**
     * Whether DJL Serving is reachable (last poll succeeded).
     */
    public boolean isDjlServingReachable() {
        return djlServingReachable;
    }

    /**
     * When the registry was last successfully refreshed.
     */
    public Instant getLastRefresh() {
        return lastRefresh;
    }
}
