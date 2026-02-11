package ai.pipestream.apicurio.registry.protobuf.runtime;

import org.eclipse.microprofile.config.spi.ConfigSource;

import java.util.Map;
import java.util.Set;

/**
 * Bridges the APICURIO_REGISTRY_URL environment variable into the
 * {@code apicurio.registry.url} config property.  Only emits a value when
 * the env var is explicitly set (e.g. in production / docker-compose).
 *
 * <p>When no env var is present the map is empty, which lets Quarkus
 * DevServices detect that no registry URL is configured and start its own
 * Apicurio container automatically — essential for CI and bare test runs.
 */
public class ApicurioRegistryDefaultsConfigSource implements ConfigSource {

    private static final int ORDINAL = 180; // below application.properties (250)
    private final Map<String, String> defaults;

    public ApicurioRegistryDefaultsConfigSource() {
        String envUrl = System.getenv("APICURIO_REGISTRY_URL");
        if (envUrl != null && !envUrl.isBlank()) {
            this.defaults = Map.of("apicurio.registry.url", envUrl.trim());
        } else {
            this.defaults = Map.of();
        }
    }

    @Override
    public Map<String, String> getProperties() {
        return defaults;
    }

    @Override
    public Set<String> getPropertyNames() {
        return defaults.keySet();
    }

    @Override
    public String getValue(String propertyName) {
        return defaults.get(propertyName);
    }

    @Override
    public String getName() {
        return "apicurio-registry-defaults";
    }

    @Override
    public int getOrdinal() {
        return ORDINAL;
    }
}
