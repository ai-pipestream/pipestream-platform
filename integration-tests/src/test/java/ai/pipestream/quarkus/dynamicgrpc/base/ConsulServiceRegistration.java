package ai.pipestream.quarkus.dynamicgrpc.base;

import com.ecwid.consul.v1.ConsulClient;
import org.jboss.logging.Logger;

/**
 * Helper for registering and deregistering test gRPC services in Consul.
 * <p>
 * Used by integration tests to simulate real service discovery flows without mocks.
 * </p>
 */
public class ConsulServiceRegistration {

    private static final Logger LOG = Logger.getLogger(ConsulServiceRegistration.class);

    private final ConsulClient consulClient;

    /**
     * Creates a registration helper bound to a particular Consul agent.
     *
     * @param consulHost the Consul agent host
     * @param consulPort the Consul agent HTTP port
     */
    public ConsulServiceRegistration(String consulHost, int consulPort) {
        this.consulClient = new ConsulClient(consulHost, consulPort);
    }

    /**
     * Registers a gRPC service instance in Consul.
     *
     * @param serviceName the logical Consul service name
     * @param serviceId   a unique identifier for the instance
     * @param host        instance host/IP
     * @param port        instance gRPC port
     */
    public void registerService(String serviceName, String serviceId, String host, int port) {
        com.ecwid.consul.v1.agent.model.NewService service = new com.ecwid.consul.v1.agent.model.NewService();
        service.setName(serviceName);
        service.setId(serviceId);
        service.setAddress(host);
        service.setPort(port);
        service.setTags(java.util.List.of("grpc"));

        consulClient.agentServiceRegister(service);
        LOG.infof("Registered service: %s at %s:%d", serviceName, host, port);
    }

    /**
     * Deregisters a previously registered service instance from Consul.
     *
     * @param serviceId the instance id to deregister
     */
    public void deregisterService(String serviceId) {
        consulClient.agentServiceDeregister(serviceId);
        LOG.infof("Deregistered service: %s", serviceId);
    }

    /**
     * Returns the underlying Consul client for advanced test operations.
     *
     * @return the Consul client
     */
    public ConsulClient getConsulClient() {
        return consulClient;
    }
}
