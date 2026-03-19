package ai.pipestream.test.support;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.consul.ConsulContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.HashMap;
import java.util.Map;

/**
 * Singleton Consul test resource shared across all test classes.
 * <p>
 * Starts ONE Consul container on first use, sets system properties so all
 * tests (and their ConsulServiceRegistration helpers) see the same port.
 * Container stays alive for the entire test JVM — no restart between classes.
 */
public class ConsulTestResource implements QuarkusTestResourceLifecycleManager {

    private static final Logger log = LoggerFactory.getLogger(ConsulTestResource.class);

    private static final DockerImageName CONSUL_IMAGE = DockerImageName.parse("hashicorp/consul:1.22");
    private static final int HTTP_PORT = 8500;
    private static final int GRPC_PORT = 8502;

    // Singleton — one container for the entire test JVM
    private static ConsulContainer consulContainer;
    private static String host;
    private static int mappedHttpPort;
    private static int mappedGrpcPort;

    @Override
    public Map<String, String> start() {
        if (consulContainer != null && consulContainer.isRunning()) {
            log.info("Reusing existing Consul container at {}:{}", host, mappedHttpPort);
            return buildConfig();
        }

        log.info("Starting singleton Consul container with image: {}", CONSUL_IMAGE);

        consulContainer = new ConsulContainer(CONSUL_IMAGE)
                .withConsulCommand("agent -dev -ui -client=0.0.0.0 -log-level=INFO");

        consulContainer.start();

        host = consulContainer.getHost();
        mappedHttpPort = consulContainer.getMappedPort(HTTP_PORT);
        mappedGrpcPort = consulContainer.getMappedPort(GRPC_PORT);

        log.info("Consul container started at http://{}:{}", host, mappedHttpPort);

        // Set system properties so ConsulServiceRegistration and any non-CDI code
        // can discover the container port without relying on Quarkus config injection
        System.setProperty("quarkus.dynamic-grpc.consul.host", host);
        System.setProperty("quarkus.dynamic-grpc.consul.port", String.valueOf(mappedHttpPort));
        System.setProperty("consul.host", host);
        System.setProperty("consul.port", String.valueOf(mappedHttpPort));

        return buildConfig();
    }

    private Map<String, String> buildConfig() {
        String hostPort = String.format("%s:%d", host, mappedHttpPort);
        String consulUrl = String.format("http://%s", hostPort);

        Map<String, String> config = new HashMap<>();

        // Quarkus Consul Config
        config.put("quarkus.consul-config.agent.host-port", hostPort);
        config.put("quarkus.consul-config.enabled", "true");

        // Shared Pipestream Consul client
        config.put("pipestream.consul.host", host);
        config.put("pipestream.consul.port", String.valueOf(mappedHttpPort));

        // Dynamic gRPC Consul configuration
        config.put("quarkus.dynamic-grpc.consul.host", host);
        config.put("quarkus.dynamic-grpc.consul.port", String.valueOf(mappedHttpPort));
        config.put("quarkus.dynamic-grpc.consul.refresh-period", "1s");
        config.put("quarkus.dynamic-grpc.consul.use-health-checks", "false");

        // Legacy properties
        config.put("pipeline.consul.host", host);
        config.put("pipeline.consul.port", String.valueOf(mappedHttpPort));
        config.put("consul.host", host);
        config.put("consul.port", String.valueOf(mappedHttpPort));
        config.put("consul.url", consulUrl);
        config.put("consul.grpc-port", String.valueOf(mappedGrpcPort));

        return config;
    }

    @Override
    public void stop() {
        // Don't stop — singleton lives for the entire test JVM.
        // Testcontainers Ryuk handles cleanup when the JVM exits.
        log.info("ConsulTestResource.stop() called — singleton container stays alive (Ryuk will clean up)");
    }

    /**
     * Returns the Consul HTTP port for direct access from test helpers.
     */
    public static int getHttpPort() {
        return mappedHttpPort;
    }

    /**
     * Returns the Consul host for direct access from test helpers.
     */
    public static String getConsulHost() {
        return host;
    }
}
