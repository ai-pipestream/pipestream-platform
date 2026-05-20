package ai.pipestream.registration;

import ai.pipestream.platform.registration.v1.ModuleRegistration;
import ai.pipestream.platform.registration.v1.ServiceType;
import ai.pipestream.registration.config.RegistrationConfig;
import ai.pipestream.registration.model.ServiceInfo;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Builds inline {@link ModuleRegistration} metadata for {@code RegisterRequest}.
 */
@ApplicationScoped
public class ModuleRegistrationMetadataCollector {

    private static final Logger LOG = Logger.getLogger(ModuleRegistrationMetadataCollector.class);

    private final RegistrationConfig config;

    @Inject
    public ModuleRegistrationMetadataCollector(RegistrationConfig config) {
        this.config = config;
    }

    public Optional<ModuleRegistration> collect(ServiceInfo serviceInfo) {
        if (serviceInfo.getType() != ServiceType.SERVICE_TYPE_MODULE) {
            return Optional.empty();
        }

        RegistrationConfig.ModuleConfig moduleConfig = config.module();
        ModuleRegistration.Builder builder = ModuleRegistration.newBuilder()
                .setHealthCheckPassed(moduleConfig.healthCheckPassed());

        moduleConfig.healthCheckMessage().ifPresent(builder::setHealthCheckMessage);
        moduleConfig.displayName().ifPresent(builder::setDisplayName);
        moduleConfig.owner().ifPresent(builder::setOwner);
        moduleConfig.documentationUrl().ifPresent(builder::setDocumentationUrl);
        moduleConfig.dependencies().ifPresent(builder::addAllDependencies);
        moduleConfig.expectedMaxProcessingSeconds().ifPresent(builder::setExpectedMaxProcessingSeconds);

        String description = moduleConfig.description()
                .or(() -> config.description())
                .orElse(null);
        if (description != null && !description.isBlank()) {
            builder.setDescription(description);
        }

        resolveJsonConfigSchema(moduleConfig).ifPresent(builder::setJsonConfigSchema);
        builder.putAllModuleMetadata(serviceInfo.getMetadata());

        return Optional.of(builder.build());
    }

    private Optional<String> resolveJsonConfigSchema(RegistrationConfig.ModuleConfig moduleConfig) {
        Optional<String> inline = moduleConfig.jsonConfigSchema().filter(s -> !s.isBlank());
        if (inline.isPresent()) {
            return inline;
        }

        return moduleConfig.jsonConfigSchemaPath()
                .filter(path -> !path.isBlank())
                .map(this::loadSchemaFromPath);
    }

    private String loadSchemaFromPath(String location) {
        if (location.startsWith("classpath:")) {
            String resource = location.substring("classpath:".length());
            if (resource.startsWith("/")) {
                resource = resource.substring(1);
            }
            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            try (InputStream in = cl.getResourceAsStream(resource)) {
                if (in == null) {
                    throw new IllegalArgumentException("Classpath schema not found: " + location);
                }
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to read schema from " + location, e);
            }
        }

        try {
            return Files.readString(Path.of(location), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read schema from " + location, e);
        }
    }
}
