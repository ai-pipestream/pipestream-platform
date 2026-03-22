package ai.pipestream.apicurio.registry.protobuf.runtime;

import com.google.protobuf.Message;
import io.apicurio.registry.serde.protobuf.ProtobufKafkaDeserializer;
import org.apache.kafka.common.header.Headers;
import org.jboss.logging.Logger;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * Wrapper around Apicurio's {@link ProtobufKafkaDeserializer} that provides
 * resilient deserialization with automatic fallback to direct protobuf parsing.
 *
 * <h3>1. Classloading fix</h3>
 * <p>Apicurio's {@code AbstractConfig.getClass()} uses the library's own classloader
 * to load the {@code specificReturnClass}, which fails in Quarkus because application
 * protobuf classes aren't visible to the library classloader. This wrapper intercepts
 * {@code configure()} to pre-load the class using the TCCL (application classloader)
 * and replaces the String value with the actual {@code Class} object.</p>
 *
 * <h3>2. Schema resolution fallback</h3>
 * <p>When Apicurio cannot resolve proto schemas (missing transitive imports, registry
 * unreachable, schema not yet registered), this deserializer falls back to direct
 * protobuf parsing using the configured {@code specificReturnClass}. This provides
 * resilience against:</p>
 * <ul>
 *   <li>Missing transitive proto imports in the registry (SchemaException)</li>
 *   <li>NPE from ProtobufSchemaParser when dependencies are unresolved</li>
 *   <li>Registry HTTP 404 for content IDs not yet propagated</li>
 *   <li>Registry downtime or network failures</li>
 * </ul>
 */
public class PipestreamProtobufDeserializer<U extends Message> extends ProtobufKafkaDeserializer<U> {

    private static final Logger LOG = Logger.getLogger(PipestreamProtobufDeserializer.class);
    private static final String VALUE_RETURN_CLASS = "apicurio.registry.deserializer.value.return-class";
    private static final String KEY_RETURN_CLASS = "apicurio.registry.deserializer.key.return-class";
    private static final String APICURIO_PROBLEM_DETAILS_CLASS =
            "io.apicurio.registry.rest.client.models.ProblemDetails";

    private Method fallbackParseMethod;
    private String configuredReturnClassName;

    @Override
    public void configure(Map<String, ?> configs, boolean isKey) {
        String classKey = isKey ? KEY_RETURN_CLASS : VALUE_RETURN_CLASS;
        Object returnClass = configs.get(classKey);

        if (returnClass instanceof String className && !className.isEmpty()) {
            try {
                Class<?> clazz = Thread.currentThread().getContextClassLoader().loadClass(className);
                Map<String, Object> fixedConfigs = new HashMap<>(configs);
                fixedConfigs.put(classKey, clazz);
                super.configure(fixedConfigs, isKey);
                initFallbackParseMethod(clazz);
                configuredReturnClassName = className;
                LOG.debugf("Configured with TCCL-loaded class: %s (fallback %s)",
                        className, fallbackParseMethod != null ? "ready" : "unavailable");
                return;
            } catch (ClassNotFoundException e) {
                LOG.warnf("Failed to load return class via TCCL: %s", returnClass);
            }
        } else if (returnClass instanceof Class<?> clazz) {
            initFallbackParseMethod(clazz);
            configuredReturnClassName = clazz.getName();
        }
        super.configure(configs, isKey);
    }

    @Override
    @SuppressWarnings("unchecked")
    public U deserialize(String topic, Headers headers, byte[] data) {
        try {
            U result = super.deserialize(topic, headers, data);
            if (data != null && result == null) {
                throw new IllegalStateException("Deserializer returned null for non-null payload");
            }
            return result;
        } catch (RuntimeException e) {
            if (fallbackParseMethod != null && isRecoverableSchemaFailure(e)) {
                LOG.warnf("Schema resolution failed for topic '%s' (%s: %s) — falling back to direct protobuf parsing with %s",
                        topic, e.getClass().getSimpleName(), rootCauseMessage(e), configuredReturnClassName);
                try {
                    return (U) parseFallback(data);
                } catch (RuntimeException fallbackEx) {
                    LOG.errorf(fallbackEx, "Fallback protobuf parsing also failed for topic '%s'", topic);
                    fallbackEx.addSuppressed(e);
                    throw fallbackEx;
                }
            }
            throw e;
        }
    }

    private void initFallbackParseMethod(Class<?> clazz) {
        try {
            fallbackParseMethod = clazz.getDeclaredMethod("parseFrom", InputStream.class);
        } catch (NoSuchMethodException e) {
            // Not a standard protobuf message class; fallback won't be available
        }
    }

    /**
     * Determines if the exception represents a recoverable schema resolution failure
     * where falling back to direct protobuf parsing is appropriate.
     *
     * Walks the entire cause chain looking for:
     * <ul>
     *   <li>SchemaException from wire-schema ("unable to find *.proto")</li>
     *   <li>NPE from ProtobufSchemaParser.parseSchema() (unresolved dependencies)</li>
     *   <li>Apicurio ProblemDetails with "not found" / 404 (content ID not in registry)</li>
     *   <li>Any exception with a message indicating missing proto files</li>
     *   <li>Connection/IO failures reaching the registry</li>
     * </ul>
     */
    static boolean isRecoverableSchemaFailure(Throwable e) {
        Throwable cause = e;
        while (cause != null) {
            // SchemaException from com.squareup.wire.schema (missing proto imports)
            if (cause.getClass().getSimpleName().equals("SchemaException")) {
                return true;
            }

            // NPE from Apicurio's ProtobufSchemaParser.parseSchema()
            if (cause instanceof NullPointerException) {
                for (StackTraceElement ste : cause.getStackTrace()) {
                    if ("parseSchema".equals(ste.getMethodName())
                            && ste.getClassName().contains("ProtobufSchemaParser")) {
                        return true;
                    }
                }
            }

            // Apicurio ProblemDetails with "content not found" (404)
            if (APICURIO_PROBLEM_DETAILS_CLASS.equals(cause.getClass().getName())) {
                if (containsNotFoundText(cause)) {
                    return true;
                }
            }

            // Generic: any exception mentioning missing .proto files
            String msg = cause.getMessage();
            if (msg != null && msg.contains(".proto") && msg.contains("unable to find")) {
                return true;
            }

            // Connection failures to the registry
            if (cause instanceof java.net.ConnectException
                    || cause instanceof java.net.SocketTimeoutException) {
                return true;
            }

            cause = cause.getCause();
        }
        return false;
    }

    /**
     * Parses protobuf data directly using the configured return class, skipping
     * the Apicurio wire-format prefix before the actual protobuf bytes.
     *
     * <p>In non-headers mode (default): {@code [0x00 magic][ID bytes][Ref][Data]}
     * <br>In headers mode: {@code [Ref][Data]}</p>
     */
    private Message parseFallback(byte[] data) {
        if (data == null) {
            throw new IllegalArgumentException("Cannot parse null payload");
        }
        if (fallbackParseMethod == null) {
            throw new IllegalStateException("Fallback parser is not initialized");
        }

        try {
            InputStream is = new ByteArrayInputStream(data);

            // Non-headers mode (default): skip magic byte + schema ID
            if (data.length > 0 && data[0] == 0x00) {
                //noinspection ResultOfMethodCallIgnored
                is.skip(1); // magic byte
                // Detect ID size: 4-byte (Default4ByteIdHandler) or 8-byte (Legacy8ByteIdHandler)
                byte[] idStart = new byte[4];
                if (is.read(idStart) == 4
                        && idStart[0] == 0 && idStart[1] == 0
                        && idStart[2] == 0 && idStart[3] == 0) {
                    //noinspection ResultOfMethodCallIgnored
                    is.skip(4); // 8-byte ID: skip remaining 4 bytes
                }
                // else: 4-byte ID already consumed
            }

            // Skip the Ref length-delimited message prefix written by Apicurio serializer
            skipDelimitedMessage(is);

            Message result = (Message) fallbackParseMethod.invoke(null, is);
            if (result == null) {
                throw new IllegalStateException("Fallback parser returned null for non-null payload");
            }
            LOG.debugf("Fallback parsing succeeded: %s", result.getClass().getSimpleName());
            return result;
        } catch (IllegalAccessException | InvocationTargetException | IOException ex) {
            throw new IllegalStateException("Fallback protobuf parsing failed", ex);
        }
    }

    /**
     * Checks ProblemDetails for "not found" text in getMessage(), getDetail(), or getTitle().
     * Uses reflection since ProblemDetails may not be on the compile classpath.
     */
    private static boolean containsNotFoundText(Throwable cause) {
        for (String methodName : new String[]{"getMessage", "getDetail", "getTitle"}) {
            try {
                Method m = cause.getClass().getMethod(methodName);
                Object result = m.invoke(cause);
                if (result instanceof String text) {
                    String lower = text.toLowerCase();
                    if (lower.contains("not found") || lower.contains("404")) {
                        return true;
                    }
                }
            } catch (Exception ignored) {
                // Method doesn't exist or failed — skip
            }
        }
        return false;
    }

    private static String rootCauseMessage(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getMessage();
    }

    private static void skipDelimitedMessage(InputStream is) throws IOException {
        int length = readRawVarint32(is);
        if (length > 0) {
            long remaining = length;
            while (remaining > 0) {
                long skipped = is.skip(remaining);
                if (skipped <= 0) {
                    if (is.read() < 0) break;
                    remaining--;
                } else {
                    remaining -= skipped;
                }
            }
        }
    }

    private static int readRawVarint32(InputStream is) throws IOException {
        int result = 0;
        int shift = 0;
        while (shift < 32) {
            int b = is.read();
            if (b < 0) return result;
            result |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) return result;
            shift += 7;
        }
        return result;
    }
}
