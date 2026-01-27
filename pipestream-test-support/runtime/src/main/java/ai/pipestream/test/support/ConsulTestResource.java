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
 * Shared test resource for setting up Consul container for service discovery tests.
 * <p>
 * Provides a Consul instance for testing service registration and discovery.
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

    private static final String DEFAULT_IMAGE = "hashicorp/consul:1.19";
    private static final int HTTP_PORT = 8500;
    private static final int GRPC_PORT = 8502;

    private GenericContainer<?> consul;

    @Override
    public Map<String, String> start() {
        String imageName = System.getenv().getOrDefault("CONSUL_IMAGE", DEFAULT_IMAGE);

        log.info("Starting Consul container with image: {}", imageName);

        consul = new GenericContainer<>(DockerImageName.parse(imageName))
                .withExposedPorts(HTTP_PORT, GRPC_PORT)
                .withCommand("agent", "-dev", "-client=0.0.0.0")
                .waitingFor(Wait.forHttp("/v1/status/leader")
                        .forPort(HTTP_PORT)
                        .forStatusCode(200)
                        .withStartupTimeout(Duration.ofMinutes(1)));

        consul.start();

        String host = consul.getHost();
        Integer mappedHttpPort = consul.getMappedPort(HTTP_PORT);
        Integer mappedGrpcPort = consul.getMappedPort(GRPC_PORT);
        String hostPort = String.format("%s:%d", host, mappedHttpPort);
        String consulUrl = String.format("http://%s", hostPort);

        log.info("Consul container started at: {}", consulUrl);

        return Map.ofEntries(
                // Quarkus Consul Config
                Map.entry("quarkus.consul-config.agent.host-port", hostPort),
                Map.entry("quarkus.consul-config.enabled", "true"),

                // Stork service discovery
                Map.entry("quarkus.stork.service-discovery.type", "consul"),
                Map.entry("quarkus.stork.service-discovery.consul-host", host),
                Map.entry("quarkus.stork.service-discovery.consul-port", String.valueOf(mappedHttpPort)),

                // Service registration (pipestream.registration.* prefix)
                Map.entry("pipestream.registration.consul.host", host),
                Map.entry("pipestream.registration.consul.port", String.valueOf(mappedHttpPort)),
                Map.entry("pipestream.registration.consul.url", consulUrl),

                // Pipeline Consul client (used by platform-registration-service ConsulClientProducer)
                Map.entry("pipeline.consul.host", host),
                Map.entry("pipeline.consul.port", String.valueOf(mappedHttpPort)),

                // Dynamic gRPC Consul configuration
                Map.entry("quarkus.dynamic-grpc.consul.host", host),
                Map.entry("quarkus.dynamic-grpc.consul.port", String.valueOf(mappedHttpPort)),

                // Legacy/generic properties
                Map.entry("consul.host", host),
                Map.entry("consul.port", String.valueOf(mappedHttpPort)),
                Map.entry("consul.url", consulUrl),
                Map.entry("consul.grpc-port", String.valueOf(mappedGrpcPort))
        );
    }

    /**
     * Get the Consul HTTP URL.
     *
     * @return HTTP URL or null if container is not running
     */
    public String getHttpUrl() {
        if (consul == null || !consul.isRunning()) {
            return null;
        }
        return String.format("http://%s:%d", consul.getHost(), consul.getMappedPort(HTTP_PORT));
    }

    /**
     * Get the Consul host.
     *
     * @return host or null if container is not running
     */
    public String getHost() {
        if (consul == null || !consul.isRunning()) {
            return null;
        }
        return consul.getHost();
    }

    /**
     * Get the mapped HTTP port.
     *
     * @return mapped HTTP port or null if container is not running
     */
    public Integer getHttpPort() {
        if (consul == null || !consul.isRunning()) {
            return null;
        }
        return consul.getMappedPort(HTTP_PORT);
    }

    /**
     * Get the mapped gRPC port.
     *
     * @return mapped gRPC port or null if container is not running
     */
    public Integer getGrpcPort() {
        if (consul == null || !consul.isRunning()) {
            return null;
        }
        return consul.getMappedPort(GRPC_PORT);
    }

    @Override
    public void stop() {
        if (consul != null) {
            log.info("Stopping Consul container");
            consul.stop();
        }
    }
}
