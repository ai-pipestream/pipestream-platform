package ai.pipestream.quarkus.devservices.runtime;

import org.eclipse.microprofile.config.spi.ConfigSource;
import org.jboss.logging.Logger;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * ConfigSource that provides both compose devservices configuration and
 * auto-injects common service connection properties for QuarkusDev mode.
 *
 * This enables zero-config development by automatically configuring:
 * - Kafka bootstrap servers
 * - Apicurio Schema Registry URL
 * - OpenSearch connection
 * - Consul service discovery
 * - OTLP observability endpoints
 *
 * All properties can be overridden via application.properties or environment variables.
 */
public class PipelineDevServicesConfigSource implements ConfigSource {

    private static final Logger LOG = Logger.getLogger(PipelineDevServicesConfigSource.class);
    private static final String PREFIX = "quarkus.compose.devservices.";

    /**
     * Standard service connection properties for local development.
     * These match the ports exposed by compose-devservices.yml
     */
    private static final Map<String, String> DEV_SERVICE_PROPERTIES = new HashMap<>();

    static {
        // Enable shared network for DevServices to avoid port mapping issues
        // This allows Kafka DevServices to use internal container hostnames (kafka:9092)
        // instead of trying to map ports to localhost
        // Property: quarkus.devservices.launch-on-shared-network (maps to launchOnSharedNetwork())
        DEV_SERVICE_PROPERTIES.put("quarkus.devservices.launch-on-shared-network", "true");

        // Apicurio Schema Registry (port 8081)
        DEV_SERVICE_PROPERTIES.put("mp.messaging.connector.smallrye-kafka.apicurio.registry.url",
                "http://localhost:8081/apis/registry/v3");

        // OpenSearch configuration (port 9200)
        DEV_SERVICE_PROPERTIES.put("opensearch.hosts", "localhost:9200");

        // Consul service discovery (port 8500)
        DEV_SERVICE_PROPERTIES.put("CONSUL_HOST", "localhost");
        DEV_SERVICE_PROPERTIES.put("CONSUL_PORT", "8500");

        // OpenTelemetry OTLP endpoint (port 5317 for gRPC)
        DEV_SERVICE_PROPERTIES.put("quarkus.otel.exporter.otlp.endpoint", "http://localhost:5317");

        // Note: kafka.bootstrap.servers is NOT set here - we let Quarkus Kafka DevServices
        // auto-detect it from the compose container to preserve the Dev UI functionality
    }

    @Override
    public Set<String> getPropertyNames() {
        Set<String> names = new HashSet<>();
        // Only return property names if the extension is active (enabled property is set)
        if (System.getProperty("pipeline.devservices.enabled") != null) {
            // Compose devservices configuration properties
            names.add(PREFIX + "enabled");
            names.add(PREFIX + "files");
            names.add(PREFIX + "project-name");
            names.add(PREFIX + "start-services");
            names.add(PREFIX + "stop-services");
            names.add(PREFIX + "reuse-project-for-tests");

            // Auto-injected service connection properties
            names.addAll(DEV_SERVICE_PROPERTIES.keySet());
        }
        return names;
    }

    @Override
    public String getValue(String propertyName) {
        // Only provide values if the extension is active (enabled property is set)
        if (System.getProperty("pipeline.devservices.enabled") == null) {
            return null;
        }

        // Handle compose devservices properties
        if (propertyName.startsWith(PREFIX)) {
            String key = propertyName.substring(PREFIX.length());
            String propKey = "pipeline.devservices." + key;
            String value = System.getProperty(propKey);
            if (value != null) {
                LOG.infof("Pipeline DevServices config: %s = %s", propKey, value);
            }
            return value;
        }

        // Handle auto-injected service connection properties
        if (DEV_SERVICE_PROPERTIES.containsKey(propertyName)) {
            String value = DEV_SERVICE_PROPERTIES.get(propertyName);
            LOG.infof("Auto-injecting dev service property: %s = %s", propertyName, value);
            return value;
        }

        return null;
    }

    @Override
    public String getName() {
        return "PipelineDevServicesConfigSource";
    }

    @Override
    public int getOrdinal() {
        // Use ordinal 250 to ensure it takes precedence over default config sources
        // but can still be overridden by application.properties (ordinal 260+)
        // and environment variables (ordinal 300)
        return 250;
    }
}
