package ai.pipestream.registration;

import ai.pipestream.registration.config.RegistrationConfig;
import ai.pipestream.registration.model.ServiceInfo;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Collects service metadata for registration.
 * 
 * <p>Auto-discovers service name, version, and other metadata from
 * Quarkus configuration and runtime environment.
 */
@ApplicationScoped
public class ServiceMetadataCollector {

    private static final Logger LOG = Logger.getLogger(ServiceMetadataCollector.class);

    private final RegistrationConfig config;

    @ConfigProperty(name = "quarkus.application.name", defaultValue = "unknown-service")
    String applicationName;

    @ConfigProperty(name = "quarkus.application.version", defaultValue = "1.0.0")
    String applicationVersion;

    @ConfigProperty(name = "quarkus.http.port", defaultValue = "8080")
    int httpPort;

    @ConfigProperty(name = "quarkus.grpc.server.port", defaultValue = "9000")
    int grpcPort;

    @Inject
    public ServiceMetadataCollector(RegistrationConfig config) {
        this.config = config;
    }

    /**
     * Collects and returns the service information for registration.
     *
     * @return ServiceInfo containing all collected metadata
     */
    public ServiceInfo collect() {
        String serviceName = resolveServiceName();
        String version = resolveVersion();
        String host = config.host();
        int port = resolvePort();
        Map<String, String> metadata = collectMetadata();

        ServiceInfo serviceInfo = ServiceInfo.builder()
                .serviceName(serviceName)
                .version(version)
                .host(host)
                .port(port)
                .metadata(metadata)
                .build();

        LOG.infof("Collected service metadata: %s", serviceInfo);
        return serviceInfo;
    }

    private String resolveServiceName() {
        return config.serviceName().orElse(applicationName);
    }

    private String resolveVersion() {
        return config.version().orElse(applicationVersion);
    }

    private int resolvePort() {
        // Use configured port if specified, otherwise use HTTP port
        int configuredPort = config.port();
        if (configuredPort != 8080) {
            return configuredPort;
        }
        return httpPort;
    }

    private Map<String, String> collectMetadata() {
        Map<String, String> metadata = new HashMap<>();
        
        // Add HTTP port info
        metadata.put("http.port", String.valueOf(httpPort));
        
        // Add gRPC port info
        metadata.put("grpc.port", String.valueOf(grpcPort));
        
        // Add Java version
        metadata.put("java.version", System.getProperty("java.version", "unknown"));
        
        // Add Quarkus info
        metadata.put("quarkus.version", getQuarkusVersion());

        return metadata;
    }

    private String getQuarkusVersion() {
        try {
            Package quarkusPackage = io.quarkus.runtime.Quarkus.class.getPackage();
            if (quarkusPackage != null && quarkusPackage.getImplementationVersion() != null) {
                return quarkusPackage.getImplementationVersion();
            }
        } catch (Exception e) {
            LOG.trace("Could not determine Quarkus version", e);
        }
        return "unknown";
    }
}
