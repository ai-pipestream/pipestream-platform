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
 * Wrapper around Apicurio's {@link ProtobufKafkaDeserializer} that fixes two issues
 * in Quarkus:
 *
 * <h3>1. Classloading fix</h3>
 * <p>Apicurio's {@code AbstractConfig.getClass()} uses the library's own classloader
 * to load the {@code specificReturnClass}, which fails in Quarkus because application
 * protobuf classes aren't visible to the library classloader. This wrapper intercepts
 * {@code configure()} to pre-load the class using the TCCL (application classloader)
 * and replaces the String value with the actual {@code Class} object.</p>
 *
 * <h3>2. Schema resolution fallback</h3>
 * <p>Apicurio v3's {@code ProtobufSchemaParser.parseSchema()} throws NPE when
 * {@code FileDescriptorUtils.toDescriptor()} returns null for proto schemas with
 * unresolved dependencies (e.g. {@code google/protobuf/timestamp.proto}). When a
 * {@code specificReturnClass} is configured, this wrapper catches the failure and
 * falls back to direct protobuf parsing, bypassing schema resolution entirely.</p>
 */
public class PipestreamProtobufDeserializer<U extends Message> extends ProtobufKafkaDeserializer<U> {

    private static final Logger LOG = Logger.getLogger(PipestreamProtobufDeserializer.class);
    private static final String VALUE_RETURN_CLASS = "apicurio.registry.deserializer.value.return-class";
    private static final String KEY_RETURN_CLASS = "apicurio.registry.deserializer.key.return-class";
    private static final String APICURIO_PROBLEM_DETAILS_CLASS =
            "io.apicurio.registry.rest.client.models.ProblemDetails";
    private static final String DEBUG_RUN_ID = "pre-fix";

    private Method fallbackParseMethod;

    @Override
    public void configure(Map<String, ?> configs, boolean isKey) {
        String classKey = isKey ? KEY_RETURN_CLASS : VALUE_RETURN_CLASS;
        Object returnClass = configs.get(classKey);
        // #region agent log
        LOG.infof("DBG runId=%s hypothesisId=H1 location=PipestreamProtobufDeserializer.configure message=configure-called isKey=%s classKey=%s returnClassType=%s",
                DEBUG_RUN_ID, isKey, classKey, returnClass == null ? "null" : returnClass.getClass().getName());
        // #endregion

        if (returnClass instanceof String className && !className.isEmpty()) {
            try {
                Class<?> clazz = Thread.currentThread().getContextClassLoader().loadClass(className);
                Map<String, Object> fixedConfigs = new HashMap<>(configs);
                fixedConfigs.put(classKey, clazz);
                super.configure(fixedConfigs, isKey);
                initFallbackParseMethod(clazz);
                // #region agent log
                LOG.infof("DBG runId=%s hypothesisId=H1 location=PipestreamProtobufDeserializer.configure message=configured-with-tccl className=%s fallbackParseMethodReady=%s",
                        DEBUG_RUN_ID, className, fallbackParseMethod != null);
                // #endregion
                return;
            } catch (ClassNotFoundException e) {
                // Fall through to default behavior
                // #region agent log
                LOG.infof("DBG runId=%s hypothesisId=H1 location=PipestreamProtobufDeserializer.configure message=tccl-class-load-failed className=%s errorClass=%s error=%s",
                        DEBUG_RUN_ID, className, e.getClass().getName(), String.valueOf(e.getMessage()));
                // #endregion
            }
        } else if (returnClass instanceof Class<?> clazz) {
            initFallbackParseMethod(clazz);
        }
        super.configure(configs, isKey);
        // #region agent log
        LOG.infof("DBG runId=%s hypothesisId=H1 location=PipestreamProtobufDeserializer.configure message=configured-with-original-configs fallbackParseMethodReady=%s",
                DEBUG_RUN_ID, fallbackParseMethod != null);
        // #endregion
    }

    @Override
    @SuppressWarnings("unchecked")
    public U deserialize(String topic, Headers headers, byte[] data) {
        try {
            U result = super.deserialize(topic, headers, data);
            if (data != null && result == null) {
                // #region agent log
                LOG.infof("DBG runId=%s hypothesisId=H5 location=PipestreamProtobufDeserializer.deserialize message=primary-deserialize-returned-null topic=%s dataLength=%s",
                        DEBUG_RUN_ID, topic, data.length);
                // #endregion
                throw new IllegalStateException("Deserializer returned null for non-null payload");
            }
            return result;
        } catch (RuntimeException e) {
            boolean schemaResolutionFailure = isSchemaResolutionFailure(e);
            boolean missingContentIdProblem = hasMissingContentIdProblemDetails(e);
            boolean shouldFallback = fallbackParseMethod != null && (schemaResolutionFailure || missingContentIdProblem);
            // #region agent log
            LOG.infof("DBG runId=%s hypothesisId=H2 location=PipestreamProtobufDeserializer.deserialize message=deserialize-failed topic=%s dataLength=%s fallbackReady=%s schemaResolutionFailure=%s missingContentIdProblem=%s shouldFallback=%s errorClass=%s error=%s",
                    DEBUG_RUN_ID,
                    topic,
                    data == null ? -1 : data.length,
                    fallbackParseMethod != null,
                    schemaResolutionFailure,
                    missingContentIdProblem,
                    shouldFallback,
                    e.getClass().getName(),
                    String.valueOf(e.getMessage()));
            // #endregion
            if (shouldFallback) {
                // #region agent log
                LOG.infof("DBG runId=%s hypothesisId=H4 location=PipestreamProtobufDeserializer.deserialize message=fallback-branch-entered topic=%s reason=%s",
                        DEBUG_RUN_ID,
                        topic,
                        schemaResolutionFailure ? "schema-resolution-failure"
                                : "problem-details-missing-content-id");
                // #endregion
                try {
                    return (U) parseFallback(data);
                } catch (RuntimeException fallbackEx) {
                    // #region agent log
                    LOG.infof("DBG runId=%s hypothesisId=H4 location=PipestreamProtobufDeserializer.deserialize message=fallback-branch-failed topic=%s fallbackErrorClass=%s fallbackError=%s",
                            DEBUG_RUN_ID,
                            topic,
                            fallbackEx.getClass().getName(),
                            String.valueOf(fallbackEx.getMessage()));
                    // #endregion
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

    private static boolean isSchemaResolutionFailure(Throwable e) {
        // Walk the cause chain looking for NPE from ProtobufSchemaParser.parseSchema()
        Throwable cause = e;
        while (cause != null) {
            if (cause instanceof NullPointerException) {
                for (StackTraceElement ste : cause.getStackTrace()) {
                    if ("parseSchema".equals(ste.getMethodName())
                            && ste.getClassName().contains("ProtobufSchemaParser")) {
                        return true;
                    }
                }
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
            // #region agent log
            LOG.infof("DBG runId=%s hypothesisId=H3 location=PipestreamProtobufDeserializer.parseFallback message=parse-fallback-start dataLength=%s",
                    DEBUG_RUN_ID, data.length);
            // #endregion

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
                // #region agent log
                LOG.infof("DBG runId=%s hypothesisId=H5 location=PipestreamProtobufDeserializer.parseFallback message=fallback-returned-null dataLength=%s",
                        DEBUG_RUN_ID, data.length);
                // #endregion
                throw new IllegalStateException("Fallback parser returned null for non-null payload");
            }
            // #region agent log
            LOG.infof("DBG runId=%s hypothesisId=H3 location=PipestreamProtobufDeserializer.parseFallback message=parse-fallback-success resultClass=%s",
                    DEBUG_RUN_ID, result == null ? "null" : result.getClass().getName());
            // #endregion
            return result;
        } catch (IllegalAccessException | InvocationTargetException | IOException ex) {
            // #region agent log
            LOG.infof("DBG runId=%s hypothesisId=H3 location=PipestreamProtobufDeserializer.parseFallback message=parse-fallback-failed errorClass=%s error=%s",
                    DEBUG_RUN_ID, ex.getClass().getName(), String.valueOf(ex.getMessage()));
            // #endregion
            throw new IllegalStateException("Fallback protobuf parsing failed", ex);
        }
    }

    private static boolean hasMissingContentIdProblemDetails(Throwable e) {
        Throwable cause = e;
        while (cause != null) {
            String className = cause.getClass().getName();
            String message = String.valueOf(cause.getMessage());
            String lowerMessage = message.toLowerCase();
            if (APICURIO_PROBLEM_DETAILS_CLASS.equals(className)
                    && lowerMessage.contains("content")
                    && (lowerMessage.contains("not found") || lowerMessage.contains("404"))) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
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
