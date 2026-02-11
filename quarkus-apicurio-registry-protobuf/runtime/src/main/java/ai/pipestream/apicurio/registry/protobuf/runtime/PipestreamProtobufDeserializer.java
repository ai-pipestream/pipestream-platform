package ai.pipestream.apicurio.registry.protobuf.runtime;

import com.google.protobuf.Message;
import io.apicurio.registry.serde.protobuf.ProtobufKafkaDeserializer;
import org.apache.kafka.common.header.Headers;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
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

    private static final String VALUE_RETURN_CLASS = "apicurio.registry.deserializer.value.return-class";
    private static final String KEY_RETURN_CLASS = "apicurio.registry.deserializer.key.return-class";

    private Method fallbackParseMethod;

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
                return;
            } catch (ClassNotFoundException e) {
                // Fall through to default behavior
            }
        } else if (returnClass instanceof Class<?> clazz) {
            initFallbackParseMethod(clazz);
        }
        super.configure(configs, isKey);
    }

    @Override
    @SuppressWarnings("unchecked")
    public U deserialize(String topic, Headers headers, byte[] data) {
        try {
            return super.deserialize(topic, headers, data);
        } catch (RuntimeException e) {
            if (fallbackParseMethod != null && isSchemaResolutionFailure(e)) {
                return (U) parseFallback(data);
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
            return (Message) fallbackParseMethod.invoke(null, is);
        } catch (Exception ex) {
            throw new IllegalStateException("Fallback protobuf parsing failed", ex);
        }
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
