package ai.pipestream.apicurio.registry.protobuf;

import ai.pipestream.apicurio.registry.protobuf.runtime.ProtobufChannelConfigSource;
import io.quarkus.runtime.annotations.Recorder;
import org.eclipse.microprofile.config.ConfigProvider;
import org.jboss.logging.Logger;

import java.util.Optional;
import java.util.Set;

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
     * @param incomingChannels the set of incoming channel names to configure
     * @param outgoingChannels the set of outgoing channel names to configure
     */
    public void configureProtobufChannels(Set<String> incomingChannels, Set<String> outgoingChannels) {
        ProtobufChannelConfigSource.setChannels(incomingChannels, outgoingChannels);
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
