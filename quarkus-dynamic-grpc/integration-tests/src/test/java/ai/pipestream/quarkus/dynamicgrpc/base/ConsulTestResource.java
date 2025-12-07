package ai.pipestream.quarkus.dynamicgrpc.base;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import org.testcontainers.consul.ConsulContainer;
import org.testcontainers.utility.DockerImageName;
import org.jboss.logging.Logger;

import java.util.HashMap;
import java.util.Map;

/**
 * TestContainers resource for Consul - provides real Consul instance for integration tests.
 * Uses random ports to avoid conflicts.
 */
public class ConsulTestResource implements QuarkusTestResourceLifecycleManager {

    private static final Logger LOG = Logger.getLogger(ConsulTestResource.class);

    private static final DockerImageName CONSUL_IMAGE = DockerImageName.parse("consul:1.15");
    private static ConsulContainer consulContainer;

    /**
     * Starts a Consul TestContainers instance and exposes configuration overrides
     * for the tests to connect to it.
     *
     * @return a map of configuration properties including consul host, port, refresh period, and health check usage
     */
    @Override
    public Map<String, String> start() {
        // Start Consul container with random mapped port
        consulContainer = new ConsulContainer(CONSUL_IMAGE)
            .withConsulCommand("agent -dev -ui -client=0.0.0.0 -log-level=INFO");

        consulContainer.start();

        // Get the mapped port (random host port)
        int consulPort = consulContainer.getMappedPort(8500);
        String consulHost = consulContainer.getHost();

        Map<String, String> config = new HashMap<>();
        config.put("quarkus.dynamic-grpc.consul.host", consulHost);
        config.put("quarkus.dynamic-grpc.consul.port", String.valueOf(consulPort));
        config.put("quarkus.dynamic-grpc.consul.refresh-period", "2s");
        config.put("quarkus.dynamic-grpc.consul.use-health-checks", "false");

        LOG.infof("Consul container started at %s:%d", consulHost, consulPort);

        return config;
    }

    /**
     * Stops the Consul container if it was started.
     */
    @Override
    public void stop() {
        if (consulContainer != null) {
            consulContainer.stop();
        }
    }

    /**
     * Returns the Consul container instance for tests that need direct access
     * (e.g., for registering services).
     *
     * @return the running ConsulContainer, or {@code null} if not started
     */
    public static ConsulContainer getConsulContainer() {
        return consulContainer;
    }

    /**
     * Returns the Consul HTTP endpoint for direct API calls.
     *
     * @return the base HTTP URL of Consul (e.g., http://localhost:8500), or {@code null} if container not started
     */
    public static String getConsulHttpEndpoint() {
        if (consulContainer != null) {
            return "http://" + consulContainer.getHost() + ":" + consulContainer.getMappedPort(8500);
        }
        return null;
    }
}
