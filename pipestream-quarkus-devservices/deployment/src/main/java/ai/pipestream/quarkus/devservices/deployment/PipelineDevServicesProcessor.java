package ai.pipestream.quarkus.devservices.deployment;

import ai.pipestream.quarkus.devservices.PipelineDevServicesConfig;
import ai.pipestream.quarkus.devservices.runtime.ComposeDevServicesConfigBuilder;
import ai.pipestream.quarkus.devservices.runtime.InfisicalAdminInitializer;
import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.deployment.IsDevelopment;
import io.quarkus.deployment.IsTest;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.DevServicesResultBuildItem;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.StaticInitConfigBuilderBuildItem;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

class PipelineDevServicesProcessor {

    private static final Logger LOG = Logger.getLogger(PipelineDevServicesProcessor.class);
    private static final String FEATURE = "pipeline-devservices";
    private static final String COMPOSE_FILE_RESOURCE = "compose-devservices.yml";
    private static final String INIT_SCRIPT_RESOURCE = "init-mysql.sql";
    private static final String VERSION_FILE = ".version";
    private static final String COMPOSE_CONFIG_PREFIX = "quarkus.compose.devservices.";
    private static final String PIPELINE_CONFIG_PREFIX = "pipeline.devservices.";
    private static final boolean DEFAULT_START_SERVICES = true;
    private static final boolean DEFAULT_STOP_SERVICES = false;
    private static final boolean DEFAULT_REUSE_PROJECT_FOR_TESTS = true;

    @BuildStep(onlyIf = IsDevelopment.class)
    FeatureBuildItem feature() {
        LOG.info("========================================");
        LOG.info("Pipeline Dev Services extension - feature build step executing");
        LOG.info("========================================");
        return new FeatureBuildItem(FEATURE);
    }

    @BuildStep
    StaticInitConfigBuilderBuildItem registerConfigBuilder() {
        return new StaticInitConfigBuilderBuildItem(ComposeDevServicesConfigBuilder.class);
    }

    /**
     * Register InfisicalAdminInitializer bean only in dev mode.
     * This ensures the bean is not included in production builds.
     */
    @BuildStep(onlyIf = IsDevelopment.class)
    AdditionalBeanBuildItem registerInfisicalAdminInitializerDev() {
        LOG.debug("Registering InfisicalAdminInitializer bean (dev mode)");
        return AdditionalBeanBuildItem.builder()
                .addBeanClass(InfisicalAdminInitializer.class)
                .setUnremovable()
                .build();
    }

    /**
     * Register InfisicalAdminInitializer bean only in test mode.
     * This ensures the bean is not included in production builds.
     */
    @BuildStep(onlyIf = IsTest.class)
    AdditionalBeanBuildItem registerInfisicalAdminInitializerTest() {
        LOG.debug("Registering InfisicalAdminInitializer bean (test mode)");
        return AdditionalBeanBuildItem.builder()
                .addBeanClass(InfisicalAdminInitializer.class)
                .setUnremovable()
                .build();
    }

    @BuildStep(onlyIf = IsDevelopment.class)
    DevServicesResultBuildItem setupComposeFile(PipelineDevServicesConfig config) {
        LOG.info("========================================");
        LOG.info("Pipeline Dev Services extension - setupComposeFile build step executing");
        LOG.info("Extension enabled: " + config.enabled());
        LOG.info("========================================");
        if (!config.enabled()) {
            LOG.warn("Pipeline Dev Services extension is disabled");
            clearPipelineSystemProperties();
            Map<String, String> disabledConfig = Map.of(COMPOSE_CONFIG_PREFIX + "enabled", Boolean.FALSE.toString());
            return DevServicesResultBuildItem.discovered()
                    .name(FEATURE)
                    .description("Pipeline Dev Services disabled")
                    .config(disabledConfig)
                    .build();
        }

        try {
            String targetDir = config.targetDir();
            Path targetPath = Paths.get(targetDir);
            Path composeFile = targetPath.resolve(COMPOSE_FILE_RESOURCE);
            Path versionFile = targetPath.resolve(VERSION_FILE);

            // Create target directory if it doesn't exist
            if (!Files.exists(targetPath)) {
                Files.createDirectories(targetPath);
                LOG.info("Created target directory: " + targetPath);
            }

            // Read compose file from classpath (from runtime module resources)
            InputStream composeResource = Thread.currentThread().getContextClassLoader()
                    .getResourceAsStream(COMPOSE_FILE_RESOURCE);

            if (composeResource == null) {
                LOG.warn("Compose file not found in classpath: " + COMPOSE_FILE_RESOURCE);
                clearPipelineSystemProperties();
                return DevServicesResultBuildItem.discovered()
                        .name(FEATURE)
                        .description("Pipeline Dev Services compose resource not found")
                        .config(Map.of())
                        .build();
            }

            // Calculate SHA of the resource
            byte[] resourceBytes = composeResource.readAllBytes();
            String resourceSha = calculateSHA(resourceBytes);
            composeResource.close();

            // Check if file exists
            if (Files.exists(composeFile)) {
                // Read existing version info
                VersionInfo existingVersion = readVersionInfo(versionFile);

                if (existingVersion != null && existingVersion.sha().equals(resourceSha)) {
                    // Same version, no update needed
                    LOG.debug("Compose file is up to date");
                } else if (existingVersion != null && !existingVersion.sha().equals(resourceSha)) {
                    // Version mismatch
                    if (config.forceUpdate()) {
                        LOG.info("Force update enabled, updating compose file");
                        updateComposeFile(composeFile, versionFile, resourceBytes, resourceSha, config);
                    } else if (config.autoUpdate()) {
                        // Check if file was edited (SHA differs from stored SHA)
                        byte[] existingBytes = Files.readAllBytes(composeFile);
                        String existingSha = calculateSHA(existingBytes);

                        if (existingVersion.sha().equals(existingSha)) {
                            // File wasn't edited, safe to auto-update
                            LOG.info("Auto-updating compose file (version changed)");
                            updateComposeFile(composeFile, versionFile, resourceBytes, resourceSha, config);
                        } else {
                            // File was edited, warn and don't update
                            LOG.warn("Compose file was manually edited. Skipping auto-update. " +
                                    "Set quarkus.pipeline-devservices.force-update=true to force update.");
                        }
                    } else {
                        LOG.debug("Auto-update disabled, using existing compose file");
                    }
                } else {
                    // Version file doesn't exist, treat as new
                    LOG.info("Extracting compose file (first time)");
                    extractComposeFile(composeFile, versionFile, resourceBytes, resourceSha);
                }
            } else {
                // File doesn't exist, extract it
                LOG.info("Extracting compose file to: " + composeFile);
                extractComposeFile(composeFile, versionFile, resourceBytes, resourceSha);
            }

            // Validate YAML
            validateYaml(composeFile);

            // Extract init script if it exists
            Path initScriptFile = targetPath.resolve(INIT_SCRIPT_RESOURCE);
            try (InputStream initScriptResource = Thread.currentThread().getContextClassLoader()
                    .getResourceAsStream(INIT_SCRIPT_RESOURCE)) {
                if (initScriptResource != null) {
                    LOG.debug("Extracting init script to: " + initScriptFile);
                    byte[] initScriptBytes = initScriptResource.readAllBytes();
                    Files.write(initScriptFile, initScriptBytes);
                } else {
                    LOG.debug("Init script not found in classpath: " + INIT_SCRIPT_RESOURCE);
                }
            }

            // Set Quarkus compose devservices properties
            String composeFileAbsolutePath = composeFile.toAbsolutePath().toString();
            String projectName = config.projectName()
                    .filter(s -> !s.isEmpty())
                    .orElse("pipeline-shared-devservices");

            // Set the compose file path
            LOG.info("Pipeline Dev Services configured: " + composeFileAbsolutePath);
            LOG.info("To enable Compose Dev Services, add the following properties to your application.properties:");
            LOG.info("  %dev.quarkus.compose.devservices.enabled=true");
            LOG.info("  %dev.quarkus.compose.devservices.files=" + composeFileAbsolutePath);
            LOG.info("  %dev.quarkus.compose.devservices.project-name=" + projectName);
            LOG.info("  %dev.quarkus.compose.devservices.start-services=true");
            LOG.info("  %dev.quarkus.compose.devservices.stop-services=false");
            LOG.info("  %dev.quarkus.compose.devservices.reuse-project-for-tests=true");

            Map<String, String> composeConfig = new LinkedHashMap<>();
            composeConfig.put(COMPOSE_CONFIG_PREFIX + "enabled", Boolean.toString(true));
            composeConfig.put(COMPOSE_CONFIG_PREFIX + "files", composeFileAbsolutePath);
            composeConfig.put(COMPOSE_CONFIG_PREFIX + "project-name", projectName);
            composeConfig.put(COMPOSE_CONFIG_PREFIX + "start-services", Boolean.toString(DEFAULT_START_SERVICES));
            composeConfig.put(COMPOSE_CONFIG_PREFIX + "stop-services", Boolean.toString(DEFAULT_STOP_SERVICES));
            composeConfig.put(COMPOSE_CONFIG_PREFIX + "reuse-project-for-tests",
                    Boolean.toString(DEFAULT_REUSE_PROJECT_FOR_TESTS));

            // Add DevServices configuration for shared network
            // This enables Kafka DevServices to use internal container hostnames instead of
            // port mapping
            // Kafka configuration is now handled by pipeline-kafka-quarkus-extension

            applyPipelineSystemProperties(composeConfig);
            updateComposeConfigBuilder(composeFileAbsolutePath, projectName);

            return DevServicesResultBuildItem.discovered()
                    .name(FEATURE)
                    .description("Pipeline Dev Services compose provisioning")
                    .config(composeConfig)
                    .build();
        } catch (Exception e) {
            LOG.error("Failed to setup compose file", e);
            throw new RuntimeException("Failed to setup Pipeline Dev Services compose file", e);
        }
    }

    private void extractComposeFile(Path composeFile, Path versionFile, byte[] resourceBytes, String resourceSha)
            throws IOException {
        Files.write(composeFile, resourceBytes);
        writeVersionInfo(versionFile, resourceSha);
        LOG.info("Compose file extracted and version info written");
    }

    private void updateComposeFile(Path composeFile, Path versionFile, byte[] resourceBytes, String resourceSha,
            PipelineDevServicesConfig config) throws IOException {
        // Check if file was edited
        VersionInfo existingVersion = readVersionInfo(versionFile);
        if (existingVersion != null && Files.exists(composeFile)) {
            byte[] existingBytes = Files.readAllBytes(composeFile);
            String existingSha = calculateSHA(existingBytes);

            if (!existingVersion.sha().equals(existingSha)) {
                // File was edited, create backup
                String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
                Path backupFile = Paths.get(composeFile + ".backup." + timestamp);
                Files.copy(composeFile, backupFile, StandardCopyOption.REPLACE_EXISTING);
                LOG.info("Created backup of edited compose file: " + backupFile);
            }
        }

        // Update the file
        Files.write(composeFile, resourceBytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        writeVersionInfo(versionFile, resourceSha);
        LOG.info("Compose file updated");
    }

    private void validateYaml(Path composeFile) {
        try {
            // Simple validation - check if file is readable and has content
            if (!Files.exists(composeFile) || Files.size(composeFile) == 0) {
                throw new RuntimeException("Compose file is empty or doesn't exist");
            }

            // Basic YAML validation - check for key markers
            String content = Files.readString(composeFile);
            if (!content.contains("services:") && !content.contains("version:") && !content.contains("name:")) {
                LOG.warn("Compose file may not be valid YAML - missing expected markers");
            }

            LOG.debug("YAML validation passed");
        } catch (IOException e) {
            throw new RuntimeException("Failed to validate compose file", e);
        }
    }

    private String calculateSHA(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            throw new RuntimeException("Failed to calculate SHA", e);
        }
    }

    private void writeVersionInfo(Path versionFile, String sha) throws IOException {
        String content = "sha=" + sha + "\n";
        Files.write(versionFile, content.getBytes());
    }

    private VersionInfo readVersionInfo(Path versionFile) {
        try {
            if (!Files.exists(versionFile)) {
                return null;
            }

            String content = Files.readString(versionFile);
            String sha = null;
            for (String line : content.split("\n")) {
                if (line.startsWith("sha=")) {
                    sha = line.substring(4).trim();
                    break;
                }
            }

            return sha != null ? new VersionInfo(sha) : null;
        } catch (IOException e) {
            LOG.warn("Failed to read version info", e);
            return null;
        }
    }

    private record VersionInfo(String sha) {
    }

    private void applyPipelineSystemProperties(Map<String, String> composeConfig) {
        composeConfig.forEach((key, value) -> {
            if (key.startsWith(COMPOSE_CONFIG_PREFIX)) {
                String pipelineKey = PIPELINE_CONFIG_PREFIX + key.substring(COMPOSE_CONFIG_PREFIX.length());
                System.setProperty(pipelineKey, value);
            }
        });
    }

    private void clearPipelineSystemProperties() {
        String[] keys = {
                PIPELINE_CONFIG_PREFIX + "enabled",
                PIPELINE_CONFIG_PREFIX + "files",
                PIPELINE_CONFIG_PREFIX + "project-name",
                PIPELINE_CONFIG_PREFIX + "start-services",
                PIPELINE_CONFIG_PREFIX + "stop-services",
                PIPELINE_CONFIG_PREFIX + "reuse-project-for-tests"
        };
        for (String key : keys) {
            System.clearProperty(key);
        }
        ComposeDevServicesConfigBuilder.composeFiles = null;
        ComposeDevServicesConfigBuilder.projectName = null;
        ComposeDevServicesConfigBuilder.enabled = false;
        ComposeDevServicesConfigBuilder.startServices = DEFAULT_START_SERVICES;
        ComposeDevServicesConfigBuilder.stopServices = DEFAULT_STOP_SERVICES;
        ComposeDevServicesConfigBuilder.reuseProjectForTests = DEFAULT_REUSE_PROJECT_FOR_TESTS;
    }

    private void updateComposeConfigBuilder(String composeFileAbsolutePath, String projectName) {
        ComposeDevServicesConfigBuilder.composeFiles = composeFileAbsolutePath;
        ComposeDevServicesConfigBuilder.projectName = projectName;
        ComposeDevServicesConfigBuilder.enabled = true;
        ComposeDevServicesConfigBuilder.startServices = DEFAULT_START_SERVICES;
        ComposeDevServicesConfigBuilder.stopServices = DEFAULT_STOP_SERVICES;
        ComposeDevServicesConfigBuilder.reuseProjectForTests = DEFAULT_REUSE_PROJECT_FOR_TESTS;
    }
}
