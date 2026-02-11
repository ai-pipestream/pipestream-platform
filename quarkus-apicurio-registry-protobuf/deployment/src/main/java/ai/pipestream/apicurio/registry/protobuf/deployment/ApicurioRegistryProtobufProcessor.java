package ai.pipestream.apicurio.registry.protobuf.deployment;

import ai.pipestream.apicurio.registry.protobuf.ApicurioRegistryProtobufRecorder;
import ai.pipestream.apicurio.registry.protobuf.ProtobufChannel;
import ai.pipestream.apicurio.registry.protobuf.ProtobufKafkaHelper;
import ai.pipestream.apicurio.registry.protobuf.UuidKeyExtractorRegistry;
import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.arc.deployment.AnnotationsTransformerBuildItem;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.ExecutionTime;
import io.quarkus.deployment.annotations.Record;
import io.quarkus.deployment.builditem.CombinedIndexBuildItem;
import io.quarkus.deployment.builditem.IndexDependencyBuildItem;
import io.quarkus.deployment.builditem.ExtensionSslNativeSupportBuildItem;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.RunTimeConfigurationDefaultBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ReflectiveClassBuildItem;
import jakarta.inject.Qualifier;
import org.jboss.jandex.*;
import org.jboss.logging.Logger;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Build-time processor for Apicurio Registry Protobuf extension.
 */
class ApicurioRegistryProtobufProcessor {

    private static final Logger LOGGER = Logger.getLogger(ApicurioRegistryProtobufProcessor.class);

    private static final String FEATURE = "apicurio-registry-protobuf";
    private static final String APICURIO_GROUP_ID = "io.apicurio";
    private static final String APICURIO_ARTIFACT_ID = "apicurio-registry-protobuf-serde-kafka";

    private static final DotName MESSAGE_LITE = DotName.createSimple("com.google.protobuf.MessageLite");
    private static final DotName GENERATED_MESSAGE = DotName.createSimple("com.google.protobuf.GeneratedMessage");
    private static final DotName RECORD = DotName.createSimple("io.smallrye.reactive.messaging.kafka.Record");
    private static final DotName UUID = DotName.createSimple("java.util.UUID");
    
    private static final DotName INCOMING = DotName.createSimple("org.eclipse.microprofile.reactive.messaging.Incoming");
    private static final DotName OUTGOING = DotName.createSimple("org.eclipse.microprofile.reactive.messaging.Outgoing");
    private static final DotName CHANNEL = DotName.createSimple("org.eclipse.microprofile.reactive.messaging.Channel");

    private static final DotName PROTOBUF_CHANNEL = DotName.createSimple("ai.pipestream.apicurio.registry.protobuf.ProtobufChannel");

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

    @BuildStep
    void indexDependencies(BuildProducer<IndexDependencyBuildItem> indexDependencies) {
        indexDependencies.produce(new IndexDependencyBuildItem(APICURIO_GROUP_ID, APICURIO_ARTIFACT_ID));
    }

    @BuildStep
    ExtensionSslNativeSupportBuildItem enableSslInNative() {
        return new ExtensionSslNativeSupportBuildItem(FEATURE);
    }

    @BuildStep
    AdditionalBeanBuildItem registerBeans() {
        return AdditionalBeanBuildItem.builder()
                .addBeanClasses(ProtobufKafkaHelper.class, ProtobufChannel.class, UuidKeyExtractorRegistry.class)
                .setUnremovable()
                .build();
    }
    
    @BuildStep
    AnnotationsTransformerBuildItem transformProtobufChannelQualifier() {
        return new AnnotationsTransformerBuildItem(new AnnotationTransformation() {
            @Override
            public boolean supports(AnnotationTarget.Kind kind) {
                return kind == AnnotationTarget.Kind.CLASS;
            }

            @Override
            public void apply(TransformationContext ctx) {
                if (ctx.declaration().asClass().name().equals(PROTOBUF_CHANNEL)) {
                    ctx.add(AnnotationInstance.create(DotName.createSimple(Qualifier.class.getName()), ctx.declaration(), List.of()));
                }
            }
        });
    }
    
    /**
     * Auto-detect Kafka channels using Protobuf types and configure serializer/deserializer.
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
        Map<String, String> configuredIncoming = new HashMap<>();
        Set<String> configuredOutgoing = new HashSet<>();

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
                        if (!configuredIncoming.containsKey(channelName)) {
                            String protobufClass = extractProtobufClassName(index, param.type());
                            configuredIncoming.put(channelName, protobufClass);
                            LOGGER.debugf("Auto-detected Protobuf type %s for @Incoming channel: %s", protobufClass, channelName);
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
        LOGGER.infof("Incoming Channels: %s", configuredIncoming);
        LOGGER.infof("Outgoing Channels: %s", configuredOutgoing);

        // Inject connector defaults via BuildItem (needed for DevServices to see at build time)
        for (String channelName : configuredIncoming.keySet()) {
            String prefix = "mp.messaging.incoming." + channelName + ".";
            defaults.produce(new RunTimeConfigurationDefaultBuildItem(prefix + "connector", "smallrye-kafka"));
        }
        for (String channelName : configuredOutgoing) {
            String prefix = "mp.messaging.outgoing." + channelName + ".";
            defaults.produce(new RunTimeConfigurationDefaultBuildItem(prefix + "connector", "smallrye-kafka"));
        }

        // Configure the high-priority ConfigSource via recorder at STATIC_INIT
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

    private boolean isProtobufType(IndexView index, Type type) {
        if (type == null) {
            return false;
        }

        // Handle parameterized types (e.g., Multi<TestRecord>, Record<K, V>)
        if (type.kind() == Type.Kind.PARAMETERIZED_TYPE) {
            ParameterizedType paramType = type.asParameterizedType();
            DotName rawTypeName = paramType.name();
            List<Type> args = paramType.arguments();
            
            // Special handling for Record<K, V> - check the VALUE (second argument)
            if (RECORD.equals(rawTypeName) && args.size() >= 2) {
                // Check if the VALUE is a Protobuf type
                boolean isValueProtobuf = isProtobufType(index, args.get(1));
                
                if (isValueProtobuf) {
                    // STRICT REQUIREMENT: Key must be UUID
                    Type keyType = args.getFirst();
                    if (!UUID.equals(keyType.name())) {
                        throw new ProtobufConfigurationException(
                            "Invalid Kafka Record Key type: " + keyType.name() + 
                            ". When using Protobuf values in Record<K, V>, the Key (K) MUST be java.util.UUID."
                        );
                    }
                    return true;
                }
                return false;
            }
            
            // For other parameterized types (e.g., Multi<TestRecord>), check first argument
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
     * Extract the concrete Protobuf class name from a type, unwrapping generic wrappers
     * like Message&lt;T&gt;, Multi&lt;T&gt;, Record&lt;K, V&gt;.
     */
    private String extractProtobufClassName(IndexView index, Type type) {
        if (type == null) {
            return null;
        }

        if (type.kind() == Type.Kind.PARAMETERIZED_TYPE) {
            ParameterizedType paramType = type.asParameterizedType();
            DotName rawTypeName = paramType.name();
            List<Type> args = paramType.arguments();

            // Record<K, V> - the value (second arg) is the protobuf type
            if (RECORD.equals(rawTypeName) && args.size() >= 2) {
                return extractProtobufClassName(index, args.get(1));
            }

            // Other wrappers (Message<T>, Multi<T>, etc.) - first arg
            if (!args.isEmpty()) {
                return extractProtobufClassName(index, args.getFirst());
            }
            return null;
        }

        if (type.kind() != Type.Kind.CLASS) {
            return null;
        }

        DotName typeName = type.name();
        // Don't return abstract base classes - we need concrete protobuf types
        if (MESSAGE_LITE.equals(typeName) || GENERATED_MESSAGE.equals(typeName)) {
            return null;
        }

        if (isProtobufType(index, type)) {
            return typeName.toString();
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
                "io.apicurio.registry.serde.strategy.RecordIdStrategy",
                "ai.pipestream.apicurio.registry.protobuf.runtime.PipestreamProtobufDeserializer"
        ).methods().fields().build());

        reflectiveClasses.produce(ReflectiveClassBuildItem.builder(
                "com.google.protobuf.GeneratedMessageV3",
                "com.google.protobuf.DynamicMessage",
                "com.google.protobuf.Descriptors$Descriptor",
                "com.google.protobuf.Descriptors$FileDescriptor"
        ).methods().fields().build());
    }
}
