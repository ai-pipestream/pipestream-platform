package ai.pipestream.test.support;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.consul.ConsulContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.HashMap;
import java.util.Map;

/**
 * Shared test resource for setting up Consul container for service discovery tests.
 * <p>
 * Provides a Consul instance for testing service registration and discovery.
 * Uses the official Testcontainers Consul module for simplified setup.
 * <p>
 * Usage:
 * <pre>
 * {@code
 * @QuarkusTest
 * @QuarkusTestResource(ConsulTestResource.class)
 * public class MyServiceTest {
 *     // Tests can use quarkus.consul-config.agent.host-port property
 * }
 * }
 * </pre>
 */
public class ConsulTestResource implements QuarkusTestResourceLifecycleManager {

    private static final Logger log = LoggerFactory.getLogger(ConsulTestResource.class);

    private static final DockerImageName CONSUL_IMAGE = DockerImageName.parse("hashicorp/consul:1.22");
    private static final int HTTP_PORT = 8500;
    private static final int GRPC_PORT = 8502;

    // Static container shared across tests
    private static ConsulContainer consulContainer;

    @Override
    public Map<String, String> start() {
        log.info("Starting Consul container with image: {}", CONSUL_IMAGE);

        consulContainer = new ConsulContainer(CONSUL_IMAGE)
                .withConsulCommand("agent -dev -ui -client=0.0.0.0 -log-level=INFO");

        consulContainer.start();

        String host = consulContainer.getHost();
        Integer mappedHttpPort = consulContainer.getMappedPort(HTTP_PORT);
        Integer mappedGrpcPort = consulContainer.getMappedPort(GRPC_PORT);
        String hostPort = String.format("%s:%d", host, mappedHttpPort);
        String consulUrl = String.format("http://%s", hostPort);

        log.info("Consul container started at: {}", consulUrl);

        Map<String, String> config = new HashMap<>();

        // Quarkus Consul Config
        config.put("quarkus.consul-config.agent.host-port", hostPort);
        config.put("quarkus.consul-config.enabled", "true");

        // Stork service discovery
        config.put("quarkus.stork.service-discovery.type", "consul");
        config.put("quarkus.stork.service-discovery.consul-host", host);
        config.put("quarkus.stork.service-discovery.consul-port", String.valueOf(mappedHttpPort));

        // Service registration (pipestream.registration.* prefix)
        config.put("pipestream.registration.consul.host", host);
        config.put("pipestream.registration.consul.port", String.valueOf(mappedHttpPort));
        config.put("pipestream.registration.consul.url", consulUrl);

        // Pipeline Consul client (used by platform-registration-service ConsulClientProducer)
        config.put("pipeline.consul.host", host);
        config.put("pipeline.consul.port", String.valueOf(mappedHttpPort));

        // Dynamic gRPC Consul configuration
        config.put("quarkus.dynamic-grpc.consul.host", host);
        config.put("quarkus.dynamic-grpc.consul.port", String.valueOf(mappedHttpPort));
        config.put("quarkus.dynamic-grpc.consul.refresh-period", "2s");
        config.put("quarkus.dynamic-grpc.consul.use-health-checks", "false");

        // Legacy/generic properties
        config.put("consul.host", host);
        config.put("consul.port", String.valueOf(mappedHttpPort));
        config.put("consul.url", consulUrl);
        config.put("consul.grpc-port", String.valueOf(mappedGrpcPort));

        return config;
    }

    /**
     * Get the Consul HTTP URL.
     *
     * @return HTTP URL or null if container is not running
     */
    public String getHttpUrl() {
        if (consulContainer == null || !consulContainer.isRunning()) {
            return null;
        }
        return String.format("http://%s:%d", consulContainer.getHost(), consulContainer.getMappedPort(HTTP_PORT));
    }

    /**
     * Get the Consul host.
     *
     * @return host or null if container is not running
     */
    public String getHost() {
        if (consulContainer == null || !consulContainer.isRunning()) {
            return null;
        }
        return consulContainer.getHost();
    }

    /**
     * Get the mapped HTTP port.
     *
     * @return mapped HTTP port or null if container is not running
     */
    public Integer getHttpPort() {
        if (consulContainer == null || !consulContainer.isRunning()) {
            return null;
        }
        return consulContainer.getMappedPort(HTTP_PORT);
    }

    /**
     * Get the mapped gRPC port.
     *
     * @return mapped gRPC port or null if container is not running
     */
    public Integer getGrpcPort() {
        if (consulContainer == null || !consulContainer.isRunning()) {
            return null;
        }
        return consulContainer.getMappedPort(GRPC_PORT);
    }

    /**
     * Returns the Consul container instance for tests that need direct access.
     *
     * @return the running ConsulContainer, or null if not started
     */
    public static ConsulContainer getConsulContainer() {
        return consulContainer;
    }

    /**
     * Returns the Consul HTTP endpoint for direct API calls.
     *
     * @return the base HTTP URL of Consul, or null if container not started
     */
    public static String getConsulHttpEndpoint() {
        if (consulContainer != null) {
            return "http://" + consulContainer.getHost() + ":" + consulContainer.getMappedPort(HTTP_PORT);
        }
        return null;
    }

    @Override
    public void stop() {
        if (consulContainer != null) {
            log.info("Stopping Consul container");
            consulContainer.stop();
            consulContainer = null;
        }
    }
}
