package ai.pipestream.quarkus.dynamicgrpc.discovery;

import io.smallrye.mutiny.Uni;
import io.smallrye.stork.api.ServiceInstance;

import java.util.List;

/**
 * Interface for service discovery operations.
 */
public interface ServiceDiscovery {

    /**
     * Discover a single healthy instance of a service.
     *
     * @param serviceName The name of the service to discover
     * @return A Uni containing a selected service instance
     */
    Uni<ServiceInstance> discoverService(String serviceName);

    /**
     * Discover all healthy instances of a service.
     *
     * @param serviceName The name of the service to discover
     * @return A Uni containing all available service instances
     */
    Uni<List<ServiceInstance>> discoverAllInstances(String serviceName);
}
