package ai.pipestream.registration;

import ai.pipestream.platform.registration.v1.ServiceType;
import ai.pipestream.registration.config.RegistrationConfig;
import ai.pipestream.registration.model.HttpEndpointInfo;
import ai.pipestream.registration.model.ServiceInfo;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    @ConfigProperty(name = "quarkus.http.root-path", defaultValue = "")
    String httpRootPath;

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
        String name = resolveServiceName();
        ServiceType type = resolveServiceType();
        String version = resolveVersion();
        String advertisedHost = config.advertisedHost();
        int advertisedPort = resolveAdvertisedPort();
        String internalHost = config.internalHost().orElse(null);
        Integer internalPort = config.internalPort().orElse(null);
        boolean tlsEnabled = config.tlsEnabled();
        Map<String, String> metadata = collectMetadata();
        List<String> tags = config.tags().orElse(Collections.emptyList());
        List<String> capabilities = config.capabilities().orElse(Collections.emptyList());
        List<HttpEndpointInfo> httpEndpoints = collectHttpEndpoints(advertisedHost);

        ServiceInfo serviceInfo = ServiceInfo.builder()
                .name(name)
                .type(type)
                .version(version)
                .advertisedHost(advertisedHost)
                .advertisedPort(advertisedPort)
                .internalHost(internalHost)
                .internalPort(internalPort)
                .tlsEnabled(tlsEnabled)
                .metadata(metadata)
                .tags(tags)
                .capabilities(capabilities)
                .httpEndpoints(httpEndpoints)
                .build();

        LOG.infof("Collected service metadata: %s", serviceInfo);
        return serviceInfo;
    }

    private String resolveServiceName() {
        return config.serviceName().orElse(applicationName);
    }

    private ServiceType resolveServiceType() {
        String typeString = config.type().toUpperCase();
        return switch (typeString) {
            case "MODULE" -> ServiceType.SERVICE_TYPE_MODULE;
            case "SERVICE" -> ServiceType.SERVICE_TYPE_SERVICE;
            default -> {
                LOG.warnf("Unknown service type '%s', defaulting to SERVICE", typeString);
                yield ServiceType.SERVICE_TYPE_SERVICE;
            }
        };
    }

    private String resolveVersion() {
        return config.version().orElse(applicationVersion);
    }

    private int resolveAdvertisedPort() {
        // Use configured advertised port if specified, otherwise use gRPC port
        return config.advertisedPort().orElse(grpcPort);
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

    private List<HttpEndpointInfo> collectHttpEndpoints(String advertisedHost) {
        RegistrationConfig.HttpConfig httpConfig = config.http();
        if (!httpConfig.enabled()) {
            return Collections.emptyList();
        }

        String scheme = httpConfig.scheme();
        String host = httpConfig.advertisedHost().orElse(advertisedHost);
        int port = httpConfig.advertisedPort().orElse(httpPort);
        String basePath = httpConfig.basePath().orElse(httpRootPath == null ? "" : httpRootPath);
        String healthPath = httpConfig.healthPath();
        boolean tlsEnabled = httpConfig.tlsEnabled();

        HttpEndpointInfo endpoint = new HttpEndpointInfo(
            scheme,
            host,
            port,
            basePath,
            healthPath,
            tlsEnabled
        );

        return List.of(endpoint);
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
