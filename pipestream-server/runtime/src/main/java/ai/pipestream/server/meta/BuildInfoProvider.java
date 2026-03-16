package ai.pipestream.server.meta;

import io.quarkus.runtime.Quarkus;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.LinkedHashMap;
import java.util.Map;

@ApplicationScoped
public class BuildInfoProvider {

    @ConfigProperty(name = "quarkus.application.name", defaultValue = "unknown")
    String serviceName;

    @ConfigProperty(name = "quarkus.application.version", defaultValue = "unknown")
    String serviceVersion;

    @ConfigProperty(name = "quarkus.profile", defaultValue = "prod")
    String profile;

    @ConfigProperty(name = "pipestream.build.commit", defaultValue = "unknown")
    String buildCommit;

    @ConfigProperty(name = "pipestream.build.branch", defaultValue = "unknown")
    String buildBranch;

    @ConfigProperty(name = "pipestream.build.time", defaultValue = "unknown")
    String buildTime;

    public Map<String, Object> endpointPayload() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("build", Map.of(
                "version", serviceVersion,
                "commit", buildCommit,
                "branch", buildBranch,
                "time", buildTime
        ));
        result.put("runtime", Map.of(
                "java", System.getProperty("java.version", "unknown"),
                "quarkus", quarkusVersion(),
                "profile", profile,
                "hostname", System.getenv().getOrDefault("HOSTNAME", "unknown")
        ));
        return result;
    }

    public Map<String, String> registrationMetadata() {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("build.version", serviceVersion);
        metadata.put("build.commit", buildCommit);
        metadata.put("build.branch", buildBranch);
        metadata.put("build.time", buildTime);
        metadata.put("runtime.java", System.getProperty("java.version", "unknown"));
        metadata.put("runtime.quarkus", quarkusVersion());
        metadata.put("runtime.profile", profile);
        metadata.put("runtime.hostname", System.getenv().getOrDefault("HOSTNAME", "unknown"));
        return metadata;
    }

    private String quarkusVersion() {
        Package quarkusPackage = Quarkus.class.getPackage();
        if (quarkusPackage != null && quarkusPackage.getImplementationVersion() != null) {
            return quarkusPackage.getImplementationVersion();
        }
        return "unknown";
    }
}
