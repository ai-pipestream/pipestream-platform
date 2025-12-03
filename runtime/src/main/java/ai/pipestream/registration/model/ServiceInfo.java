package ai.pipestream.registration.model;

import ai.pipestream.platform.registration.v1.ServiceType;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Service information model for registration.
 */
public final class ServiceInfo {

    private final String name;
    private final ServiceType type;
    private final String version;
    private final String advertisedHost;
    private final int advertisedPort;
    private final String internalHost;
    private final Integer internalPort;
    private final boolean tlsEnabled;
    private final Map<String, String> metadata;
    private final List<String> tags;
    private final List<String> capabilities;

    private ServiceInfo(Builder builder) {
        this.name = Objects.requireNonNull(builder.name, "name is required");
        this.type = Objects.requireNonNull(builder.type, "type is required");
        this.version = builder.version;
        this.advertisedHost = Objects.requireNonNull(builder.advertisedHost, "advertisedHost is required");
        this.advertisedPort = builder.advertisedPort;
        this.internalHost = builder.internalHost;
        this.internalPort = builder.internalPort;
        this.tlsEnabled = builder.tlsEnabled;
        this.metadata = builder.metadata != null ? Map.copyOf(builder.metadata) : Collections.emptyMap();
        this.tags = builder.tags != null ? List.copyOf(builder.tags) : Collections.emptyList();
        this.capabilities = builder.capabilities != null ? List.copyOf(builder.capabilities) : Collections.emptyList();
    }

    public String getName() {
        return name;
    }

    public ServiceType getType() {
        return type;
    }

    public String getVersion() {
        return version;
    }

    public String getAdvertisedHost() {
        return advertisedHost;
    }

    public int getAdvertisedPort() {
        return advertisedPort;
    }

    public String getInternalHost() {
        return internalHost;
    }

    public Integer getInternalPort() {
        return internalPort;
    }

    public boolean isTlsEnabled() {
        return tlsEnabled;
    }

    public Map<String, String> getMetadata() {
        return metadata;
    }

    public List<String> getTags() {
        return tags;
    }

    public List<String> getCapabilities() {
        return capabilities;
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public String toString() {
        return "ServiceInfo{" +
                "name='" + name + '\'' +
                ", type=" + type +
                ", version='" + version + '\'' +
                ", advertisedHost='" + advertisedHost + '\'' +
                ", advertisedPort=" + advertisedPort +
                ", internalHost='" + internalHost + '\'' +
                ", internalPort=" + internalPort +
                ", tlsEnabled=" + tlsEnabled +
                ", metadata=" + metadata +
                ", tags=" + tags +
                ", capabilities=" + capabilities +
                '}';
    }

    public static final class Builder {
        private String name;
        private ServiceType type = ServiceType.SERVICE_TYPE_SERVICE; // Default to SERVICE
        private String version;
        private String advertisedHost;
        private int advertisedPort;
        private String internalHost;
        private Integer internalPort;
        private boolean tlsEnabled = false;
        private Map<String, String> metadata;
        private List<String> tags;
        private List<String> capabilities;

        private Builder() {
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder type(ServiceType type) {
            this.type = type;
            return this;
        }

        public Builder version(String version) {
            this.version = version;
            return this;
        }

        public Builder advertisedHost(String advertisedHost) {
            this.advertisedHost = advertisedHost;
            return this;
        }

        public Builder advertisedPort(int advertisedPort) {
            this.advertisedPort = advertisedPort;
            return this;
        }

        public Builder internalHost(String internalHost) {
            this.internalHost = internalHost;
            return this;
        }

        public Builder internalPort(Integer internalPort) {
            this.internalPort = internalPort;
            return this;
        }

        public Builder tlsEnabled(boolean tlsEnabled) {
            this.tlsEnabled = tlsEnabled;
            return this;
        }

        public Builder metadata(Map<String, String> metadata) {
            this.metadata = metadata;
            return this;
        }

        public Builder tags(List<String> tags) {
            this.tags = tags;
            return this;
        }

        public Builder capabilities(List<String> capabilities) {
            this.capabilities = capabilities;
            return this;
        }

        public ServiceInfo build() {
            return new ServiceInfo(this);
        }
    }
}
