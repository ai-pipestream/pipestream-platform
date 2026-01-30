package ai.pipestream.server.config;

import io.smallrye.config.ConfigSourceContext;
import io.smallrye.config.ConfigValue;
import org.eclipse.microprofile.config.spi.ConfigSource;
import org.jboss.logging.Logger;

import java.net.InetAddress;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public class PipestreamServerDefaultsConfigSource implements ConfigSource {

    private static final Logger LOG = Logger.getLogger(PipestreamServerDefaultsConfigSource.class);
    private static final int ORDINAL = 50;
    private static final String DEFAULT_LINUX_HOST = "172.17.0.1";
    private static final String DEFAULT_DOCKER_HOST = "host.docker.internal";

    private final Map<String, String> defaults;

    public PipestreamServerDefaultsConfigSource(ConfigSourceContext context) {
        defaults = buildDefaults(context);
    }

    @Override
    public Map<String, String> getProperties() {
        return defaults;
    }

    @Override
    public java.util.Set<String> getPropertyNames() {
        return defaults.keySet();
    }

    @Override
    public String getValue(String propertyName) {
        return defaults.get(propertyName);
    }

    @Override
    public String getName() {
        return "pipestream-server-defaults";
    }

    @Override
    public int getOrdinal() {
        return ORDINAL;
    }

    private Map<String, String> buildDefaults(ConfigSourceContext context) {
        Map<String, String> values = new HashMap<>();

        HostDefaults hostDefaults = resolveHosts(context);
        applyIfMissingAllowingDefaults(context, values, "pipestream.registration.advertised-host",
                hostDefaults.advertisedHost(), "0.0.0.0");
        applyIfMissingAllowingDefaults(context, values, "pipestream.registration.internal-host",
                hostDefaults.internalHost(), "0.0.0.0");

        Integer registrationPort = resolveRegistrationPort(context);
        if (registrationPort != null) {
            applyIfMissingAllowingDefaults(context, values, "pipestream.registration.advertised-port",
                    String.valueOf(registrationPort), "0");
            applyIfMissingAllowingDefaults(context, values, "pipestream.registration.internal-port",
                    String.valueOf(registrationPort), "0");
        }

        String httpRootPath = normalizePath(getOptional(context, "quarkus.http.root-path").orElse(""));
        if (!httpRootPath.isBlank() && !"/".equals(httpRootPath)) {
            applyIfMissing(context, values, "pipestream.registration.http.base-path", httpRootPath);
        }

        String healthRootPath = getOptional(context, "quarkus.smallrye-health.root-path").orElse("");
        String healthPath;
        if (!healthRootPath.isBlank()) {
            String normalizedHealthRoot = normalizePath(healthRootPath);
            // If explicitly set to the bare "/health", prefer the standard /q/health under the root path.
            if ("/health".equals(normalizedHealthRoot)) {
                healthPath = joinPaths(httpRootPath, "/q/health");
            } else {
                // Respect explicit health root as-is (Quarkus already applies root-path when appropriate)
                healthPath = normalizedHealthRoot;
            }
        } else if (!httpRootPath.isBlank() && !"/".equals(httpRootPath)) {
            healthPath = joinPaths(httpRootPath, "/q/health");
        } else {
            healthPath = "/q/health";
        }
        applyIfMissing(context, values, "pipestream.registration.http.health-path", healthPath);

        if (isHttpRegistrationEnabled(context)) {
            String scheme = getOptional(context, "pipestream.registration.http.scheme").orElse("http");
            if (hostDefaults.internalHost() != null) {
                String healthUrl = String.format("%s://%s:%s%s",
                        scheme, hostDefaults.internalHost(),
                        resolveHttpPort(context),
                        healthPath);
                applyIfMissing(context, values, "pipestream.registration.http.health-url", healthUrl);
            }
        }

        return Collections.unmodifiableMap(values);
    }

    private HostDefaults resolveHosts(ConfigSourceContext context) {
        Optional<String> explicitAdvertised = firstNonBlank(
                getOptional(context, "SERVICE_REGISTRATION_ADVERTISED_HOST"),
                getOptional(context, "pipestream.server.advertised-host"));
        Optional<String> explicitInternal = firstNonBlank(
                getOptional(context, "SERVICE_REGISTRATION_INTERNAL_HOST"),
                getOptional(context, "pipestream.server.internal-host"));

        String mode = getOptional(context, "pipestream.server.host-mode").orElse("auto").trim().toLowerCase();

        String derivedAdvertised = null;
        String derivedInternal = null;
        if (!"custom".equals(mode)) {
            String defaultHost = resolveDefaultHost(mode, context);
            derivedAdvertised = defaultHost;
            derivedInternal = defaultHost;
        }

        if ("custom".equals(mode) && (explicitAdvertised.isEmpty() || explicitInternal.isEmpty())) {
            LOG.warn("pipestream.server.host-mode=custom requires both advertised-host and internal-host to be set.");
        }

        String advertisedHost = explicitAdvertised.orElse(derivedAdvertised);
        String internalHost = explicitInternal.orElse(derivedInternal);

        return new HostDefaults(advertisedHost, internalHost);
    }

    private String resolveDefaultHost(String mode, ConfigSourceContext context) {
        boolean prod = isProdProfile(context);

        if (prod) {
            String hostname = resolveHostname(context);
            if (hostname != null) {
                return hostname;
            }
        }

        String resolvedMode = mode;
        if ("auto".equals(mode)) {
            resolvedMode = detectOsMode();
        }

        return switch (resolvedMode) {
            case "mac", "windows" -> DEFAULT_DOCKER_HOST;
            case "linux" -> DEFAULT_LINUX_HOST;
            default -> DEFAULT_LINUX_HOST;
        };
    }

    private boolean isProdProfile(ConfigSourceContext context) {
        String profile = getOptional(context, "quarkus.profile").orElse("");
        String pipelineEnv = getOptional(context, "PIPELINE_ENV").orElse("");
        return "prod".equalsIgnoreCase(profile)
                || "production".equalsIgnoreCase(profile)
                || "prod".equalsIgnoreCase(pipelineEnv)
                || "production".equalsIgnoreCase(pipelineEnv);
    }

    private String resolveHostname(ConfigSourceContext context) {
        String envHostname = getOptional(context, "HOSTNAME").orElse("");
        if (!envHostname.isBlank()) {
            return envHostname;
        }
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            LOG.debug("Unable to resolve hostname for prod defaults", e);
            return null;
        }
    }

    private String detectOsMode() {
        String osName = System.getProperty("os.name", "").toLowerCase();
        if (osName.contains("mac")) {
            return "mac";
        }
        if (osName.contains("win")) {
            return "windows";
        }
        return "linux";
    }

    private Integer resolveRegistrationPort(ConfigSourceContext context) {
        boolean separateServer = getOptional(context, "quarkus.grpc.server.use-separate-server")
                .map(Boolean::parseBoolean)
                .orElse(true);
        if (separateServer) {
            return resolveGrpcPort(context);
        }
        return resolveHttpPort(context);
    }

    private int resolveGrpcPort(ConfigSourceContext context) {
        return getOptional(context, "quarkus.grpc.server.port")
                .map(Integer::parseInt)
                .orElse(9000);
    }

    private int resolveHttpPort(ConfigSourceContext context) {
        return getOptional(context, "quarkus.http.port")
                .map(Integer::parseInt)
                .orElse(8080);
    }

    private boolean isHttpRegistrationEnabled(ConfigSourceContext context) {
        return getOptional(context, "pipestream.registration.http.enabled")
                .map(Boolean::parseBoolean)
                .orElse(true);
    }

    private void applyIfMissing(ConfigSourceContext context, Map<String, String> values,
                                String key, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        if (getOptional(context, key).isEmpty()) {
            values.put(key, value);
        }
    }

    private void applyIfMissingAllowingDefaults(ConfigSourceContext context, Map<String, String> values,
                                                String key, String value, String defaultValue) {
        if (value == null || value.isBlank()) {
            return;
        }
        Optional<String> existing = getOptional(context, key);
        if (existing.isEmpty() || defaultValue.equals(existing.get())) {
            values.put(key, value);
        }
    }

    private Optional<String> getOptional(ConfigSourceContext context, String key) {
        ConfigValue value = context.getValue(key);
        if (value == null || value.getValue() == null) {
            return Optional.empty();
        }
        String raw = value.getValue().trim();
        return raw.isEmpty() ? Optional.empty() : Optional.of(raw);
    }

    private Optional<String> firstNonBlank(Optional<String> first, Optional<String> second) {
        return first.isPresent() ? first : second;
    }

    private String normalizePath(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            return "";
        }
        String trimmed = rawPath.trim();
        if (!trimmed.startsWith("/")) {
            trimmed = "/" + trimmed;
        }
        if (trimmed.length() > 1 && trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private String joinPaths(String basePath, String healthPath) {
        String base = normalizePath(basePath);
        String health = normalizePath(healthPath);
        if (base.isBlank()) {
            return health;
        }
        if (health.isBlank() || "/".equals(health)) {
            return base;
        }
        if (health.startsWith(base)) {
            return health;
        }
        return base + health;
    }

    private record HostDefaults(String advertisedHost, String internalHost) {
    }
}
