package ai.pipestream.registration.model;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * Service information model for registration.
 */
public final class ServiceInfo {

    private final String serviceName;
    private final String version;
    private final String host;
    private final int port;
    private final Map<String, String> metadata;

    private ServiceInfo(Builder builder) {
        this.serviceName = Objects.requireNonNull(builder.serviceName, "serviceName is required");
        this.version = builder.version;
        this.host = Objects.requireNonNull(builder.host, "host is required");
        this.port = builder.port;
        this.metadata = builder.metadata != null ? Map.copyOf(builder.metadata) : Collections.emptyMap();
    }

    public String getServiceName() {
        return serviceName;
    }

    public String getVersion() {
        return version;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public Map<String, String> getMetadata() {
        return metadata;
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public String toString() {
        return "ServiceInfo{" +
                "serviceName='" + serviceName + '\'' +
                ", version='" + version + '\'' +
                ", host='" + host + '\'' +
                ", port=" + port +
                ", metadata=" + metadata +
                '}';
    }

    public static final class Builder {
        private String serviceName;
        private String version;
        private String host;
        private int port;
        private Map<String, String> metadata;

        private Builder() {
        }

        public Builder serviceName(String serviceName) {
            this.serviceName = serviceName;
            return this;
        }

        public Builder version(String version) {
            this.version = version;
            return this;
        }

        public Builder host(String host) {
            this.host = host;
            return this;
        }

        public Builder port(int port) {
            this.port = port;
            return this;
        }

        public Builder metadata(Map<String, String> metadata) {
            this.metadata = metadata;
            return this;
        }

        public ServiceInfo build() {
            return new ServiceInfo(this);
        }
    }
}
