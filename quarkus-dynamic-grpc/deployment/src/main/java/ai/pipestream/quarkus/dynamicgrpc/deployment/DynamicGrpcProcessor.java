package ai.pipestream.quarkus.dynamicgrpc.deployment;

import ai.pipestream.quarkus.dynamicgrpc.ChannelManager;
import ai.pipestream.quarkus.dynamicgrpc.DynamicGrpcClientFactory;
import ai.pipestream.quarkus.dynamicgrpc.GrpcClientFactory;
import ai.pipestream.quarkus.dynamicgrpc.ServiceDiscoveryManager;
import ai.pipestream.quarkus.dynamicgrpc.discovery.DynamicConsulServiceDiscovery;
import ai.pipestream.quarkus.dynamicgrpc.discovery.RandomLoadBalancer;
import ai.pipestream.quarkus.dynamicgrpc.discovery.ServiceDiscovery;
import ai.pipestream.quarkus.dynamicgrpc.discovery.ServiceDiscoveryImpl;
import ai.pipestream.quarkus.dynamicgrpc.discovery.ServiceDiscoveryProducer;
import ai.pipestream.quarkus.dynamicgrpc.discovery.StandaloneServiceDiscoveryProducer;
import ai.pipestream.quarkus.dynamicgrpc.discovery.StandaloneVertxProducer;
import ai.pipestream.quarkus.dynamicgrpc.metrics.DynamicGrpcMetrics;
import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ReflectiveClassBuildItem;

/**
 * Quarkus deployment processor for the Dynamic gRPC extension.
 * <p>
 * This processor registers beans and prepares the extension for build-time processing.
 * </p>
 */
public class DynamicGrpcProcessor {

    private static final String FEATURE = "dynamic-grpc";

    /**
     * Registers the extension feature.
     *
     * @return the feature build item
     */
    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

    /**
     * Registers the main CDI beans for the extension.
     *
     * @return the additional beans build item
     */
    @BuildStep
    AdditionalBeanBuildItem registerBeans() {
        return AdditionalBeanBuildItem.builder()
                .addBeanClasses(
                        // Core components
                        GrpcClientFactory.class,
                        DynamicGrpcClientFactory.class,
                        ChannelManager.class,
                        ServiceDiscoveryManager.class,
                        // Configuration
                        ai.pipestream.quarkus.dynamicgrpc.config.DynamicGrpcTlsAdapter.class,
                        // Authentication
                        ai.pipestream.quarkus.dynamicgrpc.auth.AuthMetadataInterceptor.class,
                        // Metrics
                        DynamicGrpcMetrics.class,
                        // Discovery components
                        ServiceDiscovery.class,
                        ServiceDiscoveryImpl.class,
                        ServiceDiscoveryProducer.class,
                        DynamicConsulServiceDiscovery.class,
                        StandaloneServiceDiscoveryProducer.class,
                        StandaloneVertxProducer.class,
                        RandomLoadBalancer.class
                )
                .setUnremovable()
                .build();
    }

    /**
     * Registers classes for reflection in native mode.
     *
     * @return the reflective class build item
     */
    @BuildStep
    ReflectiveClassBuildItem registerReflection() {
        return ReflectiveClassBuildItem.builder(
                DynamicGrpcClientFactory.class,
                ChannelManager.class,
                ServiceDiscoveryManager.class,
                DynamicConsulServiceDiscovery.class,
                RandomLoadBalancer.class
        ).methods().fields().build();
    }
}
