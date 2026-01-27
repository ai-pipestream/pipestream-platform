package ai.pipestream.test.support;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

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

    private static final String DEFAULT_IMAGE = "pipestreamai/djl-serving-embedder:main";
    private static final int PORT = 8080;
    private static final String DEFAULT_MODEL_NAME = "all_MiniLM_L6_v2";

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
                .waitingFor(Wait.forHttp("/ping")
                        .forStatusCode(200)
                        .withStartupTimeout(Duration.ofMinutes(3)));

        djlServing.start();

        String host = djlServing.getHost();
        Integer mappedPort = djlServing.getMappedPort(PORT);
        String baseUrl = String.format("http://%s:%d", host, mappedPort);
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

    @Override
    public void stop() {
        if (djlServing != null) {
            log.info("Stopping DJL Serving container");
            djlServing.stop();
        }
    }
}
