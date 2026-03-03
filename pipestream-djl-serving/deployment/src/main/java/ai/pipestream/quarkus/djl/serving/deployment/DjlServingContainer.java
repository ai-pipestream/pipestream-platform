package ai.pipestream.quarkus.djl.serving.deployment;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.util.Map;

public class DjlServingContainer extends GenericContainer<DjlServingContainer> {

    public static final int DJL_SERVING_PORT = 8080;
    public static final String DEFAULT_IMAGE = "pipestreamai/djl-serving-embedder:latest";

    public DjlServingContainer(String imageName) {
        super(DockerImageName.parse(imageName == null ? DEFAULT_IMAGE : imageName));
        withExposedPorts(DJL_SERVING_PORT);
        waitingFor(Wait.forHttp("/ping").forPort(DJL_SERVING_PORT));
    }

    public String getUrl() {
        return "http://" + getHost() + ":" + getMappedPort(DJL_SERVING_PORT);
    }
}
