package ai.pipestream.quarkus.djl.serving.runtime;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Shared parser for DJL Serving /models payloads.
 */
final class DjlServingModelsParser {

    private DjlServingModelsParser() {
    }

    static Map<String, DjlModelRegistry.ModelStatus> parseModels(JsonObject response, Instant observedAt) {
        Map<String, DjlModelRegistry.ModelStatus> parsed = new HashMap<>();
        if (response == null) {
            return parsed;
        }

        JsonArray modelArray = response.getJsonArray("models");
        if (modelArray == null) {
            return parsed;
        }

        for (int i = 0; i < modelArray.size(); i++) {
            JsonObject model = modelArray.getJsonObject(i);
            if (model == null) {
                continue;
            }

            String name = model.getString("modelName");
            if (name == null || name.isBlank()) {
                continue;
            }

            String status = model.getString("status", "UNKNOWN");
            String url = model.getString("modelUrl", "");
            parsed.put(name, new DjlModelRegistry.ModelStatus(name, status, url, observedAt));
        }

        return parsed;
    }

    static String normalizeModelName(String raw) {
        if (raw == null) {
            return "";
        }
        return raw
                .trim()
                .toLowerCase()
                .replace("-", "")
                .replace("_", "")
                .replace(".", "");
    }
}
