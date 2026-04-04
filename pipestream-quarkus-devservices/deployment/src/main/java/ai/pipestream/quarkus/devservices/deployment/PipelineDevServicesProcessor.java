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
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Provisions the shared {@code compose-devservices.yml} and wires {@code quarkus.compose.devservices.*}.
 *
 * <p><strong>Dev UI note (Quarkus core):</strong> Enabling Compose Dev Services causes Quarkus to register a
 * dev service named {@code "Compose Dev Services"} (with spaces). Quarkus 3.32.x {@code DevUIProcessor} registers
 * the footer JSON-RPC method using a space-stripped name but embeds the <em>unsanitized</em> name in Dev UI
 * metadata, so every app that turns on compose sees {@code method not found} for
 * {@code devui-footer-log_Compose Dev ServicesLog} and related console errors. That bug is not in this
 * extension—we publish {@link #FEATURE} as {@code pipeline-devservices} (no spaces). The fix belongs in
 * {@code quarkus-devui-deployment} (metadata should use the same sanitized suffix as the RPC method), or upstream
 * Quarkus changing the compose display name. Until then, teams can set
 * {@code %dev.quarkus.compose.devservices.enabled=false} if they run the same stack via Docker Compose manually.
 */
class PipelineDevServicesProcessor {

    private static final Logger LOG = Logger.getLogger(PipelineDevServicesProcessor.class);
    private static final String FEATURE = "pipeline-devservices";
    private static final String COMPOSE_FILE_RESOURCE = "compose-devservices.yml";
    private static final String INIT_SCRIPT_RESOURCE = "init-postgres.sql";
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

            // Create target directory if it doesn't exist
            if (!Files.exists(targetPath)) {
                Files.createDirectories(targetPath);
                LOG.info("Created target directory: " + targetPath);
            }

            // Write-once: only extract if the file doesn't exist.
            // Quarkus 3.34+ tracks compose files as Gradle task inputs —
            // overwriting triggers hot-reload cascades across all running services.
            // To pick up a new compose file version, delete ~/.pipeline/ and restart.
            if (!Files.exists(composeFile)) {
                try (InputStream composeResource = Thread.currentThread().getContextClassLoader()
                        .getResourceAsStream(COMPOSE_FILE_RESOURCE)) {
                    if (composeResource == null) {
                        LOG.warn("Compose file not found in classpath: " + COMPOSE_FILE_RESOURCE);
                        clearPipelineSystemProperties();
                        return DevServicesResultBuildItem.discovered()
                                .name(FEATURE)
                                .description("Pipeline Dev Services compose resource not found")
                                .config(Map.of())
                                .build();
                    }
                    Files.write(composeFile, composeResource.readAllBytes());
                    LOG.info("Extracted compose file to: " + composeFile);
                }
            } else {
                LOG.debug("Compose file already exists, skipping extraction: " + composeFile);
            }

            // Extract init script (write-once, same logic)
            Path initScriptFile = targetPath.resolve(INIT_SCRIPT_RESOURCE);
            if (!Files.exists(initScriptFile)) {
                try (InputStream initScriptResource = Thread.currentThread().getContextClassLoader()
                        .getResourceAsStream(INIT_SCRIPT_RESOURCE)) {
                    if (initScriptResource != null) {
                        Files.write(initScriptFile, initScriptResource.readAllBytes());
                        LOG.debug("Extracted init script to: " + initScriptFile);
                    }
                }
            }

            String composeFileAbsolutePath = composeFile.toAbsolutePath().toString();
            String projectName = config.projectName()
                    .filter(s -> !s.isEmpty())
                    .orElse("pipeline-shared-devservices");

            LOG.info("Pipeline Dev Services configured: " + composeFileAbsolutePath);

            Map<String, String> composeConfig = new LinkedHashMap<>();
            composeConfig.put(COMPOSE_CONFIG_PREFIX + "enabled", Boolean.toString(true));
            composeConfig.put(COMPOSE_CONFIG_PREFIX + "files", composeFileAbsolutePath);
            composeConfig.put(COMPOSE_CONFIG_PREFIX + "project-name", projectName);
            composeConfig.put(COMPOSE_CONFIG_PREFIX + "start-services", Boolean.toString(DEFAULT_START_SERVICES));
            composeConfig.put(COMPOSE_CONFIG_PREFIX + "stop-services", Boolean.toString(DEFAULT_STOP_SERVICES));
            composeConfig.put(COMPOSE_CONFIG_PREFIX + "reuse-project-for-tests",
                    Boolean.toString(DEFAULT_REUSE_PROJECT_FOR_TESTS));

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
