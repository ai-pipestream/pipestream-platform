package ai.pipestream.quarkus.devservices.deployment;

import ai.pipestream.quarkus.devservices.PipelineDevServicesConfig;
import io.quarkus.deployment.builditem.DevServicesResultBuildItem;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class PipelineDevServicesProcessorTest {

    private static final String COMPOSE_FILE = "compose-devservices.yml";
    private static final String FEATURE = "pipeline-devservices";
    private static final String COMPOSE_PREFIX = "quarkus.compose.devservices.";

    @BeforeEach
    @AfterEach
    void resetSystemProperties() {
        String prefix = "pipeline.devservices.";
        for (String key : List.of(
                "enabled",
                "files",
                "project-name",
                "start-services",
                "stop-services",
                "reuse-project-for-tests")) {
            System.clearProperty(prefix + key);
        }
    }

    @Test
    void featureBuildItem_hasExpectedName() {
        PipelineDevServicesProcessor p = new PipelineDevServicesProcessor();
        FeatureBuildItem f = p.feature();
        assertNotNull(f);
        assertEquals("pipeline-devservices", f.getName());
    }

    @Test
    void setupComposeFile_extractsAndProducesRuntimeConfig(@TempDir Path temp) throws Exception {
        TestConfig cfg = new TestConfig(true, temp.toString(), Optional.empty());

        PipelineDevServicesProcessor p = new PipelineDevServicesProcessor();
        DevServicesResultBuildItem result = p.setupComposeFile(cfg);

        // Compose file is created
        Path composePath = temp.resolve(COMPOSE_FILE);
        assertTrue(Files.exists(composePath), "Compose file should be extracted");
        assertTrue(Files.size(composePath) > 0, "Compose file should not be empty");

        // Content matches the classpath resource
        assertArrayEquals(readResourceBytes(), Files.readAllBytes(composePath),
                "Extracted file should match the classpath resource");

        assertNotNull(result);
        assertEquals(FEATURE, result.getName());
        Map<String, String> config = result.getConfig();
        assertEquals("true", config.get(COMPOSE_PREFIX + "enabled"));
        assertEquals(composePath.toAbsolutePath().toString(), config.get(COMPOSE_PREFIX + "files"));
        assertEquals("pipeline-shared-devservices", config.get(COMPOSE_PREFIX + "project-name"));
        assertEquals("true", config.get(COMPOSE_PREFIX + "start-services"));
        assertEquals("false", config.get(COMPOSE_PREFIX + "stop-services"));
        assertEquals("true", config.get(COMPOSE_PREFIX + "reuse-project-for-tests"));

        assertEquals("true", System.getProperty("pipeline.devservices.enabled"));
        assertEquals(composePath.toAbsolutePath().toString(), System.getProperty("pipeline.devservices.files"));
    }

    @Test
    void setupComposeFile_respectsDisabledFlag(@TempDir Path temp) throws Exception {
        TestConfig cfg = new TestConfig(false, temp.toString(), Optional.empty());

        PipelineDevServicesProcessor p = new PipelineDevServicesProcessor();
        DevServicesResultBuildItem result = p.setupComposeFile(cfg);

        // No files created
        assertFalse(Files.exists(temp.resolve(COMPOSE_FILE)));

        assertNotNull(result);
        assertEquals(FEATURE, result.getName());
        assertEquals("false", result.getConfig().get(COMPOSE_PREFIX + "enabled"));
        assertNull(System.getProperty("pipeline.devservices.enabled"));
    }

    @Test
    void setupComposeFile_projectNameOverrideAndBlank(@TempDir Path temp) {
        PipelineDevServicesProcessor p = new PipelineDevServicesProcessor();

        // Custom project name
        TestConfig cfgCustom = new TestConfig(true, temp.resolve("c1").toString(), Optional.of("custom"));
        DevServicesResultBuildItem custom = p.setupComposeFile(cfgCustom);
        assertEquals("custom", custom.getConfig().get(COMPOSE_PREFIX + "project-name"));

        // Blank should fallback to default
        TestConfig cfgBlank = new TestConfig(true, temp.resolve("c2").toString(), Optional.of(""));
        DevServicesResultBuildItem blank = p.setupComposeFile(cfgBlank);
        assertEquals("pipeline-shared-devservices", blank.getConfig().get(COMPOSE_PREFIX + "project-name"));
    }

    @Test
    void setupComposeFile_doesNotOverwriteExistingFile(@TempDir Path temp) throws Exception {
        PipelineDevServicesProcessor p = new PipelineDevServicesProcessor();
        TestConfig cfg = new TestConfig(true, temp.toString(), Optional.empty());

        // First run extracts
        p.setupComposeFile(cfg);
        Path compose = temp.resolve(COMPOSE_FILE);
        byte[] initial = Files.readAllBytes(compose);

        // Simulate manual edit
        byte[] edited = "name: my-custom-compose\nservices: {}\n".getBytes();
        Files.write(compose, edited);

        // Second run should NOT overwrite
        p.setupComposeFile(cfg);
        byte[] afterSecondRun = Files.readAllBytes(compose);
        assertArrayEquals(edited, afterSecondRun,
                "Write-once: existing compose file should not be overwritten");
    }

    @Test
    void setupComposeFile_extractsWhenFileDeleted(@TempDir Path temp) throws Exception {
        PipelineDevServicesProcessor p = new PipelineDevServicesProcessor();
        TestConfig cfg = new TestConfig(true, temp.toString(), Optional.empty());

        // First run extracts
        p.setupComposeFile(cfg);
        Path compose = temp.resolve(COMPOSE_FILE);
        assertTrue(Files.exists(compose));

        // Delete it
        Files.delete(compose);
        assertFalse(Files.exists(compose));

        // Second run should re-extract
        p.setupComposeFile(cfg);
        assertTrue(Files.exists(compose), "Compose file should be re-extracted after deletion");
        assertArrayEquals(readResourceBytes(), Files.readAllBytes(compose),
                "Re-extracted file should match the classpath resource");
    }

    @Test
    void setupComposeFile_extractsInitScript(@TempDir Path temp) throws Exception {
        PipelineDevServicesProcessor p = new PipelineDevServicesProcessor();
        TestConfig cfg = new TestConfig(true, temp.toString(), Optional.empty());

        p.setupComposeFile(cfg);

        Path initScript = temp.resolve("init-postgres.sql");
        assertTrue(Files.exists(initScript), "Init script should be extracted");
        assertTrue(Files.size(initScript) > 0, "Init script should not be empty");
    }

    @Test
    void setupComposeFile_initScriptNotOverwritten(@TempDir Path temp) throws Exception {
        PipelineDevServicesProcessor p = new PipelineDevServicesProcessor();
        TestConfig cfg = new TestConfig(true, temp.toString(), Optional.empty());

        // First run
        p.setupComposeFile(cfg);
        Path initScript = temp.resolve("init-postgres.sql");
        byte[] edited = "-- custom init\n".getBytes();
        Files.write(initScript, edited);

        // Second run should not overwrite
        p.setupComposeFile(cfg);
        assertArrayEquals(edited, Files.readAllBytes(initScript),
                "Write-once: existing init script should not be overwritten");
    }

    // Helpers
    private static byte[] readResourceBytes() throws IOException {
        try (InputStream is = Thread.currentThread().getContextClassLoader().getResourceAsStream(COMPOSE_FILE)) {
            assertNotNull(is, "Test resource '" + COMPOSE_FILE + "' not found on classpath");
            return is.readAllBytes();
        }
    }

    private record TestConfig(boolean enabled,
                              String targetDir,
                              Optional<String> projectName) implements PipelineDevServicesConfig {
        @Override
        public boolean enabled() { return enabled; }
        @Override
        public String targetDir() { return targetDir; }
        @Override
        public Optional<String> projectName() { return projectName; }
    }
}
