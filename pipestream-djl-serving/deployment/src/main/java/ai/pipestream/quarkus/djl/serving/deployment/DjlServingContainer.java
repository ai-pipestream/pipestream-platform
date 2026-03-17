package ai.pipestream.quarkus.djl.serving.deployment;

import org.jboss.logging.Logger;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

public class DjlServingContainer extends GenericContainer<DjlServingContainer> {

    private static final Logger LOG = Logger.getLogger(DjlServingContainer.class);

    public static final int DJL_SERVING_PORT = 8080;
    public static final String DJL_VERSION = "0.36.0";
    public static final String DEFAULT_IMAGE_BASE = "deepjavalibrary/djl-serving";
    public static final String DEFAULT_IMAGE = DEFAULT_IMAGE_BASE + ":" + DJL_VERSION + "-cpu";
    public static final String DEFAULT_MODEL_NAME = "all_MiniLM_L6_v2";
    public static final String DEFAULT_MODEL_URI =
            "djl://ai.djl.huggingface.pytorch/sentence-transformers/all-MiniLM-L6-v2";

    public DjlServingContainer(String imageName, String variant) {
        super(DockerImageName.parse(imageName == null ? DEFAULT_IMAGE : imageName));
        withExposedPorts(DJL_SERVING_PORT);
        applyVariantConfig(variant);
        waitingFor(Wait.forHttp("/ping").forPort(DJL_SERVING_PORT).withStartupTimeout(Duration.ofMinutes(5)));
    }

    private void applyVariantConfig(String variant) {
        if ("cuda".equals(variant)) {
            // GPU settings — matches docker-compose.gpu.yml
            withEnv("MODEL_LOADING_TIMEOUT", "600");
            withEnv("JAVA_OPTS", "-Xmx8g -Xms2g -XX:+ExitOnOutOfMemoryError");
            withEnv("OPTION_ROLLING_BATCH", "disable");
            withEnv("OPTION_TENSOR_PARALLEL_DEGREE", "1");
            withEnv("OMP_NUM_THREADS", "4");
        } else {
            // CPU / aarch64 settings — matches docker-compose.yml
            withEnv("MODEL_LOADING_TIMEOUT", "300");
            withEnv("JAVA_OPTS", "-Xmx4g -Xms1g -XX:+ExitOnOutOfMemoryError");
        }
    }

    public static String resolveImageName(String variant) {
        return switch (variant) {
            case "cpu" -> DEFAULT_IMAGE_BASE + ":" + DJL_VERSION + "-cpu";
            case "cuda" -> DEFAULT_IMAGE_BASE + ":" + DJL_VERSION + "-pytorch-gpu";
            case "aarch64" -> DEFAULT_IMAGE_BASE + ":" + DJL_VERSION + "-aarch64";
            default -> DEFAULT_IMAGE_BASE + ":" + DJL_VERSION + "-cpu";
        };
    }

    /**
     * Registers a model with DJL Serving via the management API using a djl:// URI.
     * Kept for backward compatibility (single-model fallback).
     */
    public void registerModel(String modelName, String modelUri) {
        String managementUrl = getUrl();
        LOG.infof("Registering model '%s' from %s at %s", modelName, modelUri, managementUrl);

        String encodedUri;
        try {
            encodedUri = URLEncoder.encode(modelUri, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("Failed to encode model URI", e);
        }

        String endpoint = managementUrl + "/models?url=" + encodedUri
                + "&model_name=" + modelName
                + "&engine=PyTorch"
                + "&synchronous=true"
                + "&translatorFactory=ai.djl.huggingface.translator.TextEmbeddingTranslatorFactory";

        doRegister(modelName, endpoint);
    }

    /**
     * Registers a model from a pre-packaged tar.gz archive URL.
     * <p>
     * For standard models, uses the PyTorch engine and TextEmbeddingTranslatorFactory.
     * For Python handler models (like BGE-M3), omits engine/translatorFactory since
     * the serving.properties inside the archive handles configuration.
     */
    public void registerModelFromUrl(String modelName, String archiveUrl, boolean pythonHandler) {
        String managementUrl = getUrl();
        LOG.infof("Registering model '%s' from archive %s at %s (pythonHandler=%s)",
                modelName, archiveUrl, managementUrl, pythonHandler);

        String encodedUrl;
        try {
            encodedUrl = URLEncoder.encode(archiveUrl, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("Failed to encode archive URL", e);
        }

        String endpoint = managementUrl + "/models?url=" + encodedUrl
                + "&model_name=" + modelName
                + "&synchronous=true";

        if (!pythonHandler) {
            endpoint += "&engine=PyTorch"
                    + "&translatorFactory=ai.djl.huggingface.translator.TextEmbeddingTranslatorFactory";
        }

        doRegister(modelName, endpoint);
    }

    /**
     * Registers all models from the given definitions, loading archives from the base URL.
     */
    public void registerAllModels(String baseUrl, List<DjlModelDefinitions.ModelDefinition> models) {
        LOG.infof("Registering %d models from base URL: %s", models.size(), baseUrl);
        for (var model : models) {
            String archiveUrl = baseUrl + "/" + model.archiveFileName() + ".tar.gz";
            registerModelFromUrl(model.djlServingName(), archiveUrl, model.pythonHandler());
        }
    }

    private void doRegister(String modelName, String endpoint) {
        int maxRetries = 5;
        for (int i = 0; i < maxRetries; i++) {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(5_000);
                conn.setReadTimeout(120_000);

                int status = conn.getResponseCode();
                if (status >= 200 && status < 300) {
                    LOG.infof("Model '%s' registered successfully (HTTP %d)", modelName, status);
                    return;
                }

                String error = "N/A";
                try (InputStream es = conn.getErrorStream()) {
                    if (es != null) error = new String(es.readAllBytes());
                }
                LOG.warnf("Registration attempt %d/%d: HTTP %d — %s", i + 1, maxRetries, status, error);
            } catch (Exception e) {
                LOG.warnf("Registration attempt %d/%d failed: %s", i + 1, maxRetries, e.getMessage());
            }
            try { Thread.sleep(2_000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }
        throw new RuntimeException("Failed to register model '" + modelName + "' after " + maxRetries + " attempts");
    }

    public String getUrl() {
        return "http://" + getHost() + ":" + getMappedPort(DJL_SERVING_PORT);
    }
}
