package ai.pipestream.apicurio.registry.protobuf.runtime;

import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.config.spi.ConfigSource;
import org.jboss.logging.Logger;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * ConfigSource that provides Protobuf serializer/deserializer configuration
 * for detected Kafka channels.
 *
 * <p>
 * This extension enforces:
 * </p>
 * <ul>
 * <li><strong>UUID keys:</strong> via Kafka's built-in
 * {@code UUIDSerializer}/{@code UUIDDeserializer}.</li>
 * <li><strong>Protobuf values:</strong> via Apicurio Registry serde.</li>
 * </ul>
 *
 * <p>
 * The ordinal is set to 200 (lower than {@code application.properties} at 250)
 * to allow
 * users to override these defaults in their {@code application.properties}.
 * </p>
 *
 * <h2>Priority Chain</h2>
 * <p>
 * Configuration sources are loaded in the following order (higher ordinal
 * wins):
 * </p>
 * <ol>
 * <li>System properties: 400</li>
 * <li>Environment variables: 300</li>
 * <li>{@code application.properties}: 250</li>
 * <li><strong>This ConfigSource: 200</strong> (can be overridden)</li>
 * <li>Default values: ~100</li>
 * </ol>
 */
@SuppressWarnings("unused")
public class ProtobufChannelConfigSource implements ConfigSource {

    private static final Logger LOG = Logger.getLogger(ProtobufChannelConfigSource.class);

    // Value serializers (Protobuf via Apicurio Registry)
    private static final String PROTOBUF_SERIALIZER = "io.apicurio.registry.serde.protobuf.ProtobufKafkaSerializer";
    private static final String PROTOBUF_DESERIALIZER = "io.apicurio.registry.serde.protobuf.ProtobufKafkaDeserializer";

    // Key serializers (UUID - enforced by this extension)
    private static final String UUID_SERIALIZER = "org.apache.kafka.common.serialization.UUIDSerializer";
    private static final String UUID_DESERIALIZER = "org.apache.kafka.common.serialization.UUIDDeserializer";

    // These are set at static init time by the recorder
    private static final Map<String, String> incomingChannels = new HashMap<>();
    private static final Map<String, String> outgoingChannels = new HashMap<>();
    private static volatile boolean enabled = false;

    private final Map<String, String> properties = new HashMap<>();

    /**
     * Default constructor.
     */
    public ProtobufChannelConfigSource() {
        // The original code had a comment here, but the instruction replaces the
        // constructor body.
        // The instruction also implies a change to the superclass, which is not present
        // in the original.
        // Assuming the user wants to keep the ConfigSource interface and not extend a
        // specific class.
        // The ordinal is handled by getOrdinal() method.
        // The name is handled by getName() method.
    }

    /**
     * Registers an incoming channel for Protobuf configuration.
     *
     * @param channelName the name of the incoming channel
     */
    public static void registerIncomingChannel(String channelName) {
        incomingChannels.put(channelName, channelName);
    }

    /**
     * Registers an outgoing channel for Protobuf configuration.
     *
     * @param channelName the name of the outgoing channel
     */
    public static void registerOutgoingChannel(String channelName) {
        outgoingChannels.put(channelName, channelName);
    }

    /**
     * Enables or disables the config source.
     *
     * @param value true to enable, false to disable
     */
    public static void setEnabled(boolean value) {
        enabled = value;
    }

    /**
     * Sets the channels to be configured.
     *
     * @param incoming the set of incoming channel names
     * @param outgoing the set of outgoing channel names
     */
    public static void setChannels(Set<String> incoming, Set<String> outgoing) {
        incomingChannels.clear();
        for (String s : incoming) {
            incomingChannels.put(s, s);
        }
        outgoingChannels.clear();
        for (String s : outgoing) {
            outgoingChannels.put(s, s);
        }
        enabled = true;  // Enable the config source after channels are set
    }

    private void buildProperties() {
        if (!enabled || !properties.isEmpty()) {
            return;
        }

        // Configure incoming channels (UUID keys + Protobuf values)
        for (String channelName : incomingChannels.keySet()) {
            String prefix = "mp.messaging.incoming." + channelName + ".";
            properties.put(prefix + "connector", "smallrye-kafka");
            properties.put(prefix + "key.deserializer", UUID_DESERIALIZER);
            properties.put(prefix + "value.deserializer", PROTOBUF_DESERIALIZER);
            properties.put(prefix + "auto.offset.reset", "earliest");
        }

        // Configure outgoing channels (UUID keys + Protobuf values)
        for (String channelName : outgoingChannels.keySet()) {
            String prefix = "mp.messaging.outgoing." + channelName + ".";
            properties.put(prefix + "connector", "smallrye-kafka");
            properties.put(prefix + "key.serializer", UUID_SERIALIZER);
            properties.put(prefix + "value.serializer", PROTOBUF_SERIALIZER);
        }

        // Connector-level defaults for Apicurio
        if (!incomingChannels.isEmpty() || !outgoingChannels.isEmpty()) {
            properties.put("mp.messaging.connector.smallrye-kafka.apicurio.protobuf.derive.class", "true");
            properties.put("mp.messaging.connector.smallrye-kafka.apicurio.registry.auto-register", "true");
            properties.put("mp.messaging.connector.smallrye-kafka.apicurio.registry.artifact-resolver-strategy",
                    "io.apicurio.registry.serde.strategy.SimpleTopicIdStrategy");
            properties.put("mp.messaging.connector.smallrye-kafka.apicurio.registry.find-latest", "true");

            // Bridge apicurio.registry.url to mp.messaging.connector.smallrye-kafka.apicurio.registry.url
            // This allows users to configure a single property that works for both the Apicurio client
            // and the Kafka serializers, and enables DevServices to detect when a registry is already configured
            bridgeApicurioRegistryUrl();
        }
    }

    /**
     * Bridges the apicurio.registry.url property to the SmallRye Kafka connector property.
     * This ensures that when users set apicurio.registry.url (e.g., from Compose Dev Services),
     * the Kafka serializers also receive this configuration.
     */
    private void bridgeApicurioRegistryUrl() {
        try {
            Optional<String> registryUrl = ConfigProvider.getConfig()
                    .getOptionalValue("apicurio.registry.url", String.class);

            if (registryUrl.isPresent() && !registryUrl.get().isBlank()) {
                String connectorKey = "mp.messaging.connector.smallrye-kafka.apicurio.registry.url";
                // Only set if not already explicitly configured
                Optional<String> existingConnectorUrl = ConfigProvider.getConfig()
                        .getOptionalValue(connectorKey, String.class);

                if (existingConnectorUrl.isEmpty()) {
                    properties.put(connectorKey, registryUrl.get());
                    LOG.infof("Bridged apicurio.registry.url=%s to %s", registryUrl.get(), connectorKey);
                }
            }
        } catch (Exception e) {
            // Config may not be fully initialized yet during early bootstrap
            // Log this as a warning so the user knows why it might fail
            LOG.warn("Failed to bridge 'apicurio.registry.url' to 'mp.messaging.connector.smallrye-kafka.apicurio.registry.url'. " +
                    "If you are manually configuring the registry URL, please explicitly set 'mp.messaging.connector.smallrye-kafka.apicurio.registry.url' in your configuration.", e);
        }
    }

    @Override
    public Map<String, String> getProperties() {
        buildProperties();
        return properties;
    }

    @Override
    public Set<String> getPropertyNames() {
        buildProperties();
        return properties.keySet();
    }

    @Override
    public String getValue(String propertyName) {
        buildProperties();
        return properties.get(propertyName);
    }

    @Override
    public String getName() {
        return "ProtobufChannelConfigSource";
    }

    @Override
    public int getOrdinal() {
        // Lower than application.properties (250) so users can easily override
        // Still higher than most default configs to ensure Protobuf serializers are
        // used
        return 200;
    }
}
