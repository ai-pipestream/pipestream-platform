package ai.pipestream.test.support.kafka;

import io.quarkus.test.junit.QuarkusTestProfile;
import org.eclipse.microprofile.config.ConfigProvider;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
 * Test profile that generates unique Kafka topics for test isolation.
 * <p>
 * This profile is automatically applied when using {@link IsolatedKafkaTopics} annotation.
 * It scans the application configuration for Kafka channels and generates unique topic names
 * to prevent cross-contamination between test runs.
 * </p>
 *
 * <h2>Topic Naming Strategy</h2>
 * <p>
 * Generated topics follow the pattern: {@code <prefix>-<uuid-8>}
 * </p>
 * <ul>
 *   <li>Default prefix: Test class simple name (lowercased, kebab-cased)</li>
 *   <li>Custom prefix: Specified via {@link IsolatedKafkaTopics#prefix()}</li>
 *   <li>UUID: 8-character random UUID suffix for uniqueness</li>
 * </ul>
 *
 * <h2>Example</h2>
 * <pre>
 * Test class: S3IntakeUploadTest
 * Default topic: s3-crawl-events-test-default
 * Generated:     s3-intake-upload-test-a1b2c3d4
 * </pre>
 *
 * @since 1.0.0
 */
public class IsolatedKafkaTopicsProfile implements QuarkusTestProfile {

    private static final String DEFAULT_CHANNEL_IN = "s3-crawl-events-in";
    private static final String DEFAULT_CHANNEL_OUT = "s3-crawl-events-out";

    @Override
    public Map<String, String> getConfigOverrides() {
        // Get the test class that's using this profile
        Class<?> testClass = getTestClass();

        // Read the annotation to get configuration
        IsolatedKafkaTopics annotation = testClass.getAnnotation(IsolatedKafkaTopics.class);

        // Generate unique topic name
        String prefix = getTopicPrefix(testClass, annotation);
        String uniqueSuffix = UUID.randomUUID().toString().substring(0, 8);
        String uniqueTopic = prefix + "-" + uniqueSuffix;

        // Get channels to isolate
        Set<String> channels = getChannelsToIsolate(annotation);

        // Build config overrides
        Map<String, String> overrides = new HashMap<>();

        for (String channel : channels) {
            // For both incoming and outgoing, map to the same unique topic
            if (channel.endsWith("-in")) {
                overrides.put("mp.messaging.incoming." + channel + ".topic", uniqueTopic);
            } else if (channel.endsWith("-out")) {
                overrides.put("mp.messaging.outgoing." + channel + ".topic", uniqueTopic);
            } else {
                // Handle channels without -in/-out suffix
                overrides.put("mp.messaging.incoming." + channel + ".topic", uniqueTopic);
                overrides.put("mp.messaging.outgoing." + channel + ".topic", uniqueTopic);
            }
        }

        System.out.println("=== Kafka Topic Isolation ===");
        System.out.println("Test class: " + testClass.getSimpleName());
        System.out.println("Unique topic: " + uniqueTopic);
        System.out.println("Isolated channels: " + channels);
        System.out.println("=============================");

        return overrides;
    }

    /**
     * Gets the test class that is using this profile.
     * This uses stack inspection to find the test class.
     */
    private Class<?> getTestClass() {
        StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();

        for (StackTraceElement element : stackTrace) {
            String className = element.getClassName();
            // Look for test classes (ending with Test)
            if (className.endsWith("Test") || className.endsWith("Tests")) {
                try {
                    return Class.forName(className);
                } catch (ClassNotFoundException e) {
                    // Continue searching
                }
            }
        }

        // Fallback: return this class (won't have annotation, will use defaults)
        return IsolatedKafkaTopicsProfile.class;
    }

    /**
     * Determines the topic prefix from test class name or annotation.
     */
    private String getTopicPrefix(Class<?> testClass, IsolatedKafkaTopics annotation) {
        if (annotation != null && !annotation.prefix().isEmpty()) {
            return annotation.prefix();
        }

        // Generate from test class name: S3IntakeUploadTest -> s3-intake-upload-test
        String className = testClass.getSimpleName();

        // Remove "Test" or "Tests" suffix
        if (className.endsWith("Tests")) {
            className = className.substring(0, className.length() - 5);
        } else if (className.endsWith("Test")) {
            className = className.substring(0, className.length() - 4);
        }

        // Convert camelCase to kebab-case
        return camelToKebab(className);
    }

    /**
     * Gets the list of channels to isolate.
     */
    private Set<String> getChannelsToIsolate(IsolatedKafkaTopics annotation) {
        Set<String> channels = new HashSet<>();

        if (annotation != null && annotation.channels().length > 0) {
            // Use explicitly specified channels
            channels.addAll(Arrays.asList(annotation.channels()));
        } else {
            // Auto-detect: use default s3-crawl-events channels
            // In the future, this could scan application.properties for all channels
            channels.add(DEFAULT_CHANNEL_IN);
            channels.add(DEFAULT_CHANNEL_OUT);
        }

        return channels;
    }

    /**
     * Converts camelCase to kebab-case.
     * Example: S3IntakeUpload -> s3-intake-upload
     */
    private String camelToKebab(String camelCase) {
        StringBuilder result = new StringBuilder();

        for (int i = 0; i < camelCase.length(); i++) {
            char c = camelCase.charAt(i);

            if (Character.isUpperCase(c)) {
                if (i > 0) {
                    result.append('-');
                }
                result.append(Character.toLowerCase(c));
            } else {
                result.append(c);
            }
        }

        return result.toString();
    }
}
