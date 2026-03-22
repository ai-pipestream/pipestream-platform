package ai.pipestream.test.support;

import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Guards against Testcontainers container reuse being enabled, which causes
 * container leaks when combined with Quarkus DevServices.
 * <p>
 * <b>The problem:</b> When {@code testcontainers.reuse.enable=true} is set in
 * {@code ~/.testcontainers.properties}, Testcontainers skips starting Ryuk (the
 * cleanup daemon). But Quarkus DevServices creates a new container per test run
 * with a unique {@code process-uuid}, so containers accumulate indefinitely.
 * </p>
 * <p>
 * <b>Usage:</b> Call {@link #ensureReuseDisabled()} early in the test JVM lifecycle
 * (e.g., from a {@link io.quarkus.test.common.QuarkusTestResourceLifecycleManager}
 * or a {@code @BeforeAll} method). It will:
 * <ul>
 *   <li>Check the system property {@code testcontainers.reuse.enable}</li>
 *   <li>Check {@code ~/.testcontainers.properties} for the setting</li>
 *   <li>Force the system property to {@code false} if reuse is detected</li>
 *   <li>Log a warning explaining why</li>
 * </ul>
 * </p>
 */
public final class TestcontainersReuseGuard {

    private static final Logger LOG = Logger.getLogger(TestcontainersReuseGuard.class);
    private static final String PROPERTY_KEY = "testcontainers.reuse.enable";
    private static volatile boolean checked = false;

    private TestcontainersReuseGuard() {
    }

    /**
     * Ensures Testcontainers container reuse is disabled. Safe to call multiple times —
     * only runs the check once per JVM.
     */
    public static void ensureReuseDisabled() {
        if (checked) {
            return;
        }
        checked = true;

        boolean reuseFromSystemProp = "true".equalsIgnoreCase(System.getProperty(PROPERTY_KEY));
        boolean reuseFromFile = isReuseEnabledInPropertiesFile();

        if (reuseFromSystemProp || reuseFromFile) {
            LOG.warnf("Testcontainers reuse is enabled (%s). This causes container leaks "
                    + "with Quarkus DevServices — each test run creates a new container that "
                    + "is never cleaned up because Ryuk is disabled when reuse=true. "
                    + "Forcing testcontainers.reuse.enable=false for this JVM. "
                    + "To fix permanently: set testcontainers.reuse.enable=false in "
                    + "~/.testcontainers.properties",
                    reuseFromSystemProp ? "system property" : "~/.testcontainers.properties");
            System.setProperty(PROPERTY_KEY, "false");
        }
    }

    private static boolean isReuseEnabledInPropertiesFile() {
        Path propsFile = Path.of(System.getProperty("user.home"), ".testcontainers.properties");
        if (!Files.exists(propsFile)) {
            return false;
        }
        try {
            return Files.readString(propsFile).contains("testcontainers.reuse.enable=true");
        } catch (IOException e) {
            LOG.debugf("Could not read %s: %s", propsFile, e.getMessage());
            return false;
        }
    }
}
