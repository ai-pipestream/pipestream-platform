package ai.pipestream.quarkus.devservices.runtime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * Tests for {@link PipelineDevServicesConfigSource} to verify reading from system properties.
 */
class PipelineDevServicesConfigSourceTest {

    private PipelineDevServicesConfigSource configSource;

    @BeforeEach
    void setUp() {
        configSource = new PipelineDevServicesConfigSource();
        // Clear system properties
        System.clearProperty("pipeline.devservices.enabled");
        System.clearProperty("pipeline.devservices.files");
        System.clearProperty("pipeline.devservices.project-name");
        System.clearProperty("pipeline.devservices.start-services");
        System.clearProperty("pipeline.devservices.stop-services");
        System.clearProperty("pipeline.devservices.reuse-project-for-tests");
    }

    @AfterEach
    void tearDown() {
        // Clear system properties
        System.clearProperty("pipeline.devservices.enabled");
        System.clearProperty("pipeline.devservices.files");
        System.clearProperty("pipeline.devservices.project-name");
        System.clearProperty("pipeline.devservices.start-services");
        System.clearProperty("pipeline.devservices.stop-services");
        System.clearProperty("pipeline.devservices.reuse-project-for-tests");
    }

    @Test
    void getName_returnsExpectedName() {
        assertThat(configSource.getName(), is("PipelineDevServicesConfigSource"));
    }

    @Test
    void getOrdinal_returnsExpectedOrdinal() {
        assertThat(configSource.getOrdinal(), is(250));
    }

    @Test
    void getPropertyNames_returnsEmptyWhenEnabledNotSet() {
        // When enabled is not set, ConfigSource should return empty set
        Set<String> names = configSource.getPropertyNames();
        assertThat(names, is(empty()));
    }

    @Test
    void getPropertyNames_returnsAllPropertiesWhenEnabledIsSet() {
        // When enabled is set, ConfigSource should return all property names
        System.setProperty("pipeline.devservices.enabled", "true");
        System.setProperty("pipeline.devservices.files", "/path/to/compose.yml");
        System.setProperty("pipeline.devservices.project-name", "test-project");
        System.setProperty("pipeline.devservices.start-services", "true");
        System.setProperty("pipeline.devservices.stop-services", "false");
        System.setProperty("pipeline.devservices.reuse-project-for-tests", "true");

        Set<String> names = configSource.getPropertyNames();

        assertThat(names, hasSize(18));
        assertThat(names, containsInAnyOrder(
            // Compose devservices configuration properties
            "quarkus.compose.devservices.enabled",
            "quarkus.compose.devservices.files",
            "quarkus.compose.devservices.project-name",
            "quarkus.compose.devservices.start-services",
            "quarkus.compose.devservices.stop-services",
            "quarkus.compose.devservices.reuse-project-for-tests",
            // Auto-injected service connection properties
            // Note: Apicurio Registry URL is NOT included here - it is discovered
            // automatically by the quarkus-apicurio-registry-protobuf extension
            "quarkus.devservices.launch-on-shared-network",
            "opensearch.hosts",
            "CONSUL_HOST",
            "CONSUL_PORT",
            "quarkus.otel.exporter.otlp.endpoint",
            // S3 (SeaweedFS) configuration
            "quarkus.s3.devservices.enabled",
            "quarkus.s3.endpoint-override",
            "quarkus.s3.path-style-access",
            "quarkus.s3.aws.region",
            "quarkus.s3.aws.credentials.type",
            "quarkus.s3.aws.credentials.static-provider.access-key-id",
            "quarkus.s3.aws.credentials.static-provider.secret-access-key"
        ));
    }

    @Test
    void getValue_returnsNullWhenEnabledNotSet() {
        // When enabled is not set, ConfigSource should return null for all properties
        assertThat(configSource.getValue("quarkus.compose.devservices.files"), is(nullValue()));
        assertThat(configSource.getValue("quarkus.compose.devservices.enabled"), is(nullValue()));
    }

    @Test
    void getValue_returnsNullForNonComposeProperties() {
        // ConfigSource should return null for properties not starting with the prefix
        System.setProperty("pipeline.devservices.enabled", "true");
        assertThat(configSource.getValue("quarkus.datasource.url"), is(nullValue()));
        assertThat(configSource.getValue("some.other.property"), is(nullValue()));
    }

    @Test
    void getValue_returnsNullForUnknownComposeProperty() {
        // ConfigSource should return null for unknown compose properties
        System.setProperty("pipeline.devservices.enabled", "true");
        assertThat(configSource.getValue("quarkus.compose.devservices.unknown"), is(nullValue()));
    }

    @Test
    void getValue_readsFiles() {
        System.setProperty("pipeline.devservices.enabled", "true");
        System.setProperty("pipeline.devservices.files", "/initial/path.yml");
        assertThat(configSource.getValue("quarkus.compose.devservices.files"), is("/initial/path.yml"));

        // Change value after initial read
        System.setProperty("pipeline.devservices.files", "/updated/path.yml");
        // Should read the updated value
        assertThat(configSource.getValue("quarkus.compose.devservices.files"), is("/updated/path.yml"));
    }

    @Test
    void getValue_readsEnabled() {
        System.setProperty("pipeline.devservices.enabled", "true");
        assertThat(configSource.getValue("quarkus.compose.devservices.enabled"), is("true"));

        System.setProperty("pipeline.devservices.enabled", "false");
        assertThat(configSource.getValue("quarkus.compose.devservices.enabled"), is("false"));
    }

    @Test
    void getValue_readsProjectName() {
        System.setProperty("pipeline.devservices.enabled", "true");
        System.setProperty("pipeline.devservices.project-name", "custom-project");
        assertThat(configSource.getValue("quarkus.compose.devservices.project-name"), is("custom-project"));

        System.clearProperty("pipeline.devservices.project-name");
        // Should return null when not set (no default fallback in this implementation)
        assertThat(configSource.getValue("quarkus.compose.devservices.project-name"), is(nullValue()));
    }

    @Test
    void getValue_readsStartServices() {
        System.setProperty("pipeline.devservices.enabled", "true");
        System.setProperty("pipeline.devservices.start-services", "true");
        assertThat(configSource.getValue("quarkus.compose.devservices.start-services"), is("true"));

        System.setProperty("pipeline.devservices.start-services", "false");
        assertThat(configSource.getValue("quarkus.compose.devservices.start-services"), is("false"));
    }

    @Test
    void getValue_readsStopServices() {
        System.setProperty("pipeline.devservices.enabled", "true");
        System.setProperty("pipeline.devservices.stop-services", "false");
        assertThat(configSource.getValue("quarkus.compose.devservices.stop-services"), is("false"));

        System.setProperty("pipeline.devservices.stop-services", "true");
        assertThat(configSource.getValue("quarkus.compose.devservices.stop-services"), is("true"));
    }

    @Test
    void getValue_readsReuseProjectForTests() {
        System.setProperty("pipeline.devservices.enabled", "true");
        System.setProperty("pipeline.devservices.reuse-project-for-tests", "true");
        assertThat(configSource.getValue("quarkus.compose.devservices.reuse-project-for-tests"), is("true"));

        System.setProperty("pipeline.devservices.reuse-project-for-tests", "false");
        assertThat(configSource.getValue("quarkus.compose.devservices.reuse-project-for-tests"), is("false"));
    }

    @Test
    void getValue_readsAllValuesCorrectly() {
        // Set all properties
        System.setProperty("pipeline.devservices.enabled", "true");
        System.setProperty("pipeline.devservices.files", "/test/compose.yml");
        System.setProperty("pipeline.devservices.project-name", "test-project");
        System.setProperty("pipeline.devservices.start-services", "true");
        System.setProperty("pipeline.devservices.stop-services", "false");
        System.setProperty("pipeline.devservices.reuse-project-for-tests", "true");

        assertThat(configSource.getValue("quarkus.compose.devservices.files"), is("/test/compose.yml"));
        assertThat(configSource.getValue("quarkus.compose.devservices.project-name"), is("test-project"));
        assertThat(configSource.getValue("quarkus.compose.devservices.enabled"), is("true"));
        assertThat(configSource.getValue("quarkus.compose.devservices.start-services"), is("true"));
        assertThat(configSource.getValue("quarkus.compose.devservices.stop-services"), is("false"));
        assertThat(configSource.getValue("quarkus.compose.devservices.reuse-project-for-tests"), is("true"));
    }
}
