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
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class PipelineDevServicesProcessorTest {

    private static final String COMPOSE_FILE = "compose-devservices.yml";
    private static final String VERSION_FILE = ".version";
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
        TestConfig cfg = new TestConfig(true, temp.toString(), Optional.empty(), true, false);

        PipelineDevServicesProcessor p = new PipelineDevServicesProcessor();
        DevServicesResultBuildItem result = p.setupComposeFile(cfg);

        // Files are created
        Path composePath = temp.resolve(COMPOSE_FILE);
        Path versionPath = temp.resolve(VERSION_FILE);
        assertTrue(Files.exists(composePath), "Compose file should be extracted");
        assertTrue(Files.exists(versionPath), "Version file should be written");
        assertTrue(Files.size(composePath) > 0, "Compose file should not be empty");

        // Version file content matches resource SHA
        String expectedSha = sha256(readResourceBytes());
        String versionContent = Files.readString(versionPath);
        assertTrue(versionContent.contains("sha=" + expectedSha));

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
        TestConfig cfg = new TestConfig(false, temp.toString(), Optional.empty(), true, false);

        PipelineDevServicesProcessor p = new PipelineDevServicesProcessor();
        DevServicesResultBuildItem result = p.setupComposeFile(cfg);

        // No files created
        assertFalse(Files.exists(temp.resolve(COMPOSE_FILE)));
        assertFalse(Files.exists(temp.resolve(VERSION_FILE)));

        assertNotNull(result);
        assertEquals(FEATURE, result.getName());
        assertEquals("false", result.getConfig().get(COMPOSE_PREFIX + "enabled"));
        assertNull(System.getProperty("pipeline.devservices.enabled"));
    }

    @Test
    void setupComposeFile_projectNameOverrideAndBlank(@TempDir Path temp) {
        PipelineDevServicesProcessor p = new PipelineDevServicesProcessor();

        // Custom project name
        TestConfig cfgCustom = new TestConfig(true, temp.resolve("c1").toString(), Optional.of("custom"), true, false);
        DevServicesResultBuildItem custom = p.setupComposeFile(cfgCustom);
        assertEquals("custom", custom.getConfig().get(COMPOSE_PREFIX + "project-name"));

        // Blank should fallback to default
        TestConfig cfgBlank = new TestConfig(true, temp.resolve("c2").toString(), Optional.of(""), true, false);
        DevServicesResultBuildItem blank = p.setupComposeFile(cfgBlank);
        assertEquals("pipeline-shared-devservices", blank.getConfig().get(COMPOSE_PREFIX + "project-name"));
    }

    @Test
    void setupComposeFile_noUpdateWhenVersionMatches(@TempDir Path temp) throws Exception {
        PipelineDevServicesProcessor p = new PipelineDevServicesProcessor();
        TestConfig cfg = new TestConfig(true, temp.toString(), Optional.empty(), true, false);

        // First run extracts
        p.setupComposeFile(cfg);
        byte[] initial = Files.readAllBytes(temp.resolve(COMPOSE_FILE));

        // Second run should not change the file
        p.setupComposeFile(cfg);
        byte[] second = Files.readAllBytes(temp.resolve(COMPOSE_FILE));
        assertArrayEquals(initial, second);
    }

    @Test
    void setupComposeFile_autoUpdatesWhenVersionDiffersAndNotEdited(@TempDir Path temp) throws Exception {
        PipelineDevServicesProcessor p = new PipelineDevServicesProcessor();
        TestConfig cfg = new TestConfig(true, temp.toString(), Optional.empty(), true, false);

        // Prepare an older version: write different content and matching version sha
        Path compose = temp.resolve(COMPOSE_FILE);
        Path version = temp.resolve(VERSION_FILE);
        Files.createDirectories(temp);
        byte[] oldContent = "version: '3'\nservices: {}\n".getBytes();
        Files.write(compose, oldContent, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        String oldSha = sha256(oldContent);
        Files.writeString(version, "sha=" + oldSha + "\n");

        // Run - should auto-update to resource bytes (no backup since not edited per recorded sha)
        p.setupComposeFile(cfg);

        byte[] now = Files.readAllBytes(compose);
        byte[] expected = readResourceBytes();
        assertArrayEquals(expected, now, "Compose file should be updated to resource content");

        String versionContent = Files.readString(version);
        assertTrue(versionContent.contains("sha=" + sha256(expected)));
    }

    @Test
    void setupComposeFile_skipsAutoUpdateWhenManuallyEdited(@TempDir Path temp) throws Exception {
        PipelineDevServicesProcessor p = new PipelineDevServicesProcessor();
        TestConfig cfg = new TestConfig(true, temp.toString(), Optional.empty(), true, false);

        // Start from extracted
        p.setupComposeFile(cfg);

        Path compose = temp.resolve(COMPOSE_FILE);
        Path version = temp.resolve(VERSION_FILE);
        byte[] original = Files.readAllBytes(compose);
        String recordedSha = Files.readString(version).trim().substring(4);
        assertEquals(sha256(original), recordedSha);

        // Simulate manual edit: change file without changing recorded version sha
        Files.writeString(compose, new String(original) + "# edited\n");

        // Run - should detect edit and skip auto-update
        p.setupComposeFile(cfg);
        byte[] after = Files.readAllBytes(compose);
        assertNotEquals(sha256(after), sha256(readResourceBytes()), "File should not be auto-updated");
        // version file should still contain old sha
        assertTrue(Files.readString(version).contains("sha=" + recordedSha));
    }

    @Test
    void setupComposeFile_forceUpdateCreatesBackupWhenEdited(@TempDir Path temp) throws Exception {
        PipelineDevServicesProcessor p = new PipelineDevServicesProcessor();
        TestConfig cfg = new TestConfig(true, temp.toString(), Optional.empty(), true, true);

        // Extract first
        p.setupComposeFile(cfg);
        Path compose = temp.resolve(COMPOSE_FILE);
        Path version = temp.resolve(VERSION_FILE);

        // Edit file to simulate manual changes
        Files.writeString(compose, Files.readString(compose) + "# manual edit\n");
        // Also simulate that extension resource has a new version by changing stored sha
        Files.writeString(version, "sha=0000000000000000000000000000000000000000000000000000000000000000\n");

        // Run - should force update and create backup due to edit
        p.setupComposeFile(cfg);

        // New content equals resource
        assertArrayEquals(readResourceBytes(), Files.readAllBytes(compose));
        // Version updated to resource sha
        assertEquals(sha256(readResourceBytes()), Files.readString(version).trim().substring(4));
        // Backup exists (pattern .backup.YYYYMMDD-HHMMSS)
        List<Path> backups = Files.list(temp)
                .filter(pth -> pth.getFileName().toString().startsWith(COMPOSE_FILE + ".backup."))
                .collect(Collectors.toList());
        assertFalse(backups.isEmpty(), "Backup file should be created when edited and forceUpdate=true");
    }

    @Test
    void setupComposeFile_throwsWhenYamlEmptyButVersionMatches(@TempDir Path temp) throws Exception {
        PipelineDevServicesProcessor p = new PipelineDevServicesProcessor();
        TestConfig cfg = new TestConfig(true, temp.toString(), Optional.empty(), true, false);

        // Create empty compose file but with matching version sha to skip update path
        Path compose = temp.resolve(COMPOSE_FILE);
        Path version = temp.resolve(VERSION_FILE);
        Files.createDirectories(temp);
        Files.writeString(compose, "");
        String resourceSha = sha256(readResourceBytes());
        Files.writeString(version, "sha=" + resourceSha + "\n");

        RuntimeException ex = assertThrows(RuntimeException.class, () -> p.setupComposeFile(cfg));
        assertTrue(ex.getMessage().contains("Failed to setup Pipeline Dev Services compose file"));
    }

    // Helpers
    private static byte[] readResourceBytes() throws IOException {
        try (InputStream is = Thread.currentThread().getContextClassLoader().getResourceAsStream(COMPOSE_FILE)) {
            assertNotNull(is, "Test resource '" + COMPOSE_FILE + "' not found on classpath");
            return is.readAllBytes();
        }
    }

    private static String sha256(byte[] data) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(data);
        StringBuilder hex = new StringBuilder();
        for (byte b : hash) {
            String h = Integer.toHexString(0xff & b);
            if (h.length() == 1) hex.append('0');
            hex.append(h);
        }
        return hex.toString();
    }

    private record TestConfig(boolean enabled,
                              String targetDir,
                              Optional<String> projectName,
                              boolean autoUpdate,
                              boolean forceUpdate) implements PipelineDevServicesConfig {
        @Override
        public boolean enabled() { return enabled; }
        @Override
        public String targetDir() { return targetDir; }
        @Override
        public Optional<String> projectName() { return projectName; }
        @Override
        public boolean autoUpdate() { return autoUpdate; }
        @Override
        public boolean forceUpdate() { return forceUpdate; }
    }
}
