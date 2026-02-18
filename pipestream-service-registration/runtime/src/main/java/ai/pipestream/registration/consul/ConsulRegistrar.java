package ai.pipestream.registration.consul;

import ai.pipestream.platform.registration.v1.Connectivity;
import ai.pipestream.platform.registration.v1.HttpEndpoint;
import ai.pipestream.platform.registration.v1.RegisterRequest;
import ai.pipestream.registration.model.ServiceInfo;
import io.smallrye.mutiny.Uni;
import io.vertx.ext.consul.CheckOptions;
import io.vertx.ext.consul.ServiceOptions;
import io.vertx.mutiny.ext.consul.ConsulClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * Handles service registration and unregistration with Consul.
 * Works with both the unified RegisterRequest proto and the extension's ServiceInfo model.
 */
@ApplicationScoped
public class ConsulRegistrar {

    private static final Logger LOG = Logger.getLogger(ConsulRegistrar.class);

    private final ConsulClient consulClient;

    @Inject
    public ConsulRegistrar(ConsulClient consulClient) {
        this.consulClient = consulClient;
    }

    /**
     * Register a service with Consul using the extension's ServiceInfo model.
     * Builds a RegisterRequest internally and delegates to the proto-based method.
     *
     * @param serviceInfo The service information from the extension
     * @return A Uni indicating success or failure
     */
    public Uni<Boolean> registerService(ServiceInfo serviceInfo) {
        String serviceId = generateServiceId(
                serviceInfo.getName(),
                serviceInfo.getAdvertisedHost(),
                serviceInfo.getAdvertisedPort());

        RegisterRequest request = buildRegisterRequest(serviceInfo);
        return registerService(request, serviceId);
    }

    /**
     * Register a service with Consul using the extension's ServiceInfo model and a specific service ID.
     *
     * @param serviceInfo The service information from the extension
     * @param serviceId   The unique Consul service ID to use
     * @return A Uni indicating success or failure
     */
    public Uni<Boolean> registerService(ServiceInfo serviceInfo, String serviceId) {
        RegisterRequest request = buildRegisterRequest(serviceInfo);
        return registerService(request, serviceId);
    }

    /**
     * Register a service with Consul including health check configuration.
     *
     * @param request   The unified RegisterRequest (works for both services and modules)
     * @param serviceId The unique Consul service ID to use
     * @return A Uni indicating success or failure
     */
    public Uni<Boolean> registerService(RegisterRequest request, String serviceId) {
        Connectivity connectivity = request.getConnectivity();

        // Use internal host/port for Consul service address (what Consul uses to reach the service)
        // Fall back to advertised if internal is not specified
        String consulHost = connectivity.hasInternalHost()
                ? connectivity.getInternalHost()
                : connectivity.getAdvertisedHost();
        int consulPort = connectivity.hasInternalPort()
                ? connectivity.getInternalPort()
                : connectivity.getAdvertisedPort();

        // Sanitize metadata keys (Consul doesn't allow dots in metadata keys)
        Map<String, String> sanitizedMetadata = sanitizeMetadataKeys(request.getMetadataMap());

        // Store advertised address in metadata for client discovery
        sanitizedMetadata.put("advertised-host", connectivity.getAdvertisedHost());
        sanitizedMetadata.put("advertised-port", String.valueOf(connectivity.getAdvertisedPort()));

        // Add version to metadata
        sanitizedMetadata.put("version", request.getVersion());

        // Add service type to metadata for identification
        sanitizedMetadata.put("service-type", request.getType().name());

        // Add HTTP endpoints to metadata for discovery (if provided)
        if (request.getHttpEndpointsCount() > 0) {
            sanitizedMetadata.put("http_endpoint_count", String.valueOf(request.getHttpEndpointsCount()));
            for (int i = 0; i < request.getHttpEndpointsCount(); i++) {
                var endpoint = request.getHttpEndpoints(i);
                String prefix = "http_endpoint_" + i + "_";
                sanitizedMetadata.put(prefix + "scheme", endpoint.getScheme());
                sanitizedMetadata.put(prefix + "host", endpoint.getHost());
                sanitizedMetadata.put(prefix + "port", String.valueOf(endpoint.getPort()));
                if (!endpoint.getBasePath().isBlank()) {
                    sanitizedMetadata.put(prefix + "base_path", endpoint.getBasePath());
                }
                if (!endpoint.getHealthPath().isBlank()) {
                    sanitizedMetadata.put(prefix + "health_path", endpoint.getHealthPath());
                }
                sanitizedMetadata.put(prefix + "tls_enabled", String.valueOf(endpoint.getTlsEnabled()));
            }
        }

        if (request.hasHttpSchemaArtifactId()) {
            sanitizedMetadata.put("http_schema_artifact_id", request.getHttpSchemaArtifactId());
        }
        if (request.hasHttpSchemaVersion()) {
            sanitizedMetadata.put("http_schema_version", request.getHttpSchemaVersion());
        }

        ServiceOptions serviceOptions = new ServiceOptions()
                .setId(serviceId)
                .setName(request.getName())
                .setAddress(consulHost)
                .setPort(consulPort)
                .setTags(new ArrayList<>(request.getTagsList()))
                .setMeta(sanitizedMetadata);

        // Add capabilities as tags with prefix
        request.getCapabilitiesList().forEach(cap ->
                serviceOptions.getTags().add("capability:" + cap)
        );

        boolean grpcTlsEnabled = connectivity.getTlsEnabled();
        ArrayList<CheckOptions> grpcChecks = new ArrayList<>();
        if (request.getGrpcServicesCount() > 0) {
            grpcChecks.add(buildGrpcCheck(request.getName(), consulHost, consulPort, grpcTlsEnabled, null));
            request.getGrpcServicesList().stream()
                    .map(String::trim)
                    .filter(name -> !name.isEmpty())
                    .distinct()
                    .forEach(serviceName ->
                            grpcChecks.add(buildGrpcCheck(request.getName(), consulHost, consulPort, grpcTlsEnabled, serviceName)));
        }

        CheckOptions httpCheck = null;
        if (request.getHttpEndpointsCount() > 0) {
            for (var endpoint : request.getHttpEndpointsList()) {
                if (!endpoint.getHealthPath().isBlank()) {
                    String defaultScheme = endpoint.getTlsEnabled() ? "https" : "http";
                    String scheme = endpoint.getScheme().isBlank() ? defaultScheme : endpoint.getScheme();

                    String checkHost = endpoint.getHost().isBlank() ? consulHost : endpoint.getHost();
                    int checkPort = endpoint.getPort() == 0 ? consulPort : endpoint.getPort();
                    String rawHealthPath = endpoint.getHealthPath();
                    String checkUrl;
                    if (rawHealthPath.contains("://")) {
                        checkUrl = rawHealthPath;
                        LOG.debugf("Using absolute health URL override for %s: %s", serviceId, checkUrl);
                    } else {
                        String effectiveHealthPath = joinPaths(endpoint.getBasePath(), rawHealthPath);
                        checkUrl = String.format("%s://%s:%d%s",
                                scheme, checkHost, checkPort, effectiveHealthPath);
                    }

                    httpCheck = new CheckOptions()
                            .setName(request.getName() + " HTTP Health Check")
                            .setInterval("10s")
                            .setDeregisterAfter("1m")
                            .setHttp(checkUrl);

                    if (endpoint.getTlsEnabled()) {
                        httpCheck.setTlsSkipVerify(true);
                    }

                    LOG.infof("Configuring HTTP health check for %s: %s (tlsSkipVerify=%b)",
                            serviceId, checkUrl, endpoint.getTlsEnabled());
                    break;
                }
            }
        }

        ArrayList<CheckOptions> checks = new ArrayList<>(grpcChecks);
        if (httpCheck != null) {
            checks.add(httpCheck);
        }

        if (checks.size() == 1) {
            serviceOptions.setCheckOptions(checks.getFirst());
        } else if (!checks.isEmpty()) {
            serviceOptions.setCheckListOptions(checks);
        }

        LOG.infof("Registering service with Consul: %s (type=%s)", serviceId, request.getType());

        return consulClient.registerService(serviceOptions)
                .map(v -> {
                    LOG.infof("Successfully registered service: %s", serviceId);
                    return true;
                })
                .onFailure().recoverWithItem(throwable -> {
                    LOG.errorf(throwable, "Failed to register service: %s", serviceId);
                    return false;
                });
    }

    /**
     * Unregister a service from Consul.
     */
    public Uni<Boolean> unregisterService(String serviceId) {
        LOG.infof("Unregistering service from Consul: %s", serviceId);

        return consulClient.deregisterService(serviceId)
                .map(v -> {
                    LOG.infof("Successfully unregistered service: %s", serviceId);
                    return true;
                })
                .onFailure().recoverWithItem(throwable -> {
                    LOG.errorf(throwable, "Failed to unregister service: %s", serviceId);
                    return false;
                });
    }

    /**
     * Generate a consistent service ID from service details.
     */
    public static String generateServiceId(String serviceName, String host, int port) {
        return String.format("%s-%s-%d", serviceName, host, port);
    }

    /**
     * Build a RegisterRequest proto from a ServiceInfo model.
     * Extracts the conversion logic that was previously in RegistrationClient.register().
     */
    static RegisterRequest buildRegisterRequest(ServiceInfo serviceInfo) {
        Connectivity.Builder connectivityBuilder = Connectivity.newBuilder()
                .setAdvertisedHost(serviceInfo.getAdvertisedHost())
                .setAdvertisedPort(serviceInfo.getAdvertisedPort())
                .setTlsEnabled(serviceInfo.isTlsEnabled());

        if (serviceInfo.getInternalHost() != null) {
            connectivityBuilder.setInternalHost(serviceInfo.getInternalHost());
        }
        if (serviceInfo.getInternalPort() != null) {
            connectivityBuilder.setInternalPort(serviceInfo.getInternalPort());
        }

        RegisterRequest.Builder requestBuilder = RegisterRequest.newBuilder()
                .setName(serviceInfo.getName())
                .setType(serviceInfo.getType())
                .setConnectivity(connectivityBuilder.build())
                .putAllMetadata(serviceInfo.getMetadata())
                .addAllTags(serviceInfo.getTags())
                .addAllCapabilities(serviceInfo.getCapabilities());

        if (!serviceInfo.getHttpEndpoints().isEmpty()) {
            serviceInfo.getHttpEndpoints().forEach(endpoint ->
                    requestBuilder.addHttpEndpoints(HttpEndpoint.newBuilder()
                            .setScheme(endpoint.getScheme())
                            .setHost(endpoint.getHost())
                            .setPort(endpoint.getPort())
                            .setBasePath(endpoint.getBasePath())
                            .setHealthPath(endpoint.getHealthPath())
                            .setTlsEnabled(endpoint.isTlsEnabled())
                            .build())
            );
        }

        if (!serviceInfo.getGrpcServices().isEmpty()) {
            requestBuilder.addAllGrpcServices(serviceInfo.getGrpcServices());
        }

        if (serviceInfo.getHttpSchema() != null && !serviceInfo.getHttpSchema().isBlank()) {
            requestBuilder.setHttpSchema(serviceInfo.getHttpSchema());
        }
        if (serviceInfo.getHttpSchemaVersion() != null && !serviceInfo.getHttpSchemaVersion().isBlank()) {
            requestBuilder.setHttpSchemaVersion(serviceInfo.getHttpSchemaVersion());
        }
        if (serviceInfo.getHttpSchemaArtifactId() != null && !serviceInfo.getHttpSchemaArtifactId().isBlank()) {
            requestBuilder.setHttpSchemaArtifactId(serviceInfo.getHttpSchemaArtifactId());
        }

        if (serviceInfo.getVersion() != null) {
            requestBuilder.setVersion(serviceInfo.getVersion());
        }

        return requestBuilder.build();
    }

    private Map<String, String> sanitizeMetadataKeys(Map<String, String> metadata) {
        Map<String, String> sanitized = new HashMap<>();
        for (Map.Entry<String, String> entry : metadata.entrySet()) {
            String sanitizedKey = entry.getKey().replace('.', '_');
            sanitized.put(sanitizedKey, entry.getValue());
        }
        return sanitized;
    }

    private static String joinPaths(String basePath, String healthPath) {
        String base = basePath == null ? "" : basePath.trim();
        String health = healthPath == null ? "" : healthPath.trim();
        if (health.isEmpty()) {
            return ensureLeadingSlash(base);
        }
        if (base.isEmpty() || "/".equals(base)) {
            return ensureLeadingSlash(health);
        }

        String normalizedBase = ensureLeadingSlash(stripTrailingSlash(base));
        String normalizedHealth = ensureLeadingSlash(health);
        if (normalizedHealth.equals(normalizedBase) || normalizedHealth.startsWith(normalizedBase + "/")) {
            return normalizedHealth;
        }
        return normalizedBase + normalizedHealth;
    }

    private static String ensureLeadingSlash(String path) {
        if (path == null || path.isEmpty()) {
            return "";
        }
        return path.charAt(0) == '/' ? path : "/" + path;
    }

    private static String stripTrailingSlash(String path) {
        if (path == null || path.isEmpty()) {
            return "";
        }
        int end = path.length();
        while (end > 1 && path.charAt(end - 1) == '/') {
            end--;
        }
        return path.substring(0, end);
    }

    private static CheckOptions buildGrpcCheck(String serviceName, String host, int port,
                                               boolean tlsEnabled, String grpcServiceName) {
        String target = formatGrpcTarget(host, port, grpcServiceName);
        String checkName = grpcServiceName == null || grpcServiceName.isBlank()
                ? serviceName + " gRPC Health Check"
                : serviceName + " gRPC Health Check (" + grpcServiceName + ")";

        CheckOptions check = new CheckOptions()
                .setName(checkName)
                .setInterval("10s")
                .setDeregisterAfter("1m")
                .setGrpc(target);

        if (tlsEnabled) {
            check.setGrpcTls(true);
        }

        LOG.infof("Configuring gRPC health check for %s: %s", serviceName, target);
        return check;
    }

    private static String formatGrpcTarget(String host, int port, String serviceName) {
        if (serviceName == null || serviceName.isBlank()) {
            return host + ":" + port;
        }
        String normalized = serviceName.startsWith("/") ? serviceName.substring(1) : serviceName;
        return host + ":" + port + "/" + normalized;
    }
}
