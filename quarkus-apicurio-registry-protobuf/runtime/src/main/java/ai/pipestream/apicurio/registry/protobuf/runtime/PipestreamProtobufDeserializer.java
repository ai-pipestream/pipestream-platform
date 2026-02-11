package ai.pipestream.apicurio.registry.protobuf.runtime;

import com.google.protobuf.Message;
import io.apicurio.registry.serde.protobuf.ProtobufKafkaDeserializer;

import java.util.HashMap;
import java.util.Map;

/**
 * Wrapper around Apicurio's {@link ProtobufKafkaDeserializer} that fixes classloading
 * in Quarkus.
 *
 * <p>Apicurio's {@code AbstractConfig.getClass()} uses the library's own classloader
 * ({@code this.getClass().getClassLoader()}) to load the {@code specificReturnClass},
 * which fails in Quarkus because application protobuf classes aren't visible to the
 * library classloader. However, if the config value is already a {@code Class} object,
 * Apicurio returns it directly without classloading.</p>
 *
 * <p>This wrapper intercepts {@code configure()} to pre-load the class using the
 * Thread Context ClassLoader (TCCL), which in Quarkus is the application classloader,
 * and replaces the String value with the actual {@code Class} object before delegating
 * to the parent.</p>
 */
public class PipestreamProtobufDeserializer<U extends Message> extends ProtobufKafkaDeserializer<U> {

    private static final String VALUE_RETURN_CLASS = "apicurio.registry.deserializer.value.return-class";
    private static final String KEY_RETURN_CLASS = "apicurio.registry.deserializer.key.return-class";

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
                return;
            } catch (ClassNotFoundException e) {
                // Fall through to default behavior
            }
        }
        super.configure(configs, isKey);
    }
}
