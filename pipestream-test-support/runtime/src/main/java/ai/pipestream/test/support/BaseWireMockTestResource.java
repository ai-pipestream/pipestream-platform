package ai.pipestream.test.support;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.util.Map;

/**
 * Base class for WireMock test resources that provides common container setup and configuration.
 * Uses {@link TestContainerConstants.WireMock} as the single source of truth for image version
 * and resolution. Defaults to {@code docker.io} for production image registry.
 * Subclasses should override {@link #buildConfig(GenericContainer)} to provide service-specific configuration.
 */
public abstract class BaseWireMockTestResource implements QuarkusTestResourceLifecycleManager {

    protected static final int DEFAULT_HTTP_PORT = TestContainerConstants.WireMock.DEFAULT_HTTP_PORT;
    protected static final int DEFAULT_GRPC_PORT = TestContainerConstants.WireMock.DEFAULT_GRPC_PORT;

    private GenericContainer<?> wireMockContainer;

    @SuppressWarnings("resource")
    @Override
    public Map<String, String> start() {
        TestcontainersReuseGuard.ensureReuseDisabled();
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

    /**
     * Resolves the WireMock container image from system properties, environment variables,
     * or the default {@code docker.io} image defined in {@link TestContainerConstants.WireMock}.
     */
    static String resolveImage() {
        return TestContainerConstants.resolveImage(
                TestContainerConstants.WireMock.IMAGE_PROPERTY,
                TestContainerConstants.WireMock.IMAGE_ENV,
                TestContainerConstants.WireMock.IMAGE_ENV_FALLBACK,
                TestContainerConstants.WireMock.DEFAULT_IMAGE);
    }
}
