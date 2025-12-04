package ai.pipestream.apicurio.registry.protobuf.deployment;

import io.quarkus.builder.item.SimpleBuildItem;

/**
 * <p>
 * Build item indicating whether the application defines any Protobuf-backed
 * messaging channels.
 * </p>
 */
public final class ProtobufChannelsBuildItem extends SimpleBuildItem {
    private final boolean hasChannels;

    /**
     * <p>
     * Creates a new build item.
     * </p>
     *
     * @param hasChannels <code>true</code> if at least one Protobuf channel is present; <code>false</code> otherwise
     */
    public ProtobufChannelsBuildItem(boolean hasChannels) {
        this.hasChannels = hasChannels;
    }

    /**
     * <p>
     * Returns whether the application contains any Protobuf channels.
     * </p>
     *
     * @return <code>true</code> if channels are present; <code>false</code> otherwise
     */
    public boolean hasChannels() {
        return hasChannels;
    }
}
