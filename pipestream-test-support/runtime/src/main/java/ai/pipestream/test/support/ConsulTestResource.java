package ai.pipestream.test.support;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.consul.ConsulContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.HashMap;
import java.util.Map;

/**
 * Test resource for setting up an isolated Consul container for service discovery tests.
 * <p>
 * Provides a dedicated Consul instance for a test class. 
 * Uses the official Testcontainers Consul module.
 */
public class ConsulTestResource implements QuarkusTestResourceLifecycleManager {

    private static final Logger log = LoggerFactory.getLogger(ConsulTestResource.class);

    private static final DockerImageName CONSUL_IMAGE = DockerImageName.parse("hashicorp/consul:1.22");
    private static final int HTTP_PORT = 8500;
    private static final int GRPC_PORT = 8502;

    private ConsulContainer consulContainer;

    @Override
    public Map<String, String> start() {
        log.info("Starting isolated Consul container with image: {}", CONSUL_IMAGE);

        consulContainer = new ConsulContainer(CONSUL_IMAGE)
                .withCommand("agent", "-dev", "-ui", "-client=0.0.0.0", "-log-level=INFO");

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

        // Shared Pipestream Consul client (PipestreamConsulClientProducer)
        config.put("pipestream.consul.host", host);
        config.put("pipestream.consul.port", String.valueOf(mappedHttpPort));

        // Legacy properties
        config.put("pipeline.consul.host", host);
        config.put("pipeline.consul.port", String.valueOf(mappedHttpPort));

        // Dynamic gRPC Consul configuration
        config.put("quarkus.dynamic-grpc.consul.host", host);
        config.put("quarkus.dynamic-grpc.consul.port", String.valueOf(mappedHttpPort));
        config.put("quarkus.dynamic-grpc.consul.refresh-period", "1s"); // Fast refresh for tests
        config.put("quarkus.dynamic-grpc.consul.use-health-checks", "false");

        // Legacy/generic properties
        config.put("consul.host", host);
        config.put("consul.port", String.valueOf(mappedHttpPort));
        config.put("consul.url", consulUrl);
        config.put("consul.grpc-port", String.valueOf(mappedGrpcPort));

        return config;
    }

    @Override
    public void stop() {
        if (consulContainer != null) {
            log.info("Stopping Consul container at: {}", 
                String.format("http://%s:%d", consulContainer.getHost(), consulContainer.getMappedPort(HTTP_PORT)));
            consulContainer.stop();
            consulContainer = null;
        }
    }
}
