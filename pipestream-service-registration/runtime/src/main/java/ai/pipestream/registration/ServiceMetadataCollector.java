package ai.pipestream.registration;

import ai.pipestream.platform.registration.v1.ServiceType;
import ai.pipestream.registration.config.RegistrationConfig;
import ai.pipestream.registration.model.HttpEndpointInfo;
import ai.pipestream.registration.model.ServiceInfo;
import io.quarkus.grpc.runtime.GrpcServerRecorder;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.net.URI;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Collects service metadata for registration.
 *
 * <p>Auto-discovers service name, version, and other metadata from
 * Quarkus configuration and runtime environment.
 */
@ApplicationScoped
public class ServiceMetadataCollector {

    private static final Logger LOG = Logger.getLogger(ServiceMetadataCollector.class);
    private static final Set<String> RESERVED_GRPC_SERVICES = Set.of(
            "grpc.health.v1.Health",
            "grpc.reflection.v1.ServerReflection",
            "grpc.reflection.v1alpha.ServerReflection"
    );

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
        List<String> grpcServices = collectGrpcServices();
        String httpSchema = config.http().schema().orElse(null);
        String httpSchemaVersion = config.http().schemaVersion().orElse(null);
        String httpSchemaArtifactId = config.http().schemaArtifactId().orElse(null);

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
                .grpcServices(grpcServices)
                .httpSchema(httpSchema)
                .httpSchemaVersion(httpSchemaVersion)
                .httpSchemaArtifactId(httpSchemaArtifactId)
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

        HealthUrlOverride override = null;
        if (httpConfig.healthUrl().isPresent()) {
            String rawHealthUrl = httpConfig.healthUrl().get();
            override = parseHealthUrl(rawHealthUrl, port);
            if (override != null) {
                scheme = override.scheme();
                host = override.host();
                port = override.port();
                healthPath = override.healthPath();
                if ("https".equalsIgnoreCase(scheme)) {
                    tlsEnabled = true;
                }
            } else {
                // Treat as a direct health-path override when not a full URL.
                healthPath = rawHealthUrl;
            }
        }

        if (override == null
                && !basePath.isBlank()
                && !healthPath.isBlank()
                && !healthPath.equals("/q/health")
                && healthPath.startsWith("/")
                && !healthPath.startsWith(basePath)) {
            LOG.warnf("HTTP health path '%s' does not include base path '%s'; the registration service will prepend it.", healthPath, basePath);
        }

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

    private List<String> collectGrpcServices() {
        try {
            List<GrpcServerRecorder.GrpcServiceDefinition> definitions = GrpcServerRecorder.getServices();
            if (definitions == null || definitions.isEmpty()) {
                return Collections.emptyList();
            }
            return definitions.stream()
                    .map(definition -> definition.definition.getServiceDescriptor().getName())
                    .filter(name -> name != null && !name.isBlank())
                    .filter(name -> !RESERVED_GRPC_SERVICES.contains(name))
                    .distinct()
                    .sorted()
                    .collect(Collectors.toList());
        } catch (Exception | NoClassDefFoundError e) {
            LOG.debug("gRPC services are unavailable for registration", e);
            return Collections.emptyList();
        }
    }

    private HealthUrlOverride parseHealthUrl(String rawHealthUrl, int fallbackPort) {
        if (rawHealthUrl == null || rawHealthUrl.isBlank()) {
            return null;
        }
        try {
            URI uri = URI.create(rawHealthUrl);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || host == null) {
                return null;
            }
            int port = uri.getPort();
            int resolvedPort = port != -1 ? port : fallbackPort;
            String path = uri.getRawPath();
            if (path == null || path.isBlank()) {
                path = "/";
            }
            String query = uri.getRawQuery();
            if (query != null && !query.isBlank()) {
                path = path + "?" + query;
            }
            return new HealthUrlOverride(scheme, host, resolvedPort, path);
        } catch (IllegalArgumentException e) {
            LOG.warnf("Invalid health-url '%s'; treating as health-path override.", rawHealthUrl);
            return null;
        }
    }

    private record HealthUrlOverride(String scheme, String host, int port, String healthPath) {
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
