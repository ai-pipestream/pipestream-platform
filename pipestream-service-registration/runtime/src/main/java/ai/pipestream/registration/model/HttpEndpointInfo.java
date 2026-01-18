package ai.pipestream.registration.model;

import java.util.Objects;

/**
 * HTTP endpoint information for registration.
 */
public final class HttpEndpointInfo {

    private final String scheme;
    private final String host;
    private final int port;
    private final String basePath;
    private final String healthPath;
    private final boolean tlsEnabled;

    public HttpEndpointInfo(String scheme, String host, int port, String basePath, String healthPath, boolean tlsEnabled) {
        this.scheme = Objects.requireNonNull(scheme, "scheme is required");
        this.host = Objects.requireNonNull(host, "host is required");
        this.port = port;
        this.basePath = basePath != null ? basePath : "";
        this.healthPath = healthPath != null ? healthPath : "";
        this.tlsEnabled = tlsEnabled;
    }

    public String getScheme() {
        return scheme;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public String getBasePath() {
        return basePath;
    }

    public String getHealthPath() {
        return healthPath;
    }

    public boolean isTlsEnabled() {
        return tlsEnabled;
    }

    @Override
    public String toString() {
        return "HttpEndpointInfo{" +
            "scheme='" + scheme + '\'' +
            ", host='" + host + '\'' +
            ", port=" + port +
            ", basePath='" + basePath + '\'' +
            ", healthPath='" + healthPath + '\'' +
            ", tlsEnabled=" + tlsEnabled +
            '}';
    }
}
