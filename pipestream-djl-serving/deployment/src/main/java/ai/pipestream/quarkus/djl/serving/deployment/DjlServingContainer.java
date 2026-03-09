package ai.pipestream.quarkus.djl.serving.deployment;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.util.Map;

public class DjlServingContainer extends GenericContainer<DjlServingContainer> {

    public static final int DJL_SERVING_PORT = 8080;
    public static final String DJL_VERSION = "0.36.0";
    public static final String DEFAULT_IMAGE_BASE = "deepjavalibrary/djl-serving";
    public static final String DEFAULT_IMAGE = DEFAULT_IMAGE_BASE + ":" + DJL_VERSION;

    public DjlServingContainer(String imageName) {
        super(DockerImageName.parse(imageName == null ? DEFAULT_IMAGE : imageName));
        withExposedPorts(DJL_SERVING_PORT);
        waitingFor(Wait.forHttp("/ping").forPort(DJL_SERVING_PORT));
    }

    public static String resolveImageName(String variant) {
        if ("cpu".equals(variant)) {
            return DEFAULT_IMAGE;
        }
        return DEFAULT_IMAGE_BASE + ":" + DJL_VERSION + "-" + variant;
    }

    public String getUrl() {
        return "http://" + getHost() + ":" + getMappedPort(DJL_SERVING_PORT);
    }
}
