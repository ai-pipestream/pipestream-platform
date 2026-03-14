package ai.pipestream.test.support;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * Shared test resource for setting up DJL Serving container for embedding tests.
 * <p>
 * Provides a pre-warmed DJL Serving instance with the all-MiniLM-L6-v2 model
 * for testing embedding operations.
 * <p>
 * Usage:
 * <pre>
 * {@code
 * @QuarkusTest
 * @QuarkusTestResource(DjlServingTestResource.class)
 * public class MyEmbeddingTest {
 *     // Tests can use embedder.djl-serving.url property
 * }
 * }
 * </pre>
 */
public class DjlServingTestResource implements QuarkusTestResourceLifecycleManager {

    private static final Logger log = LoggerFactory.getLogger(DjlServingTestResource.class);

    private static final String DEFAULT_IMAGE = "deepjavalibrary/djl-serving:0.36.0-cpu";
    private static final int PORT = 8080;
    private static final String DEFAULT_MODEL_NAME = "all_MiniLM_L6_v2";
    private static final String DEFAULT_MODEL_URI =
            "djl://ai.djl.huggingface.pytorch/sentence-transformers/all-MiniLM-L6-v2";

    private GenericContainer<?> djlServing;
    private String modelName = DEFAULT_MODEL_NAME;

    @Override
    public void init(Map<String, String> initArgs) {
        if (initArgs != null && initArgs.containsKey("model-name")) {
            this.modelName = initArgs.get("model-name");
        }
    }

    @Override
    public Map<String, String> start() {
        String imageName = System.getenv().getOrDefault("DJL_SERVING_IMAGE", DEFAULT_IMAGE);

        log.info("Starting DJL Serving container with image: {}", imageName);

        djlServing = new GenericContainer<>(DockerImageName.parse(imageName))
                .withExposedPorts(PORT)
                .withEnv("MODEL_LOADING_TIMEOUT", "300")
                .withEnv("JAVA_OPTS", "-Xmx4g -Xms1g -XX:+ExitOnOutOfMemoryError")
                .waitingFor(Wait.forHttp("/ping")
                        .forStatusCode(200)
                        .withStartupTimeout(Duration.ofMinutes(3)));

        djlServing.start();

        String host = djlServing.getHost();
        Integer mappedPort = djlServing.getMappedPort(PORT);
        String baseUrl = String.format("http://%s:%d", host, mappedPort);

        // Register the model via DJL Serving management API
        registerModel(baseUrl, modelName);

        String predictionsUrl = String.format("%s/predictions/%s", baseUrl, modelName);

        log.info("DJL Serving container started at: {}", baseUrl);
        log.info("Predictions endpoint: {}", predictionsUrl);

        return Map.of(
                "embedder.backend", "DJL_SERVING",
                "embedder.djl-serving.url", baseUrl,
                "embedder.djl-serving.model-name", modelName,
                "djl-serving.url", baseUrl,
                "djl-serving.predictions-url", predictionsUrl
        );
    }

    /**
     * Get the base URL of the DJL Serving instance.
     *
     * @return base URL or null if container is not running
     */
    public String getBaseUrl() {
        if (djlServing == null || !djlServing.isRunning()) {
            return null;
        }
        return String.format("http://%s:%d", djlServing.getHost(), djlServing.getMappedPort(PORT));
    }

    /**
     * Get the predictions URL for the loaded model.
     *
     * @return predictions URL or null if container is not running
     */
    public String getPredictionsUrl() {
        String baseUrl = getBaseUrl();
        if (baseUrl == null) {
            return null;
        }
        return String.format("%s/predictions/%s", baseUrl, modelName);
    }

    /**
     * Get the model name (endpoint name) used by this container.
     *
     * @return model name
     */
    public String getModelName() {
        return modelName;
    }

    /**
     * Get the mapped port for the DJL Serving container.
     *
     * @return mapped port or null if container is not running
     */
    public Integer getMappedPort() {
        if (djlServing == null || !djlServing.isRunning()) {
            return null;
        }
        return djlServing.getMappedPort(PORT);
    }

    private void registerModel(String baseUrl, String modelName) {
        String modelUri = DEFAULT_MODEL_URI;
        log.info("Registering model '{}' from {} at {}", modelName, modelUri, baseUrl);

        String encodedUri;
        try {
            encodedUri = URLEncoder.encode(modelUri, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("Failed to encode model URI", e);
        }

        String endpoint = baseUrl + "/models?url=" + encodedUri
                + "&model_name=" + modelName
                + "&engine=PyTorch"
                + "&synchronous=true"
                + "&translatorFactory=ai.djl.huggingface.translator.TextEmbeddingTranslatorFactory";

        int maxRetries = 5;
        for (int i = 0; i < maxRetries; i++) {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(5_000);
                conn.setReadTimeout(120_000);

                int status = conn.getResponseCode();
                if (status >= 200 && status < 300) {
                    log.info("Model '{}' registered successfully (HTTP {})", modelName, status);
                    return;
                }

                String error = "N/A";
                try (InputStream es = conn.getErrorStream()) {
                    if (es != null) error = new String(es.readAllBytes());
                }
                log.warn("Registration attempt {}/{}: HTTP {} — {}", i + 1, maxRetries, status, error);
            } catch (Exception e) {
                log.warn("Registration attempt {}/{} failed: {}", i + 1, maxRetries, e.getMessage());
            }
            try { Thread.sleep(2_000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }
        throw new RuntimeException("Failed to register model '" + modelName + "' after " + maxRetries + " attempts");
    }

    @Override
    public void stop() {
        if (djlServing != null) {
            log.info("Stopping DJL Serving container");
            djlServing.stop();
        }
    }
}
