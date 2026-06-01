package ai.pipestream.quarkus.dynamicgrpc;

import ai.pipestream.quarkus.dynamicgrpc.exception.DynamicGrpcException;
import ai.pipestream.quarkus.dynamicgrpc.exception.InvalidServiceNameException;
import ai.pipestream.quarkus.dynamicgrpc.metrics.DynamicGrpcMetrics;
import io.grpc.Channel;
import io.quarkus.grpc.MutinyStub;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.function.Function;

/**
 * Factory for creating Mutiny gRPC clients using dynamic service discovery.
 * <p>
 * This factory ensures the service is defined in Stork, discovers instances,
 * obtains a Channel from ChannelManager, and produces Mutiny stubs on demand.
 * </p>
 */
@ApplicationScoped
public class DynamicGrpcClientFactory implements GrpcClientFactory {

    /**
     * Default constructor for CDI frameworks.
     */
    public DynamicGrpcClientFactory() {
    }

    private static final Logger LOG = Logger.getLogger(DynamicGrpcClientFactory.class);

    @Inject
    ServiceDiscoveryManager serviceDiscoveryManager;

    @Inject
    ChannelManager channelManager;

    @Inject
    DynamicGrpcMetrics metrics;

    /**
     * {@inheritDoc}
     */
    @Override
    public <T extends MutinyStub> Uni<T> getClient(String serviceName, Function<Channel, T> stubCreator) {
        if (stubCreator == null) {
            return Uni.createFrom().failure(
                    new DynamicGrpcException("Stub creator function must not be null")
            );
        }

        return getChannel(serviceName)
                .map(channel -> {
                    try {
                        T stub = stubCreator.apply(channel);
                        // Record successful client creation
                        metrics.recordClientCreationSuccess(serviceName);
                        return stub;
                    } catch (Exception e) {
                        LOG.errorf(e, "Failed to create stub for service: %s", serviceName);
                        // Record exception
                        metrics.recordException(e.getClass().getSimpleName(), serviceName, "stub_creation");
                        metrics.recordClientCreationFailure(serviceName, e.getClass().getSimpleName());
                        throw new DynamicGrpcException("Failed to create gRPC stub for service: " + serviceName, e);
                    }
                })
                .onFailure().invoke(throwable -> {
                    // Record failed client creation if channel retrieval failed
                    if (!(throwable instanceof DynamicGrpcException)) {
                        metrics.recordClientCreationFailure(serviceName, throwable.getClass().getSimpleName());
                    }
                });
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <T> T getBlockingClient(String serviceName, Function<Channel, T> stubCreator) {
        if (stubCreator == null) {
            throw new DynamicGrpcException("Stub creator function must not be null");
        }
        // Resolve the channel synchronously. Virtual-thread callers tolerate
        // the block; it pins only a carrier briefly during Stork resolution
        // on first access (subsequent calls hit the Caffeine cache).
        try {
            Channel channel = getChannel(serviceName).await().indefinitely();
            T stub = stubCreator.apply(channel);
            metrics.recordClientCreationSuccess(serviceName);
            return stub;
        } catch (DynamicGrpcException e) {
            metrics.recordClientCreationFailure(serviceName, e.getClass().getSimpleName());
            throw e;
        } catch (RuntimeException e) {
            LOG.errorf(e, "Failed to create blocking stub for service: %s", serviceName);
            metrics.recordException(e.getClass().getSimpleName(), serviceName, "stub_creation");
            metrics.recordClientCreationFailure(serviceName, e.getClass().getSimpleName());
            throw new DynamicGrpcException("Failed to create blocking gRPC stub for service: " + serviceName, e);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <T> T getAsyncClient(String serviceName, Function<Channel, T> stubCreator) {
        if (stubCreator == null) {
            throw new DynamicGrpcException("Stub creator function must not be null");
        }
        // Same resolution path as getBlockingClient — the caller's stub type
        // (blocking vs async vs future) is purely a function of the
        // stubCreator reference. Channel acquisition is identical. Keeping
        // a distinct method makes caller intent explicit at the call site.
        try {
            Channel channel = getChannel(serviceName).await().indefinitely();
            T stub = stubCreator.apply(channel);
            metrics.recordClientCreationSuccess(serviceName);
            return stub;
        } catch (DynamicGrpcException e) {
            metrics.recordClientCreationFailure(serviceName, e.getClass().getSimpleName());
            throw e;
        } catch (RuntimeException e) {
            LOG.errorf(e, "Failed to create async stub for service: %s", serviceName);
            metrics.recordException(e.getClass().getSimpleName(), serviceName, "stub_creation");
            metrics.recordClientCreationFailure(serviceName, e.getClass().getSimpleName());
            throw new DynamicGrpcException("Failed to create async gRPC stub for service: " + serviceName, e);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Uni<Channel> getChannel(String serviceName) {
        // Validate service name
        if (serviceName == null) {
            InvalidServiceNameException ex = new InvalidServiceNameException(null, "Service name must not be null");
            metrics.recordException(ex.getClass().getSimpleName(), null, "validation");
            return Uni.createFrom().failure(ex);
        }
        if (serviceName.isBlank()) {
            InvalidServiceNameException ex = new InvalidServiceNameException(serviceName, "Service name must not be blank");
            metrics.recordException(ex.getClass().getSimpleName(), serviceName, "validation");
            return Uni.createFrom().failure(ex);
        }

        LOG.tracef("Getting channel for service: %s", serviceName);

        return serviceDiscoveryManager.ensureServiceDefined(serviceName)
                .chain(ignored -> {
                    LOG.tracef("Step 1: Service %s defined", serviceName);
                    return serviceDiscoveryManager.getServiceInstances(serviceName);
                })
                .chain(instances -> {
                    LOG.tracef("Step 2: Got %d instances for %s", instances.size(), serviceName);
                    return channelManager.getOrCreateChannel(serviceName, instances);
                });
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getActiveServiceCount() {
        return channelManager.getActiveServiceCount();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void evictChannel(String serviceName) {
        channelManager.evictChannel(serviceName);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getCacheStats() {
        return channelManager.getCacheStats();
    }

}
