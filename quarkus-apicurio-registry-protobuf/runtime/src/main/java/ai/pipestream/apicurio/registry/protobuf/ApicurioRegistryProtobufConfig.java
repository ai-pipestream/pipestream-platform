package ai.pipestream.apicurio.registry.protobuf;

import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

import java.util.Optional;

/**
 * Runtime configuration for the Apicurio Registry Protobuf extension.
 *
 * <p>
 * This configuration class defines properties that control the runtime behavior
 * of the
 * Protobuf serializer and deserializer, including artifact registration and
 * lookup strategies.
 * </p>
 */
@SuppressWarnings("unused")
@ConfigMapping(prefix = "quarkus.apicurio-registry.protobuf")
@ConfigRoot(phase = ConfigPhase.RUN_TIME)
public interface ApicurioRegistryProtobufConfig {

    /**
     * Whether to derive the Java class from the protobuf schema at runtime.
     *
     * <p>
     * When set to {@code true}, this avoids classloading issues by using schema
     * metadata
     * instead of requiring the class to be on the application classpath.
     * </p>
     *
     * <p>
     * Default is {@code true} to avoid Quarkus classloader visibility issues.
     * </p>
     *
     * @return true if class derivation is enabled, false otherwise
     */
    @WithDefault("true")
    boolean deriveClass();

    /**
     * Whether to automatically register artifacts with the registry.
     *
     * <p>
     * If set to {@code true}, the serializer will attempt to register the Protobuf
     * schema
     * in the Apicurio Registry if it does not already exist.
     * </p>
     *
     * @return true if auto-registration is enabled, false otherwise
     */
    @WithDefault("true")
    boolean autoRegister();

    /**
     * The artifact resolver strategy to use.
     *
     * <p>
     * Common values include:
     * </p>
     * <ul>
     * <li>{@code io.apicurio.registry.serde.strategy.TopicIdStrategy}</li>
     * <li>{@code io.apicurio.registry.serde.strategy.RecordIdStrategy}</li>
     * <li>{@code io.apicurio.registry.serde.strategy.SimpleTopicIdStrategy}
     * (Default)</li>
     * </ul>
     *
     * @return the artifact resolver strategy class name
     */
    @WithDefault("io.apicurio.registry.serde.strategy.SimpleTopicIdStrategy")
    String artifactResolverStrategy();

    /**
     * Whether to find the latest artifact version.
     *
     * <p>
     * If set to {@code true}, the deserializer will attempt to find the latest
     * version
     * of the artifact in the registry.
     * </p>
     *
     * @return true if finding the latest artifact is enabled, false otherwise
     */
    @WithDefault("true")
    boolean findLatest();

    /**
     * Optional explicit group ID for artifacts.
     *
     * <p>
     * If specified, this group ID will be used when registering or looking up
     * artifacts.
     * </p>
     *
     * @return an optional string containing the explicit group ID
     */
    Optional<String> explicitGroupId();
}
