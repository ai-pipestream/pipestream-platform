package ai.pipestream.apicurio.registry.protobuf.deployment;

import ai.pipestream.apicurio.registry.protobuf.ApicurioRegistryProtobufRecorder;
import ai.pipestream.apicurio.registry.protobuf.ProtobufChannel;
import ai.pipestream.apicurio.registry.protobuf.ProtobufIncoming;
import ai.pipestream.apicurio.registry.protobuf.ProtobufOutgoing;
import ai.pipestream.apicurio.registry.protobuf.ProtobufKafkaHelper;
import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.arc.deployment.AnnotationsTransformerBuildItem;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.ExecutionTime;
import io.quarkus.deployment.annotations.Record;
import io.quarkus.deployment.builditem.CombinedIndexBuildItem;
import io.quarkus.deployment.builditem.ExtensionSslNativeSupportBuildItem;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.RunTimeConfigurationDefaultBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ReflectiveClassBuildItem;
import org.jboss.jandex.*;
import org.jboss.logging.Logger;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Build-time processor for Apicurio Registry Protobuf extension.
 *
 * <p>
 * This processor automatically configures Kafka channels for Protobuf message serialization and deserialization
 * by detecting Protobuf types in {@code @Incoming}, {@code @Outgoing}, and {@code @Channel} annotations.
 * </p>
 *
 * <h3>Key Features</h3>
 * <ul>
 *   <li><strong>Auto-Detection:</strong> Scans for methods and fields using Protobuf types (classes extending {@code MessageLite}).</li>
 *   <li><strong>Configuration Generation:</strong> Automatically sets the following properties for detected channels:
 *     <ul>
 *       <li>{@code value.serializer} or {@code value.deserializer} to Apicurio Protobuf Serde.</li>
 *       <li>{@code connector} to {@code smallrye-kafka} (if not already set).</li>
 *     </ul>
 *   </li>
 *   <li><strong>Native Image Support:</strong> Registers necessary Apicurio and Protobuf classes for reflection.</li>
 * </ul>
 *
 * <h3>Configuration</h3>
 * <p>
 * The extension relies on the following global properties being set in {@code application.properties}:
 * </p>
 * <ul>
 *   <li>{@code mp.messaging.connector.smallrye-kafka.apicurio.registry.url}</li>
 *   <li>{@code kafka.bootstrap.servers}</li>
 * </ul>
 *
 * @see ProtobufIncoming
 * @see ProtobufOutgoing
 * @see ProtobufChannel
 */
class ApicurioRegistryProtobufProcessor {

    private static final Logger LOGGER = Logger.getLogger(ApicurioRegistryProtobufProcessor.class);

    private static final String FEATURE = "apicurio-registry-protobuf";

    // Protobuf base types (MessageLite is the root interface, GeneratedMessage is the base class)
    private static final DotName MESSAGE_LITE = DotName.createSimple("com.google.protobuf.MessageLite");
    private static final DotName GENERATED_MESSAGE = DotName.createSimple("com.google.protobuf.GeneratedMessage");

    // Standard Reactive Messaging annotations
    private static final DotName INCOMING = DotName.createSimple("org.eclipse.microprofile.reactive.messaging.Incoming");
    private static final DotName OUTGOING = DotName.createSimple("org.eclipse.microprofile.reactive.messaging.Outgoing");
    private static final DotName CHANNEL = DotName.createSimple("org.eclipse.microprofile.reactive.messaging.Channel");

    // Our custom Protobuf annotations
    private static final DotName PROTOBUF_INCOMING = DotName.createSimple("ai.pipestream.apicurio.registry.protobuf.ProtobufIncoming");
    private static final DotName PROTOBUF_OUTGOING = DotName.createSimple("ai.pipestream.apicurio.registry.protobuf.ProtobufOutgoing");
    private static final DotName PROTOBUF_CHANNEL = DotName.createSimple("ai.pipestream.apicurio.registry.protobuf.ProtobufChannel");

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

    @BuildStep
    ExtensionSslNativeSupportBuildItem enableSslInNative() {
        return new ExtensionSslNativeSupportBuildItem(FEATURE);
    }

    /**
     * Register CDI beans provided by the extension.
     */
    @BuildStep
    AdditionalBeanBuildItem registerBeans() {
        return AdditionalBeanBuildItem.builder()
                .addBeanClasses(ProtobufKafkaHelper.class)
                .setUnremovable()
                .build();
    }

    /**
     * Transform @ProtobufIncoming, @ProtobufOutgoing, and @ProtobufChannel annotations
     * into standard SmallRye Reactive Messaging annotations.
     * <p>
     * This allows users to use our simplified annotations while SmallRye sees the standard ones.
     */
    @BuildStep
    AnnotationsTransformerBuildItem transformProtobufAnnotations() {
        return new AnnotationsTransformerBuildItem(new AnnotationTransformation() {
            @Override
            public boolean supports(AnnotationTarget.Kind kind) {
                return kind == AnnotationTarget.Kind.METHOD
                        || kind == AnnotationTarget.Kind.FIELD
                        || kind == AnnotationTarget.Kind.METHOD_PARAMETER;
            }

            @Override
            public void apply(TransformationContext ctx) {
                // Check for our custom annotations
                AnnotationInstance protobufIncoming = ctx.annotations().stream()
                        .filter(a -> a.name().equals(PROTOBUF_INCOMING))
                        .findFirst().orElse(null);
                AnnotationInstance protobufOutgoing = ctx.annotations().stream()
                        .filter(a -> a.name().equals(PROTOBUF_OUTGOING))
                        .findFirst().orElse(null);
                AnnotationInstance protobufChannel = ctx.annotations().stream()
                        .filter(a -> a.name().equals(PROTOBUF_CHANNEL))
                        .findFirst().orElse(null);

                // Transform @ProtobufIncoming -> @Incoming
                if (protobufIncoming != null) {
                    String channelName = protobufIncoming.value().asString();
                    ctx.remove(ann -> ann.name().equals(PROTOBUF_INCOMING));
                    ctx.add(AnnotationInstance.create(INCOMING, ctx.declaration(),
                            List.of(AnnotationValue.createStringValue("value", channelName))));
                    LOGGER.debugf("Transformed @ProtobufIncoming to @Incoming for channel: %s", channelName);
                }

                // Transform @ProtobufOutgoing -> @Outgoing
                if (protobufOutgoing != null) {
                    String channelName = protobufOutgoing.value().asString();
                    ctx.remove(ann -> ann.name().equals(PROTOBUF_OUTGOING));
                    ctx.add(AnnotationInstance.create(OUTGOING, ctx.declaration(),
                            List.of(AnnotationValue.createStringValue("value", channelName))));
                    LOGGER.debugf("Transformed @ProtobufOutgoing to @Outgoing for channel: %s", channelName);
                }

                // Transform @ProtobufChannel -> @Channel
                if (protobufChannel != null) {
                    String channelName = protobufChannel.value().asString();
                    ctx.remove(ann -> ann.name().equals(PROTOBUF_CHANNEL));
                    ctx.add(AnnotationInstance.create(CHANNEL, ctx.declaration(),
                            List.of(AnnotationValue.createStringValue("value", channelName))));
                    LOGGER.debugf("Transformed @ProtobufChannel to @Channel for channel: %s", channelName);
                }
            }
        });
    }

    /**
     * Auto-detect Kafka channels using Protobuf types and configure serializer/deserializer.
     * <p>
     * This scans for:
     * - @ProtobufIncoming/@ProtobufOutgoing/@ProtobufChannel (our annotations - always configured)
     * - @Incoming/@Outgoing/@Channel with Protobuf message types (backward compatible auto-detection)
     * <p>
     * Uses STATIC_INIT to configure the high-priority ConfigSource before config is read.
     */
    @SuppressWarnings("PointlessNullCheck")
    @BuildStep
    @Record(ExecutionTime.STATIC_INIT)
    void autoConfigureProtobufChannels(
            CombinedIndexBuildItem combinedIndex,
            ApicurioRegistryProtobufRecorder recorder,
            BuildProducer<RunTimeConfigurationDefaultBuildItem> defaults,
            BuildProducer<ProtobufChannelsBuildItem> channelsBuildItem) {

        IndexView index = combinedIndex.getIndex();
        Set<String> configuredIncoming = new HashSet<>();
        Set<String> configuredOutgoing = new HashSet<>();

        // === Scan our custom @ProtobufIncoming annotations (always configure) ===
        for (AnnotationInstance annotation : index.getAnnotations(PROTOBUF_INCOMING)) {
            String channelName = annotation.value().asString();
            if (configuredIncoming.add(channelName)) {
                LOGGER.debugf("Configuring @ProtobufIncoming channel: %s", channelName);
            }
        }

        // === Scan our custom @ProtobufOutgoing annotations (always configure) ===
        for (AnnotationInstance annotation : index.getAnnotations(PROTOBUF_OUTGOING)) {
            String channelName = annotation.value().asString();
            if (configuredOutgoing.add(channelName)) {
                LOGGER.debugf("Configuring @ProtobufOutgoing channel: %s", channelName);
            }
        }

        // === Scan our custom @ProtobufChannel annotations (always configure as outgoing) ===
        for (AnnotationInstance annotation : index.getAnnotations(PROTOBUF_CHANNEL)) {
            String channelName = annotation.value().asString();
            if (configuredOutgoing.add(channelName)) {
                LOGGER.debugf("Configuring @ProtobufChannel channel: %s", channelName);
            }
        }

        // === Backward compatibility: Scan standard @Incoming with Protobuf types ===
        for (AnnotationInstance annotation : index.getAnnotations(INCOMING)) {
            if (annotation.target().kind() == AnnotationTarget.Kind.METHOD) {
                MethodInfo method = annotation.target().asMethod();
                String channelName = annotation.value().asString();

                // Check method parameters for Protobuf types
                for (MethodParameterInfo param : method.parameters()) {
                    if (isProtobufType(index, param.type())) {
                        if (configuredIncoming.add(channelName)) {
                            LOGGER.debugf("Auto-detected Protobuf type for @Incoming channel: %s", channelName);
                        }
                        break;
                    }
                }
            }
        }

        // === Backward compatibility: Scan standard @Outgoing with Protobuf types ===
        for (AnnotationInstance annotation : index.getAnnotations(OUTGOING)) {
            if (annotation.target().kind() == AnnotationTarget.Kind.METHOD) {
                MethodInfo method = annotation.target().asMethod();
                String channelName = annotation.value().asString();

                // Check return type for Protobuf types
                if (isProtobufType(index, method.returnType())) {
                    if (configuredOutgoing.add(channelName)) {
                        LOGGER.debugf("Auto-detected Protobuf type for @Outgoing channel: %s", channelName);
                    }
                }
            }
        }

        // === Backward compatibility: Scan standard @Channel with Protobuf Emitter types ===
        for (AnnotationInstance annotation : index.getAnnotations(CHANNEL)) {
            String channelName = annotation.value().asString();
            AnnotationTarget target = annotation.target();

            if (target.kind() == AnnotationTarget.Kind.FIELD) {
                Type fieldType = target.asField().type();
                Type messageType = extractEmitterType(fieldType);
                if (messageType != null && isProtobufType(index, messageType)) {
                    if (configuredOutgoing.add(channelName)) {
                        LOGGER.debugf("Auto-detected Protobuf type for @Channel Emitter: %s", channelName);
                    }
                }
            } else if (target.kind() == AnnotationTarget.Kind.METHOD_PARAMETER) {
                MethodParameterInfo param = target.asMethodParameter();
                Type messageType = extractEmitterType(param.type());
                if (messageType != null && isProtobufType(index, messageType)) {
                    if (configuredOutgoing.add(channelName)) {
                        LOGGER.debugf("Auto-detected Protobuf type for @Channel parameter: %s", channelName);
                    }
                }
            }
        }

        LOGGER.infof("Configured %d incoming and %d outgoing Protobuf channels",
                configuredIncoming.size(), configuredOutgoing.size());

        // Inject connector defaults via BuildItem (needed for DevServices to see at build time)
        for (String channelName : configuredIncoming) {
            String prefix = "mp.messaging.incoming." + channelName + ".";
            defaults.produce(new RunTimeConfigurationDefaultBuildItem(prefix + "connector", "smallrye-kafka"));
        }
        for (String channelName : configuredOutgoing) {
            String prefix = "mp.messaging.outgoing." + channelName + ".";
            defaults.produce(new RunTimeConfigurationDefaultBuildItem(prefix + "connector", "smallrye-kafka"));
        }

        // Configure the high-priority ConfigSource via recorder at STATIC_INIT
        // This sets serializer/deserializer with high priority to override SmallRye defaults
        if (!configuredIncoming.isEmpty() || !configuredOutgoing.isEmpty()) {
            recorder.configureProtobufChannels(configuredIncoming, configuredOutgoing);
            channelsBuildItem.produce(new ProtobufChannelsBuildItem(true));
        } else {
            channelsBuildItem.produce(new ProtobufChannelsBuildItem(false));
        }
    }

    @BuildStep
    @Record(ExecutionTime.RUNTIME_INIT)
    void validateRuntimeConfig(ApicurioRegistryProtobufRecorder recorder, ProtobufChannelsBuildItem channels) {
        recorder.validateConfig(channels.hasChannels());
    }

    /**
     * Check if a type is a Protobuf message type (extends MessageLite).
     * Note: Only check MessageLite, NOT GeneratedMessageV3 as per user requirement.
     */
    private boolean isProtobufType(IndexView index, Type type) {
        if (type == null) {
            return false;
        }

        // Handle parameterized types (e.g., Multi<TestRecord>)
        if (type.kind() == Type.Kind.PARAMETERIZED_TYPE) {
            ParameterizedType paramType = type.asParameterizedType();
            List<Type> args = paramType.arguments();
            if (!args.isEmpty()) {
                return isProtobufType(index, args.getFirst());
            }
            return false;
        }

        if (type.kind() != Type.Kind.CLASS) {
            return false;
        }

        DotName typeName = type.name();

        // Direct match
        if (MESSAGE_LITE.equals(typeName)) {
            return true;
        }

        // Check if the class extends MessageLite
        ClassInfo classInfo = index.getClassByName(typeName);
        if (classInfo == null) {
            LOGGER.debugf("Class %s not found in Jandex index", typeName);
            return false;
        }

        // Check superclass hierarchy
        DotName superName = classInfo.superName();
        while (superName != null && !superName.equals(DotName.OBJECT_NAME)) {
            if (MESSAGE_LITE.equals(superName) || GENERATED_MESSAGE.equals(superName)) {
                return true;
            }
            ClassInfo superClass = index.getClassByName(superName);
            if (superClass == null) {
                break;
            }
            superName = superClass.superName();
        }

        // Check interfaces
        for (DotName iface : classInfo.interfaceNames()) {
            if (MESSAGE_LITE.equals(iface)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Extract the type argument from Emitter<T> or similar generic types.
     */
    private Type extractEmitterType(Type type) {
        if (type.kind() == Type.Kind.PARAMETERIZED_TYPE) {
            ParameterizedType paramType = type.asParameterizedType();
            List<Type> args = paramType.arguments();
            if (!args.isEmpty()) {
                return args.getFirst();
            }
        }
        return null;
    }

    /**
     * Register Apicurio and Protobuf classes for reflection (needed for native image).
     */
    @BuildStep
    void registerForReflection(BuildProducer<ReflectiveClassBuildItem> reflectiveClasses) {
        reflectiveClasses.produce(ReflectiveClassBuildItem.builder(
                "io.apicurio.registry.serde.protobuf.ProtobufSerializer",
                "io.apicurio.registry.serde.protobuf.ProtobufDeserializer",
                "io.apicurio.registry.serde.protobuf.ProtobufSerdeHeaders",
                "io.apicurio.registry.serde.strategy.SimpleTopicIdStrategy",
                "io.apicurio.registry.serde.strategy.TopicIdStrategy",
                "io.apicurio.registry.serde.strategy.RecordIdStrategy"
        ).methods().fields().build());

        reflectiveClasses.produce(ReflectiveClassBuildItem.builder(
                "com.google.protobuf.GeneratedMessageV3",
                "com.google.protobuf.DynamicMessage",
                "com.google.protobuf.Descriptors$Descriptor",
                "com.google.protobuf.Descriptors$FileDescriptor"
        ).methods().fields().build());
    }
}
