package ai.pipestream.test.support;

import org.jboss.logging.Logger;
import org.opensearch.testcontainers.OpenSearchContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.HashMap;
import java.util.Map;

/**
 * Quarkus test resource that starts a real OpenSearch container via testcontainers
 * and exposes connection properties through the config map. Intended to replace
 * compose devservices in tests.
 */
public class OpensearchContainerTestResource implements io.quarkus.test.common.QuarkusTestResourceLifecycleManager {

    private static final Logger LOG = Logger.getLogger(OpensearchContainerTestResource.class);
    private static final String DEFAULT_IMAGE = "opensearchproject/opensearch:3.5.0";

    private OpenSearchContainer<?> container;

    @Override
    public Map<String, String> start() {
        String image = System.getProperty("pipestream.opensearch.image", DEFAULT_IMAGE);
        container = new OpenSearchContainer<>(DockerImageName.parse(image))
                .withEnv("DISABLE_SECURITY_PLUGIN", "true")
                .withEnv("discovery.type", "single-node")
                .withEnv("bootstrap.memory_lock", "true")
                .withEnv("OPENSEARCH_JAVA_OPTS", "-Xms512m -Xmx512m");

        container.start();
        String host = container.getHttpHostAddress(); // host:port
        LOG.infof("Started OpenSearch testcontainer at %s (image=%s)", host, image);

        Map<String, String> config = new HashMap<>();
        config.put("opensearch.hosts", host.replace("http://", ""));
        config.put("opensearch.protocol", "http");
        // disable any compose/devservices flags
        config.put("quarkus.compose.devservices.enabled", "false");
        return config;
    }

    @Override
    public void stop() {
        if (container != null) {
            container.stop();
        }
    }
}
