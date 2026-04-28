package ai.pipestream.server.config;

import io.smallrye.config.ConfigSourceContext;
import io.smallrye.config.ConfigValue;
import org.eclipse.microprofile.config.spi.ConfigSource;
import org.jboss.logging.Logger;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Provides default configuration properties for Pipestream services.
 * <p>
 * This {@link ConfigSource} has a low ordinal (100) so that it can be overridden
 * by {@code application.properties}, environment variables, or other configuration sources.
 * It centralizes platform-wide defaults for gRPC, HTTP, and registration.
 */
public class PipestreamServerDefaultsConfigSource implements ConfigSource {

    private static final Logger LOG = Logger.getLogger(PipestreamServerDefaultsConfigSource.class);
    // Use lower than application.properties (250) so it acts as a true default.
    private static final int ORDINAL = 100;
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
        applyIfMissing(context, values, "pipestream.registration.registration-service.discovery-name", "platform-registration");
        logDiscoveryDefaults(values.get("pipestream.registration.registration-service.discovery-name"));
        applyIfMissing(context, values, "pipestream.registration.required", "true");
        applyIfMissing(context, values, "%dev.pipestream.registration.required", "false");
        applyIfMissing(context, values, "%test.pipestream.registration.required", "false");

        // Bind to all interfaces so Consul (in Docker) can reach the host service via 172.17.0.1
        applyIfMissing(context, values, "quarkus.http.host", "0.0.0.0");

        // Default to the SEPARATE Netty gRPC server, not the unified Vert.x one.
        //
        // The unified Vert.x gRPC server on Quarkus 3.34 has known throughput
        // pathologies for large-payload + concurrent traffic (quarkusio/quarkus#51129):
        // quarkus.http.initial-window-size does not propagate to per-stream
        // HTTP/2 windows on the unified path, so any payload >64KB stop-and-waits.
        // We measured ~10–15× slower than separate Netty for 5–50MB payloads, with
        // concurrent throughput WORSE than sequential. See LargePayloadConcurrencyTest
        // in quarkus-dynamic-grpc/integration-tests for the reproducer.
        //
        // Services that explicitly want unified mode (no use case in the platform
        // today, but kept overridable) can still set use-separate-server=false in
        // their own application.properties.
        applyIfMissing(context, values, "quarkus.grpc.server.use-separate-server", "true");
        // gRPC defaults: health + reflection + large messages
        applyIfMissing(context, values, "quarkus.grpc.server.health.enabled", "true");
        applyIfMissing(context, values, "quarkus.grpc.server.grpc-health.enabled", "true");
        applyIfMissing(context, values, "quarkus.grpc.server.enable-reflection-service", "true");
        applyIfMissing(context, values, "quarkus.grpc.server.max-inbound-message-size", "2147483647");
        // Match the production-proven connector-intake-service config: outbound
        // limit lifted in lockstep with inbound, and HTTP/2 flow-control window
        // bumped to 100MB so a 5–50MB PipeDoc streams in one shot rather than
        // stop-and-waiting every 64KB. Without this default a typical PipeDoc
        // upload is bandwidth-bottlenecked at ~7.5MB/s on localhost.
        applyIfMissing(context, values, "quarkus.grpc.server.max-outbound-message-size", "2147483647");
        applyIfMissing(context, values, "quarkus.grpc.server.flow-control-window", "104857600");

        // HTTP/2 keep-alive ping permit window. Without this, every gRPC server
        // in the platform inherits gRPC's default permit-keep-alive-time of
        // 5 MINUTES, while our gRPC clients (both stock @GrpcClient and the
        // dynamic-grpc ChannelManager) ping every 30 seconds. Mismatch causes
        // the server to send GOAWAY with HTTP/2 ENHANCE_YOUR_CALM after the
        // 2nd ping (~60 s in), tearing down the connection and any in-flight
        // RPC. We saw this as exactly-one-doc loss in 1000-doc transport
        // tests: the doc whose dispatch landed on the GOAWAY connection died
        // with RESOURCE_EXHAUSTED ("too_many_pings"). 20s gives a safety
        // margin under the 30s client interval.
        applyIfMissing(context, values, "quarkus.grpc.server.netty.permit-keep-alive-time", "20s");

        // HTTP/2 initial window size for the Vert.x HTTP server (REST
        // endpoints). Distinct from quarkus.grpc.server.flow-control-window
        // (which targets the separate Netty gRPC server). intake exposes
        // multi-GB HTTP uploads that flow into repository; without bumping
        // the HTTP/2 window from the 64KB spec default, those uploads
        // stop-and-wait on every WINDOW_UPDATE round-trip. 100MB matches
        // the gRPC-side window for symmetry.
        applyIfMissing(context, values, "quarkus.http.initial-window-size", "104857600");

        // Per-service gRPC port = HTTP port + 10000.
        //
        // With the unified Vert.x gRPC server (the prior platform default,
        // use-separate-server=false), gRPC piggy-backed on the HTTP port —
        // every service was already unique because each service has a unique
        // HTTP port. Flipping to use-separate-server=true (the migration to
        // grpc-netty) means the gRPC server needs its own port, and Quarkus's
        // built-in default (9000) collides whenever more than one service
        // runs on the same host (process-compose, dev mode, test rigs,
        // CI runners).
        //
        // Convention: gRPC = HTTP + 10000. Easy to reason about (HTTP=18105
        // → gRPC=28105), preserves the per-service uniqueness the unified
        // mode gave us for free, and leaves the 10000-port jump big enough
        // that there's no overlap with the HTTP range. Services that need
        // a different port can still set quarkus.grpc.server.port directly.
        int httpPortForGrpcDerivation = resolveHttpPort(context);
        if (httpPortForGrpcDerivation > 0 && httpPortForGrpcDerivation <= 55535) {
            String derivedGrpcPort = String.valueOf(httpPortForGrpcDerivation + 10000);
            applyIfMissing(context, values, "quarkus.grpc.server.port", derivedGrpcPort);
        }

        // OpenAPI defaults
        applyIfMissing(context, values, "quarkus.swagger-ui.always-include", "true");
        applyIfMissing(context, values, "quarkus.smallrye-openapi.info-title", resolveOpenApiTitle(context));
        applyIfMissing(context, values, "quarkus.smallrye-openapi.info-version", resolveOpenApiVersion(context));
        applyIfMissing(context, values, "quarkus.smallrye-openapi.info-description", resolveOpenApiDescription(context));

        // OpenAPI/Swagger/Health paths: use relative values (no leading slash) so they inherit root-path.
        // When root-path is set (e.g. /modules/parser), /q/openapi, /q/swagger-ui, /q/health nest under it.
        // See https://quarkus.io/blog/path-resolution-in-quarkus/
        applyIfMissing(context, values, "quarkus.http.non-application-root-path", "q");
        applyIfMissing(context, values, "quarkus.smallrye-openapi.path", "openapi");
        applyIfMissing(context, values, "quarkus.swagger-ui.path", "swagger-ui");

        // Security: enable admin fallback in dev mode (no x-account-id header → admin)
        applyIfMissing(context, values, "%dev.pipestream.security.admin-fallback-enabled", "true");
        applyIfMissing(context, values, "%test.pipestream.security.admin-fallback-enabled", "true");

        // Testcontainers: disable container reuse in test mode.
        // When reuse is enabled, Testcontainers skips starting Ryuk (the cleanup daemon),
        // but Quarkus DevServices creates a new container per test run (unique process-uuid).
        // This causes containers to accumulate indefinitely. Force reuse off so Ryuk cleans up.
        if (isTestProfile(context)) {
            LOG.info("Test mode active for Pipestream server. Setting testcontainers.reuse.enable=false "
                    + "to prevent container leaks from Quarkus DevServices.");
            System.setProperty("testcontainers.reuse.enable", "false");
        }

        // Dev profile: compose devservices defaults (shared infra)
        applyIfMissing(context, values, "%dev.quarkus.devservices.enabled", "true");
        applyIfMissing(context, values, "%dev.quarkus.compose.devservices.enabled", "true");
        applyIfMissing(context, values, "%dev.quarkus.compose.devservices.files", "${user.home}/.pipeline/compose-devservices.yml");
        applyIfMissing(context, values, "%dev.quarkus.compose.devservices.project-name", "pipeline-shared-devservices");
        applyIfMissing(context, values, "%dev.quarkus.compose.devservices.start-services", "true");
        applyIfMissing(context, values, "%dev.quarkus.compose.devservices.stop-services", "false");
        applyIfMissing(context, values, "%dev.quarkus.compose.devservices.reuse-project-for-tests", "true");
        applyIfMissing(context, values, "%dev.quarkus.devservices.timeout", "120s");
        applyIfMissing(context, values, "%dev.quarkus.compose.devservices.stop-timeout", "30s");
        // Allow datasource devservices so Quarkus can pick up JDBC from compose mapping
        applyIfMissing(context, values, "%dev.quarkus.datasource.devservices.enabled", "true");
        logDevServicesDefaults(values);

        Integer registrationPort = resolveRegistrationPort(context);
        if (registrationPort != null && registrationPort > 0) {
            applyIfMissingAllowingDefaults(context, values, "pipestream.registration.advertised-port",
                    String.valueOf(registrationPort), "8080");
            applyIfMissingAllowingDefaults(context, values, "pipestream.registration.internal-port",
                    String.valueOf(registrationPort), "8080");
            // In dev, prefer the runtime HTTP/grpc port even if a baked default exists
            applyIfMissing(context, values, "%dev.pipestream.registration.advertised-port",
                    String.valueOf(registrationPort));
            applyIfMissing(context, values, "%dev.pipestream.registration.internal-port",
                    String.valueOf(registrationPort));
            logRegistrationDefaults(registrationPort, values);
        }

        String httpRootPath = normalizePath(getOptional(context, "quarkus.http.root-path").orElse(""));
        if (!httpRootPath.isBlank() && !"/".equals(httpRootPath)) {
            applyIfMissing(context, values, "pipestream.registration.http.base-path", httpRootPath);
            applyIfMissing(context, values, "quarkus.smallrye-openapi.servers", httpRootPath);
        }

        String healthRootPathRaw = getOptional(context, "quarkus.smallrye-health.root-path").orElse("health");
        String healthBasePath = resolveHealthBasePath(context, httpRootPath);
        String healthPath;
        if (!healthRootPathRaw.isBlank() && healthRootPathRaw.startsWith("/")) {
            healthPath = normalizePath(healthRootPathRaw);
        } else {
            String normalizedHealthRoot = normalizePath(healthRootPathRaw);
            healthPath = joinPaths(healthBasePath, normalizedHealthRoot);
        }
        if (isRegistrationRequired(context)) {
            healthPath = toLivenessPath(healthPath);
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

    private String resolveOpenApiTitle(ConfigSourceContext context) {
        return firstNonBlank(
                getOptional(context, "quarkus.application.name"),
                getOptional(context, "pipestream.registration.service-name"))
                .orElse(null);
    }

    private String resolveOpenApiVersion(ConfigSourceContext context) {
        return firstNonBlank(
                getOptional(context, "quarkus.application.version"),
                getOptional(context, "pipestream.registration.version"))
                .orElse(null);
    }

    private String resolveOpenApiDescription(ConfigSourceContext context) {
        return getOptional(context, "pipestream.registration.description").orElse(null);
    }

    private String resolveDefaultHost(String mode, ConfigSourceContext context) {
        boolean prod = isProdProfile(context);

        if (prod) {
            String hostname = resolveHostname(context);
            if (hostname != null) {
                // If we are in a CI/CD runner, default to localhost
                if (hostname.toLowerCase().contains("runner")) {
                    return "localhost";
                }
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

    private boolean isTestProfile(ConfigSourceContext context) {
        String profile = getOptional(context, "quarkus.profile").orElse("");
        return "test".equalsIgnoreCase(profile);
    }

    private String resolveHostname(ConfigSourceContext context) {
        // Only use HOSTNAME env var (always set in Docker/K8s).
        // Do NOT use InetAddress.getLocalHost() — this ConfigSource runs at build time
        // during Quarkus augmentation, so it would bake the CI runner hostname into the
        // artifact instead of resolving at runtime.
        String envHostname = getOptional(context, "HOSTNAME").orElse("");
        if (!envHostname.isBlank()) {
            return envHostname;
        }
        return null;
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

    /**
     * IMPORTANT: this is called from {@code buildDefaults()} BEFORE the
     * {@code values} map (which holds {@code use-separate-server=true}) has
     * been published as a config source. Reading via {@code getOptional}
     * here only sees OTHER sources, not our pending defaults. Defaulting
     * to the same value we just wrote ({@code true}) keeps the registration
     * port consistent with the gRPC server we'll actually start. If a
     * caller has explicitly set {@code use-separate-server=false} in their
     * own properties, that wins and we register on the HTTP port.
     */
    private Integer resolveRegistrationPort(ConfigSourceContext context) {
        boolean separateServer = resolveSeparateServerDefault(context);
        if (separateServer) {
            return resolveGrpcPort(context);
        }
        return resolveHttpPort(context);
    }

    private int resolveGrpcPort(ConfigSourceContext context) {
        boolean separateServer = resolveSeparateServerDefault(context);
        if (!separateServer) {
            return resolveHttpPort(context);
        }
        Optional<Integer> explicit = firstInt(
                getOptional(context, "quarkus.grpc.server.test-port"),
                getOptional(context, "quarkus.grpc.server.port"));
        if (explicit.isPresent()) {
            return explicit.get();
        }
        // Mirror the HTTP+10000 default applied in buildDefaults so that
        // service registration computes the same gRPC port the server will
        // actually bind to. Falls back to Quarkus's 9000 default only when
        // we can't resolve an HTTP port either (e.g. running with a fully
        // dynamic port assignment).
        int httpPort = resolveHttpPort(context);
        if (httpPort > 0 && httpPort <= 55535) {
            return httpPort + 10000;
        }
        return 9000;
    }

    /**
     * Reads {@code quarkus.grpc.server.use-separate-server} from upstream
     * sources, falling back to the platform default ({@code true}) so build-
     * time consumers in this same source see the value we publish below.
     * Keep the fallback here in lockstep with the {@code applyIfMissing}
     * call in {@link #buildDefaults}.
     */
    private boolean resolveSeparateServerDefault(ConfigSourceContext context) {
        return getOptional(context, "quarkus.grpc.server.use-separate-server")
                .map(Boolean::parseBoolean)
                .orElse(true);
    }

    private int resolveHttpPort(ConfigSourceContext context) {
        // Skip values <= 0 when looking for a usable HTTP port.
        // quarkus.http.test-port=0 is Quarkus's idiom for "pick a random
        // ephemeral port at test time" — when it's set unconditionally
        // (not under %test) it would otherwise short-circuit gRPC port
        // derivation in dev/prod and fall back to Quarkus's default 9000.
        // Same treatment for any property that resolves to 0 or negative.
        return firstPositiveInt(
                getOptional(context, "quarkus.http.test-port"),
                getOptional(context, "quarkus.http.port"))
                .orElse(0); // Use 0 to indicate unset/dynamic rather than 8080
    }

    private Optional<Integer> firstInt(Optional<String> first, Optional<String> second) {
        if (first.isPresent()) {
            try {
                return Optional.of(Integer.parseInt(first.get()));
            } catch (NumberFormatException ignored) {}
        }
        if (second.isPresent()) {
            try {
                return Optional.of(Integer.parseInt(second.get()));
            } catch (NumberFormatException ignored) {}
        }
        return Optional.empty();
    }

    private Optional<Integer> firstPositiveInt(Optional<String> first, Optional<String> second) {
        if (first.isPresent()) {
            try {
                int parsed = Integer.parseInt(first.get());
                if (parsed > 0) return Optional.of(parsed);
            } catch (NumberFormatException ignored) {}
        }
        if (second.isPresent()) {
            try {
                int parsed = Integer.parseInt(second.get());
                if (parsed > 0) return Optional.of(parsed);
            } catch (NumberFormatException ignored) {}
        }
        return Optional.empty();
    }

    private String resolveHealthBasePath(ConfigSourceContext context, String httpRootPath) {
        boolean managementEnabled = getOptional(context, "quarkus.management.enabled")
                .map(Boolean::parseBoolean)
                .orElse(false);
        if (managementEnabled) {
            String managementRoot = getOptional(context, "quarkus.management.root-path").orElse("/q");
            return normalizePath(managementRoot);
        }

        String nonAppRootRaw = getOptional(context, "quarkus.http.non-application-root-path").orElse("q");
        if (nonAppRootRaw.isBlank()) {
            return "";
        }
        if (nonAppRootRaw.startsWith("/")) {
            return normalizePath(nonAppRootRaw);
        }
        String normalizedNonApp = normalizePath(nonAppRootRaw);
        if (httpRootPath == null || httpRootPath.isBlank() || "/".equals(httpRootPath)) {
            return normalizedNonApp;
        }
        return joinPaths(httpRootPath, normalizedNonApp);
    }

    private boolean isRegistrationRequired(ConfigSourceContext context) {
        Optional<String> required = getOptional(context, "pipestream.registration.required");
        return required.map(Boolean::parseBoolean).orElseGet(() -> !isTestProfile(context));
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

    private String toLivenessPath(String healthPath) {
        String normalized = normalizePath(healthPath);
        if (normalized.endsWith("/live")) {
            return normalized;
        }
        if (normalized.endsWith("/health")) {
            return normalized + "/live";
        }
        if (normalized.endsWith("/ready")) {
            return normalized.replace("/ready", "/live");
        }
        return normalized + "/live";
    }

    private record HostDefaults(String advertisedHost, String internalHost) {
    }

    private void logDevServicesDefaults(Map<String, String> values) {
        if (!LOG.isInfoEnabled()) {
            return;
        }
        String composeFile = values.get("%dev.quarkus.compose.devservices.files");
        String project = values.get("%dev.quarkus.compose.devservices.project-name");
        String start = values.get("%dev.quarkus.compose.devservices.start-services");
        String stop = values.get("%dev.quarkus.compose.devservices.stop-services");
        String reuse = values.get("%dev.quarkus.compose.devservices.reuse-project-for-tests");
        String datasourceDevservices = values.get("%dev.quarkus.datasource.devservices.enabled");
        if (composeFile != null || project != null) {
            LOG.infof("DevServices defaults applied: compose-file=%s project=%s start=%s stop=%s reuse=%s datasource-devservices=%s",
                    composeFile, project, start, stop, reuse, datasourceDevservices);
        }
    }

    private void logRegistrationDefaults(Integer registrationPort, Map<String, String> values) {
        if (!LOG.isInfoEnabled() || registrationPort == null) {
            return;
        }
        String advertised = values.getOrDefault("%dev.pipestream.registration.advertised-port",
                values.get("pipestream.registration.advertised-port"));
        String internal = values.getOrDefault("%dev.pipestream.registration.internal-port",
                values.get("pipestream.registration.internal-port"));
        LOG.infof("Registration defaults applied: resolved-port=%s advertised-port=%s internal-port=%s",
                registrationPort, advertised, internal);
    }

    private void logDiscoveryDefaults(String discoveryName) {
        if (LOG.isInfoEnabled() && discoveryName != null) {
            LOG.infof("Registration discovery default applied: discovery-name=%s", discoveryName);
        }
    }
}
