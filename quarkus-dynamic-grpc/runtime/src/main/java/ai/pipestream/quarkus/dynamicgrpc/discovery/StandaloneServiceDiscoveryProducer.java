package ai.pipestream.quarkus.dynamicgrpc.discovery;

import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Producer for ServiceDiscovery when running the dynamic-grpc module standalone.
 * <p>
 * This producer provides a default ServiceDiscovery implementation when no other
 * implementation is available.
 * </p>
 * <p>
 * The DefaultBean annotation ensures this is only used when no other ServiceDiscovery
 * bean is available.
 * </p>
 */
@ApplicationScoped
public class StandaloneServiceDiscoveryProducer {

    /**
     * Default constructor for CDI.
     */
    public StandaloneServiceDiscoveryProducer() {
    }

    private static final Logger LOG = Logger.getLogger(StandaloneServiceDiscoveryProducer.class);

    @Inject
    @ServiceDiscoveryImpl(ServiceDiscoveryImpl.Type.CONSUL_DIRECT)
    DynamicConsulServiceDiscovery dynamicConsulServiceDiscovery;

    /**
     * Produces a default ServiceDiscovery implementation for standalone usage.
     *
     * When the dynamic-grpc runtime is used outside of a full Quarkus application (or when no other
     * ServiceDiscovery bean is provided), this method supplies a Consul-backed discovery
     * implementation using {@link DynamicConsulServiceDiscovery}.
     *
     * @return a default ServiceDiscovery implementation suitable for standalone environments
     */
    @Produces
    @DefaultBean
    @ApplicationScoped
    public ServiceDiscovery produceServiceDiscovery() {
        LOG.info("Producing default ServiceDiscovery for standalone dynamic-grpc module");
        return dynamicConsulServiceDiscovery;
    }
}
