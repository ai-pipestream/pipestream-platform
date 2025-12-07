package ai.pipestream.quarkus.dynamicgrpc;

import ai.pipestream.quarkus.dynamicgrpc.exception.DynamicGrpcException;
import ai.pipestream.quarkus.dynamicgrpc.exception.InvalidServiceNameException;
import ai.pipestream.quarkus.dynamicgrpc.exception.ServiceNotFoundException;
import ai.pipestream.quarkus.dynamicgrpc.metrics.DynamicGrpcMetrics;
import io.grpc.*;
import io.quarkus.grpc.MutinyStub;
import io.smallrye.mutiny.Uni;
import io.smallrye.stork.Stork;
import io.smallrye.stork.api.Service;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

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

        LOG.debugf("Getting channel for service: %s", serviceName);

        return serviceDiscoveryManager.ensureServiceDefined(serviceName)
                .chain(ignored -> {
                    LOG.debugf("Step 1: Service %s defined", serviceName);
                    return serviceDiscoveryManager.getServiceInstances(serviceName);
                })
                .chain(instances -> {
                    LOG.debugf("Step 2: Got %d instances for %s", instances.size(), serviceName);
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

    /**
     * NameResolverProvider that uses SmallRye Stork to resolve service instances
     * for a logical service name into gRPC address groups.
     */
    public static class StorkNameResolverProvider extends NameResolverProvider {
        private final String serviceName;

        /**
         * Creates a new StorkNameResolverProvider.
         *
         * @param serviceName the logical service name to resolve
         */
        public StorkNameResolverProvider(String serviceName) {
            this.serviceName = serviceName;
        }

        @Override
        protected boolean isAvailable() {
            return true;
        }

        @Override
        protected int priority() {
            return 5;
        }

        /**
         * Creates a new resolver for the given target URI using the configured service name.
         *
         * @param targetUri the target URI passed by gRPC
         * @param args      resolver arguments provided by gRPC
         * @return a NameResolver backed by SmallRye Stork
         */
        @Override
        public NameResolver newNameResolver(URI targetUri, NameResolver.Args args) {
            return new StorkNameResolver(serviceName);
        }

        /**
         * Returns the default URI scheme used by this provider.
         *
         * @return the {@code stork} scheme
         */
        @Override
        public String getDefaultScheme() {
            return "stork";
        }
    }

    /**
     * NameResolver implementation that uses Stork for service instance resolution.
     */
    public static class StorkNameResolver extends NameResolver {
        private final String serviceName;
        private Listener2 listener;

        /**
         * Creates a new StorkNameResolver.
         *
         * @param serviceName the logical service name to resolve
         */
        public StorkNameResolver(String serviceName) {
            this.serviceName = serviceName;
        }

        /**
         * The logical authority equals the service name being resolved.
         *
         * @return the service name authority
         */
        @Override
        public String getServiceAuthority() {
            return serviceName;
        }

        @Override
        public void start(Listener2 listener) {
            this.listener = listener;
            resolve();
        }

        @Override
        public void refresh() {
            resolve();
        }

        /**
         * Performs a resolution round by querying Stork for current instances and reporting results
         * to the registered listener.
         */
        private void resolve() {
            try {
                Service service = Stork.getInstance().getService(serviceName);
                service.getInstances().onItem().invoke(instances -> {
                    if (instances.isEmpty()) {
                        Logger.getLogger(StorkNameResolver.class).warnf("No instances found for service: %s", serviceName);
                        listener.onError(io.grpc.Status.UNAVAILABLE.withDescription("No instances found for service " + serviceName));
                    } else {
                        List<EquivalentAddressGroup> addresses = instances.stream()
                                .map(i -> new EquivalentAddressGroup(new InetSocketAddress(i.getHost(), i.getPort())))
                                .collect(Collectors.toList());
                        listener.onResult(ResolutionResult.newBuilder()
                                .setAddressesOrError(StatusOr.fromValue(addresses))
                                .build());
                    }
                }).subscribe().asCompletionStage();
            } catch (Exception e) {
                Logger.getLogger(StorkNameResolver.class).errorf(e, "Failed to resolve service instances for %s", serviceName);
                listener.onError(io.grpc.Status.INTERNAL.withCause(e).withDescription("Failed to resolve instances"));
            }
        }

        @Override
        public void shutdown() {
            // Cleanup if needed
        }
    }
}
