package ai.pipestream.test;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.util.HashMap;
import java.util.Map;

/**
 * Shared test resource for configuring Kafka and Apicurio Registry in
 * integration tests.
 * This resource enforces standard ports for Docker Compose services:
 * - Kafka: localhost:9095
 * - Apicurio: localhost:8082
 * <p>
 * Usage:
 * 
 * @QuarkusTest
 * @QuarkusTestResource(KafkaTestResource.class)
 *                                               public class MyTest { ... }
 */
public class KafkaTestResource implements QuarkusTestResourceLifecycleManager {

    @Override
    public Map<String, String> start() {
        // Kafka configuration is now handled by pipeline-kafka-quarkus-extension
        // and docker-compose.yml
        return new HashMap<>();
    }

    @Override
    public void stop() {
        // No-op: Services are managed by Docker Compose
    }
}
