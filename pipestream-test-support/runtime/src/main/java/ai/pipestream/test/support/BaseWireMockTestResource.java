package ai.pipestream.test.support;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.util.Map;

/**
 * Base class for WireMock test resources that provides common container setup and configuration.
 * Subclasses should override {@link #buildConfig(GenericContainer)} to provide service-specific configuration.
 */
public abstract class BaseWireMockTestResource implements QuarkusTestResourceLifecycleManager {

    private static final String IMAGE_PROPERTY = "pipestream.wiremock.image";
    private static final String IMAGE_ENV = "PIPESTREAM_WIREMOCK_IMAGE";
    private static final String IMAGE_ENV_FALLBACK = "WIREMOCK_IMAGE";
    private static final String DEFAULT_IMAGE = "docker.io/pipestreamai/pipestream-wiremock-server:0.1.38";

    protected static final int DEFAULT_HTTP_PORT = 8080;
    protected static final int DEFAULT_GRPC_PORT = 50052;

    private GenericContainer<?> wireMockContainer;

    @SuppressWarnings("resource")
    @Override
    public Map<String, String> start() {
        wireMockContainer = new GenericContainer<>(DockerImageName.parse(resolveImage()))
                .withExposedPorts(exposedPorts())
                .waitingFor(Wait.forLogMessage(readyLogPattern(), 1));

        configureContainer(wireMockContainer);
        wireMockContainer.start();

        return buildConfig(wireMockContainer);
    }

    @Override
    public void stop() {
        if (wireMockContainer != null) {
            wireMockContainer.stop();
        }
    }

    protected Integer[] exposedPorts() {
        return new Integer[]{DEFAULT_HTTP_PORT, DEFAULT_GRPC_PORT};
    }

    protected String readyLogPattern() {
        return ".*WireMock Server started.*";
    }

    protected void configureContainer(GenericContainer<?> container) {
        // Optional hook for subclasses.
    }

    protected abstract Map<String, String> buildConfig(GenericContainer<?> container);

    protected String getHost() {
        return wireMockContainer.getHost();
    }

    protected int getMappedPort(int port) {
        return wireMockContainer.getMappedPort(port);
    }

    private static String resolveImage() {
        String image = System.getProperty(IMAGE_PROPERTY);
        if (image == null || image.isBlank()) {
            image = System.getenv(IMAGE_ENV);
        }
        if (image == null || image.isBlank()) {
            image = System.getenv(IMAGE_ENV_FALLBACK);
        }
        if (image == null || image.isBlank()) {
            image = DEFAULT_IMAGE;
        }
        return image;
    }
}
