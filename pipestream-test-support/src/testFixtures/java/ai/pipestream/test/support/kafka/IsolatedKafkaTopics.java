package ai.pipestream.test.support.kafka;

import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;

import java.lang.annotation.*;

/**
 * Meta-annotation that automatically isolates Kafka topics for test execution.
 * <p>
 * This annotation ensures that each test class gets its own unique Kafka topics,
 * preventing cross-contamination between tests. It automatically detects channels
 * in your application and generates unique topic names with a UUID suffix.
 * </p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * @QuarkusTest
 * @IsolatedKafkaTopics
 * class MyKafkaTest {
 *     // Test methods here
 * }
 * }</pre>
 *
 * <h2>How It Works</h2>
 * <ul>
 *   <li>Scans for all Kafka channels in your test configuration</li>
 *   <li>Generates unique topic names: {@code <original-topic>-<test-class>-<uuid>}</li>
 *   <li>Overrides topic configuration for both incoming and outgoing channels</li>
 *   <li>Works with Apicurio Registry auto-configuration</li>
 * </ul>
 *
 * <h2>Custom Prefix</h2>
 * <p>
 * By default, uses the test class name as prefix. You can customize via {@link #prefix()}:
 * </p>
 * <pre>{@code
 * @IsolatedKafkaTopics(prefix = "my-test")
 * class MyKafkaTest {
 * }
 * }</pre>
 *
 * @since 1.0.0
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@TestProfile(IsolatedKafkaTopicsProfile.class)
public @interface IsolatedKafkaTopics {

    /**
     * Optional prefix for generated topic names.
     * <p>
     * If not specified, uses the test class simple name (lowercased, kebab-cased).
     * </p>
     *
     * @return the topic prefix
     */
    String prefix() default "";

    /**
     * Channel names to isolate.
     * <p>
     * If empty (default), auto-detects all channels from application.properties.
     * If specified, only isolates the listed channels.
     * </p>
     *
     * @return array of channel names to isolate
     */
    String[] channels() default {};
}
