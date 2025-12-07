package ai.pipestream.quarkus.dynamicgrpc.discovery;

/**
 * Interface for producing ServiceDiscovery instances.
 * <p>
 * Implementations of this interface are responsible for creating and configuring
 * appropriate ServiceDiscovery instances based on the application's configuration.
 * </p>
 */
public interface ServiceDiscoveryProducer {

    /**
     * Produces a ServiceDiscovery instance based on the current configuration.
     *
     * @return A configured ServiceDiscovery instance suitable for the current environment
     */
    ServiceDiscovery produceServiceDiscovery();
}
