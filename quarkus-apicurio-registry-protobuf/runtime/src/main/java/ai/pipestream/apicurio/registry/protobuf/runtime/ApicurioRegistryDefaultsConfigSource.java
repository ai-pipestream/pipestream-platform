package ai.pipestream.apicurio.registry.protobuf.runtime;

import org.eclipse.microprofile.config.spi.ConfigSource;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Provides a safe default for apicurio.registry.url so applications don't need
 * to set it manually when using the Apicurio protobuf extension. This runs with
 * a lower ordinal than application.properties, so explicit values or Dev
 * Services values still win.
 */
public class ApicurioRegistryDefaultsConfigSource implements ConfigSource {

    private static final int ORDINAL = 180; // below application.properties (250)
    private final Map<String, String> defaults;

    public ApicurioRegistryDefaultsConfigSource() {
        Map<String, String> map = new HashMap<>();
        String envUrl = System.getenv("APICURIO_REGISTRY_URL");
        if (envUrl != null && !envUrl.isBlank()) {
            map.put("apicurio.registry.url", envUrl.trim());
        } else {
            map.put("apicurio.registry.url", "http://localhost:8081/apis/registry/v3");
        }
        this.defaults = Collections.unmodifiableMap(map);
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
