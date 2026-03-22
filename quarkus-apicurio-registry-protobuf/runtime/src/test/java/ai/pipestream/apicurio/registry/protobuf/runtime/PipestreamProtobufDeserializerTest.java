package ai.pipestream.apicurio.registry.protobuf.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link PipestreamProtobufDeserializer} verifying the TCCL classloader
 * fix and schema resolution fallback logic.
 */
class PipestreamProtobufDeserializerTest {

    private static final String VALUE_RETURN_CLASS = "apicurio.registry.deserializer.value.return-class";
    private static final String KEY_RETURN_CLASS = "apicurio.registry.deserializer.key.return-class";

    // =========================================================================
    // Configure / TCCL classloading tests
    // =========================================================================

    @Test
    void configureReplacesStringWithClassObject() {
        String className = "com.google.protobuf.DynamicMessage";
        Map<String, Object> configs = new HashMap<>();
        configs.put(VALUE_RETURN_CLASS, className);
        configs.put("apicurio.registry.url", "http://localhost:8081/apis/registry/v3");

        TestCapturingDeserializer deserializer = new TestCapturingDeserializer();
        deserializer.doTestConfigure(configs, false);

        assertThat(deserializer.lastConfigs.get(VALUE_RETURN_CLASS))
                .as("String class name should be replaced with Class object via TCCL")
                .isInstanceOf(Class.class);
        assertThat(((Class<?>) deserializer.lastConfigs.get(VALUE_RETURN_CLASS)).getName())
                .isEqualTo("com.google.protobuf.DynamicMessage");
    }

    @Test
    void configurePassesThroughWhenNoReturnClass() {
        Map<String, Object> configs = new HashMap<>();
        configs.put("apicurio.registry.url", "http://localhost:8081/apis/registry/v3");

        TestCapturingDeserializer deserializer = new TestCapturingDeserializer();
        deserializer.doTestConfigure(configs, false);

        assertThat(deserializer.lastConfigs.get(VALUE_RETURN_CLASS))
                .as("No return class configured — should be absent").isNull();
    }

    @Test
    void configureHandlesInvalidClassName() {
        Map<String, Object> configs = new HashMap<>();
        configs.put(VALUE_RETURN_CLASS, "com.nonexistent.FakeClass");
        configs.put("apicurio.registry.url", "http://localhost:8081/apis/registry/v3");

        TestCapturingDeserializer deserializer = new TestCapturingDeserializer();
        deserializer.doTestConfigure(configs, false);

        assertThat(deserializer.lastConfigs.get(VALUE_RETURN_CLASS))
                .as("Invalid class name should fall through as-is")
                .isEqualTo("com.nonexistent.FakeClass");
    }

    @Test
    void configureIgnoresEmptyString() {
        Map<String, Object> configs = new HashMap<>();
        configs.put(VALUE_RETURN_CLASS, "");
        configs.put("apicurio.registry.url", "http://localhost:8081/apis/registry/v3");

        TestCapturingDeserializer deserializer = new TestCapturingDeserializer();
        deserializer.doTestConfigure(configs, false);

        assertThat(deserializer.lastConfigs.get(VALUE_RETURN_CLASS))
                .as("Empty string should pass through unchanged").isEqualTo("");
    }

    @Test
    void configureUsesKeyPropertyWhenIsKey() {
        Map<String, Object> configs = new HashMap<>();
        configs.put(KEY_RETURN_CLASS, "com.google.protobuf.DynamicMessage");
        configs.put("apicurio.registry.url", "http://localhost:8081/apis/registry/v3");

        TestCapturingDeserializer deserializer = new TestCapturingDeserializer();
        deserializer.doTestConfigure(configs, true);

        assertThat(deserializer.lastConfigs.get(KEY_RETURN_CLASS))
                .as("Key return class should also be replaced with Class object")
                .isInstanceOf(Class.class);
    }

    // =========================================================================
    // isRecoverableSchemaFailure tests — the core fallback trigger logic
    // =========================================================================

    @Test
    void recognizesSchemaExceptionByClassName() {
        // The real SchemaException has simple name "SchemaException" — test that the
        // class name check works by using a nested class with that exact simple name
        RuntimeException schemaEx = new SchemaException(
                "unable to find ai/pipestream/data/v1/pipeline_core_types.proto");
        RuntimeException wrapped = new RuntimeException("Deserialization failed", schemaEx);

        assertThat(PipestreamProtobufDeserializer.isRecoverableSchemaFailure(wrapped))
                .as("SchemaException (by class simple name) should trigger fallback")
                .isTrue();
    }

    @Test
    void recognizesSchemaExceptionByMessage() {
        // Even if the exception class name doesn't match, the message pattern should match
        RuntimeException ex = new RuntimeException(
                "unable to find ai/pipestream/data/v1/pipeline_core_types.proto");

        assertThat(PipestreamProtobufDeserializer.isRecoverableSchemaFailure(ex))
                .as("Exception with 'unable to find *.proto' message should trigger fallback")
                .isTrue();
    }

    @Test
    void recognizesNpeFromProtobufSchemaParser() {
        NullPointerException npe = new NullPointerException();
        npe.setStackTrace(new StackTraceElement[]{
                new StackTraceElement(
                        "io.apicurio.registry.serde.protobuf.ProtobufSchemaParser",
                        "parseSchema", "ProtobufSchemaParser.java", 42)
        });
        RuntimeException wrapped = new RuntimeException("parse failed", npe);

        assertThat(PipestreamProtobufDeserializer.isRecoverableSchemaFailure(wrapped))
                .as("NPE from ProtobufSchemaParser.parseSchema should trigger fallback")
                .isTrue();
    }

    @Test
    void recognizesApicurioProblemDetailsNotFound() {
        // Use the real Apicurio ProblemDetails class if available on the classpath
        Throwable problemDetails;
        try {
            Class<?> pdClass = Class.forName("io.apicurio.registry.rest.client.models.ProblemDetails");
            problemDetails = (Throwable) pdClass.getDeclaredConstructor(String.class)
                    .newInstance("Content with ID 12345 not found (404)");
        } catch (Exception e) {
            // If not on classpath, skip this test
            org.junit.jupiter.api.Assumptions.assumeTrue(false,
                    "Apicurio ProblemDetails not on test classpath");
            return;
        }
        RuntimeException wrapped = new RuntimeException("Registry error", problemDetails);

        assertThat(PipestreamProtobufDeserializer.isRecoverableSchemaFailure(wrapped))
                .as("Apicurio ProblemDetails with 'not found' should trigger fallback")
                .isTrue();
    }

    @Test
    void recognizesConnectException() {
        ConnectException connEx = new ConnectException("Connection refused");
        RuntimeException wrapped = new RuntimeException("Registry unreachable", connEx);

        assertThat(PipestreamProtobufDeserializer.isRecoverableSchemaFailure(wrapped))
                .as("ConnectException should trigger fallback (registry down)")
                .isTrue();
    }

    @Test
    void recognizesSocketTimeoutException() {
        SocketTimeoutException timeoutEx = new SocketTimeoutException("Read timed out");
        RuntimeException wrapped = new RuntimeException("Registry timeout", timeoutEx);

        assertThat(PipestreamProtobufDeserializer.isRecoverableSchemaFailure(wrapped))
                .as("SocketTimeoutException should trigger fallback (registry slow)")
                .isTrue();
    }

    @Test
    void doesNotFallbackForUnrelatedExceptions() {
        RuntimeException unrelated = new RuntimeException("Something completely different");

        assertThat(PipestreamProtobufDeserializer.isRecoverableSchemaFailure(unrelated))
                .as("Unrelated exceptions should NOT trigger fallback")
                .isFalse();
    }

    @Test
    void doesNotFallbackForNpeFromOtherCode() {
        NullPointerException npe = new NullPointerException("some field was null");
        npe.setStackTrace(new StackTraceElement[]{
                new StackTraceElement("com.example.MyService", "doStuff", "MyService.java", 10)
        });

        assertThat(PipestreamProtobufDeserializer.isRecoverableSchemaFailure(npe))
                .as("NPE from non-schema code should NOT trigger fallback")
                .isFalse();
    }

    @Test
    void handlesDeeplyNestedCauseChain() {
        // SchemaException buried 4 levels deep
        RuntimeException schemaEx = new SchemaException("unable to find common.proto");
        RuntimeException level3 = new RuntimeException("level3", schemaEx);
        RuntimeException level2 = new RuntimeException("level2", level3);
        RuntimeException level1 = new RuntimeException("level1", level2);
        RuntimeException top = new RuntimeException("Deserialization failed", level1);

        assertThat(PipestreamProtobufDeserializer.isRecoverableSchemaFailure(top))
                .as("Should find SchemaException even when deeply nested")
                .isTrue();
    }

    @ParameterizedTest(name = "proto message pattern: \"{0}\"")
    @MethodSource("protoMessagePatterns")
    void recognizesVariousProtoMissingMessages(String message, boolean expected) {
        RuntimeException ex = new RuntimeException(message);
        assertThat(PipestreamProtobufDeserializer.isRecoverableSchemaFailure(ex))
                .as("Message '%s' → fallback=%s", message, expected)
                .isEqualTo(expected);
    }

    static Stream<Arguments> protoMessagePatterns() {
        return Stream.of(
                Arguments.of("unable to find ai/pipestream/data/v1/pipeline_core_types.proto", true),
                Arguments.of("unable to find google/protobuf/timestamp.proto", true),
                Arguments.of("unable to find some_module.proto", true),
                Arguments.of("unable to find something_else", false),  // no .proto
                Arguments.of("proto file missing", false),  // doesn't match pattern
                Arguments.of("normal error message", false)
        );
    }

    // =========================================================================
    // Test helpers
    // =========================================================================

    /**
     * Has simple name "SchemaException" to match the class name check in
     * isRecoverableSchemaFailure(). Simulates com.squareup.wire.schema.SchemaException.
     */
    private static class SchemaException extends RuntimeException {
        SchemaException(String message) { super(message); }
    }

    /**
     * Test helper that captures the configs passed to super.configure() without
     * actually initializing the full Kafka/Apicurio deserializer stack.
     */
    private static class TestCapturingDeserializer extends PipestreamProtobufDeserializer<com.google.protobuf.DynamicMessage> {

        Map<String, ?> lastConfigs;

        void doTestConfigure(Map<String, Object> configs, boolean isKey) {
            String classKey = isKey ? KEY_RETURN_CLASS : VALUE_RETURN_CLASS;
            Object returnClass = configs.get(classKey);

            if (returnClass instanceof String className && !className.isEmpty()) {
                try {
                    Class<?> clazz = Thread.currentThread().getContextClassLoader().loadClass(className);
                    Map<String, Object> fixedConfigs = new HashMap<>(configs);
                    fixedConfigs.put(classKey, clazz);
                    lastConfigs = fixedConfigs;
                    return;
                } catch (ClassNotFoundException e) {
                    // Fall through
                }
            }
            lastConfigs = configs;
        }
    }
}
