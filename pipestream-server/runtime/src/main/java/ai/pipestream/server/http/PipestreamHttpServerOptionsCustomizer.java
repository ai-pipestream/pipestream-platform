package ai.pipestream.server.http;

import ai.pipestream.server.config.PipestreamServerConfig;
import io.quarkus.vertx.http.HttpServerOptionsCustomizer;
import io.vertx.core.http.HttpServerOptions;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.jboss.logging.Logger;

@Singleton
public class PipestreamHttpServerOptionsCustomizer implements HttpServerOptionsCustomizer {

    private static final Logger LOG = Logger.getLogger(PipestreamHttpServerOptionsCustomizer.class);
    private static final int DEFAULT_HTTP2_WINDOW_SIZE = 104857600;

    @Inject
    PipestreamServerConfig config;

    @Override
    public void customizeHttpServer(HttpServerOptions options) {
        apply(options, "http");
    }

    @Override
    public void customizeHttpsServer(HttpServerOptions options) {
        apply(options, "https");
    }

    private void apply(HttpServerOptions options, String serverKind) {
        int windowSize = resolveHttp2WindowSize();
        if (windowSize <= 0) {
            return;
        }

        options.setHttp2ConnectionWindowSize(windowSize);
        LOG.infof("Applied HTTP/2 connection window size (%d) to %s server (class=%s, capabilities=%s)",
                windowSize, serverKind, normalizeClass(config.serverClass()), normalizeCapabilities(config.capabilities().orElse("")));
    }

    private int resolveHttp2WindowSize() {
        if (config.http2ConnectionWindowSize().isPresent()) {
            return config.http2ConnectionWindowSize().getAsInt();
        }

        if (isLargeGrpcEnabled()) {
            return DEFAULT_HTTP2_WINDOW_SIZE;
        }

        return 0;
    }

    private boolean isLargeGrpcEnabled() {
        String serviceClass = normalizeClass(config.serverClass());
        String capabilities = normalizeCapabilities(config.capabilities().orElse(""));

        if (!isKnownClass(serviceClass)) {
            LOG.warnf("Unknown pipestream.server.class '%s'; defaulting to core behavior", serviceClass);
        }

        if ("module".equals(serviceClass) || "connector".equals(serviceClass) || "engine".equals(serviceClass)) {
            return true;
        }

        return hasCapability(capabilities, "large-grpc") || hasCapability(capabilities, "pipedoc");
    }

    private boolean hasCapability(String capabilities, String target) {
        if (capabilities.isEmpty()) {
            return false;
        }
        for (String raw : capabilities.split(",")) {
            if (target.equals(raw.trim())) {
                return true;
            }
        }
        return false;
    }

    private String normalizeClass(String serviceClass) {
        if (serviceClass == null) {
            return "core";
        }
        String normalized = serviceClass.trim().toLowerCase();
        return normalized.isEmpty() ? "core" : normalized;
    }

    private String normalizeCapabilities(String capabilities) {
        if (capabilities == null) {
            return "";
        }
        return capabilities.trim().toLowerCase();
    }

    private boolean isKnownClass(String serviceClass) {
        return "core".equals(serviceClass)
                || "module".equals(serviceClass)
                || "connector".equals(serviceClass)
                || "engine".equals(serviceClass);
    }
}
