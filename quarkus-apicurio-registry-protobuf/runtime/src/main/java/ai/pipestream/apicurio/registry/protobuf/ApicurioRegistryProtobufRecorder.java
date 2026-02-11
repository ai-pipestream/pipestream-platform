package ai.pipestream.apicurio.registry.protobuf;

import ai.pipestream.apicurio.registry.protobuf.runtime.ProtobufChannelConfigSource;
import ai.pipestream.apicurio.registry.protobuf.runtime.ProtobufEmitterImpl;
import io.quarkus.arc.SyntheticCreationalContext;
import io.quarkus.runtime.annotations.Recorder;
import io.smallrye.reactive.messaging.MutinyEmitter;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.enterprise.inject.spi.CDI;
import org.eclipse.microprofile.config.ConfigProvider;
import org.jboss.logging.Logger;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Recorder for Apicurio Registry Protobuf configuration.
 *
 * <p>
 * This recorder runs at {@code STATIC_INIT} to configure the high-priority
 * {@code ConfigSource}
 * with the detected Protobuf channels. It ensures that the correct serializers
 * and deserializers
 * are applied before the application starts.
 * </p>
 */
@Recorder
public class ApicurioRegistryProtobufRecorder {

    private static final Logger LOG = Logger.getLogger(ApicurioRegistryProtobufRecorder.class);

    /**
     * Default constructor.
     */
    public ApicurioRegistryProtobufRecorder() {
    }

    /**
     * Called at STATIC_INIT to configure the Protobuf channels.
     * This sets up the high-priority ConfigSource with serializer/deserializer
     * config.
     *
     * @param incomingChannels map of incoming channel name to protobuf class FQCN (nullable value)
     * @param outgoingChannels the set of outgoing channel names to configure
     */
    public void configureProtobufChannels(Map<String, String> incomingChannels, Set<String> outgoingChannels) {
        ProtobufChannelConfigSource.setChannels(incomingChannels, outgoingChannels);
    }

    /**
     * Creates a synthetic bean for {@link ProtobufEmitter}.
     *
     * @param channelName the name of the channel
     * @param messageType the Protobuf message type
     * @return the bean creator
     */
    public Function<SyntheticCreationalContext<ProtobufEmitter<?>>, ProtobufEmitter<?>> createProtobufEmitter(String channelName, Class<?> messageType) {
        return context -> {
            BeanManager beanManager = CDI.current().getBeanManager();

            // Load the generated Keeper class
            String keeperClassName = "ai.pipestream.apicurio.registry.protobuf.deployment.ProtobufChannelKeeper";
            Class<?> keeperClass;
            try {
                keeperClass = Class.forName(keeperClassName, true, Thread.currentThread().getContextClassLoader());
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("ProtobufChannelKeeper class not found. Ensure the extension is correctly configured.", e);
            }

            // Lookup the Keeper bean
            Set<Bean<?>> keeperBeans = beanManager.getBeans(keeperClass);
            if (keeperBeans.isEmpty()) {
                throw new IllegalStateException("ProtobufChannelKeeper bean not found.");
            }
            Bean<?> keeperBean = beanManager.resolve(keeperBeans);
            Object keeperInstance = beanManager.getReference(keeperBean, keeperClass, beanManager.createCreationalContext(keeperBean));

            // Retrieve the specific emitter for the channel
            MutinyEmitter<?> emitter;
            try {
                emitter = (MutinyEmitter<?>) keeperClass.getMethod("getEmitter", String.class).invoke(keeperInstance, channelName);
            } catch (Exception e) {
                throw new RuntimeException("Failed to invoke getEmitter on ProtobufChannelKeeper", e);
            }

            if (emitter == null) {
                throw new IllegalStateException("ProtobufChannelKeeper returned null for channel '" + channelName + "'");
            }

            // Lookup the registry
            UuidKeyExtractorRegistry registry = (UuidKeyExtractorRegistry) beanManager.getReference(
                    beanManager.resolve(beanManager.getBeans(UuidKeyExtractorRegistry.class)),
                    UuidKeyExtractorRegistry.class,
                    beanManager.createCreationalContext(null)
            );

            // Create the wrapper
            @SuppressWarnings({"unchecked", "rawtypes"})
            ProtobufEmitterImpl<?> impl = new ProtobufEmitterImpl(emitter, registry, messageType);

            return impl;
        };
    }

    /**
     * Called at RUNTIME_INIT to validate that the configuration is correct.
     *
     * @param hasChannels true if the application has configured Protobuf channels
     */
    public void validateConfig(boolean hasChannels) {
        if (!hasChannels) {
            return;
        }

        Optional<String> connectorUrl = ConfigProvider.getConfig()
                .getOptionalValue("mp.messaging.connector.smallrye-kafka.apicurio.registry.url", String.class);

        if (connectorUrl.isPresent() && !connectorUrl.get().isBlank()) {
            return; // Configuration is valid
        }

        // Connector URL is missing. Check if the user tried to use the simple property.
        Optional<String> simpleUrl = ConfigProvider.getConfig()
                .getOptionalValue("apicurio.registry.url", String.class);

        if (simpleUrl.isPresent() && !simpleUrl.get().isBlank()) {
            // User set the simple URL, but it wasn't bridged. This is likely the issue.
            String msg = """
                
                ========================================================================================
                [Apicurio Registry Protobuf Extension] Configuration Error
                ========================================================================================
                The property 'apicurio.registry.url' is set to '%s', but it was not correctly bridged
                to the Kafka connector configuration.
                
                This usually happens when 'apicurio.registry.url' is defined in a way that prevents
                automatic bridging (e.g., manual Dev Services override).
                
                FIX: Explicitly set the following property in your application.properties:
                
                mp.messaging.connector.smallrye-kafka.apicurio.registry.url=${apicurio.registry.url}
                ========================================================================================
                """.formatted(simpleUrl.get());
            LOG.error(msg);
            throw new IllegalArgumentException(msg);
        }

        // Neither is set. This might be okay if DevServices starts, but if we are here at RUNTIME_INIT
        // and DevServices didn't start (or failed), we might have an issue.
        // However, we can't know for sure if DevServices failed or if it's just not ready yet.
        // But typically DevServices runs before RUNTIME_INIT.
        
        // If we are here, and no URL is set, SmallRye Kafka will likely fail later.
        // We can warn the user.
        LOG.warn("""
            
            [Apicurio Registry Protobuf Extension] No Apicurio Registry URL configured.
            Protobuf channels are detected, but 'mp.messaging.connector.smallrye-kafka.apicurio.registry.url' is not set.
            If Dev Services is disabled, the application will likely fail to start.
            """);
    }
}
