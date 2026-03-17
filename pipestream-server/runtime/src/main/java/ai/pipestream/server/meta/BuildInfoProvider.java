package ai.pipestream.server.meta;

import io.quarkus.info.BuildInfo;
import io.quarkus.info.GitInfo;
import io.quarkus.runtime.Quarkus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.LinkedHashMap;
import java.util.Map;

@ApplicationScoped
public class BuildInfoProvider {

    private static final String UNKNOWN = "unknown";

    @Inject
    Instance<BuildInfo> buildInfo;

    @Inject
    Instance<GitInfo> gitInfo;

    @ConfigProperty(name = "quarkus.profile", defaultValue = "prod")
    String profile;

    public Map<String, Object> endpointPayload() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("build", Map.of(
                "version", buildVersion(),
                "commit", gitCommit(),
                "branch", gitBranch(),
                "time", buildTime()
        ));
        result.put("runtime", Map.of(
                "java", System.getProperty("java.version", UNKNOWN),
                "quarkus", quarkusVersion(),
                "profile", profile,
                "hostname", System.getenv().getOrDefault("HOSTNAME", UNKNOWN)
        ));
        return result;
    }

    public Map<String, String> registrationMetadata() {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("build.version", buildVersion());
        metadata.put("build.commit", gitCommit());
        metadata.put("build.branch", gitBranch());
        metadata.put("build.time", buildTime());
        metadata.put("runtime.java", System.getProperty("java.version", UNKNOWN));
        metadata.put("runtime.quarkus", quarkusVersion());
        metadata.put("runtime.profile", profile);
        metadata.put("runtime.hostname", System.getenv().getOrDefault("HOSTNAME", UNKNOWN));
        return metadata;
    }

    public String getVersion() {
        return buildVersion();
    }

    private String buildVersion() {
        if (buildInfo.isResolvable() && buildInfo.get().version() != null) {
            return buildInfo.get().version();
        }
        return UNKNOWN;
    }

    private String buildTime() {
        if (buildInfo.isResolvable() && buildInfo.get().time() != null) {
            return buildInfo.get().time().toString();
        }
        return UNKNOWN;
    }

    private String gitCommit() {
        if (gitInfo.isResolvable() && gitInfo.get().latestCommitId() != null) {
            return gitInfo.get().latestCommitId();
        }
        return UNKNOWN;
    }

    private String gitBranch() {
        if (gitInfo.isResolvable() && gitInfo.get().branch() != null) {
            return gitInfo.get().branch();
        }
        return UNKNOWN;
    }

    private String quarkusVersion() {
        if (buildInfo.isResolvable() && buildInfo.get().quarkusVersion() != null) {
            return buildInfo.get().quarkusVersion();
        }
        Package quarkusPackage = Quarkus.class.getPackage();
        if (quarkusPackage != null && quarkusPackage.getImplementationVersion() != null) {
            return quarkusPackage.getImplementationVersion();
        }
        return UNKNOWN;
    }
}
