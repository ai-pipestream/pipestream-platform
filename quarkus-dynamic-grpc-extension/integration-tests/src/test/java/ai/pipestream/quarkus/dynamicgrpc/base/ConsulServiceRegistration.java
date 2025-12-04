package ai.pipestream.quarkus.dynamicgrpc.base;

import org.kiwiproject.consul.AgentClient;
import org.kiwiproject.consul.Consul;
import org.kiwiproject.consul.model.agent.ImmutableRegistration;
import org.kiwiproject.consul.model.agent.Registration;
import org.jboss.logging.Logger;

import java.util.List;

/**
 * Helper for registering and deregistering test gRPC services in Consul.
 * <p>
 * Used by integration tests to simulate real service discovery flows without mocks.
 * </p>
 */
public class ConsulServiceRegistration {

    private static final Logger LOG = Logger.getLogger(ConsulServiceRegistration.class);

    // Use the kiwi-provided Consul client (Orbitz under the hood)
    private final AgentClient consulClient;

    /**
     * Creates a registration helper bound to a particular Consul agent.
     *
     * @param consulHost the Consul agent host
     * @param consulPort the Consul agent HTTP port
     */
    public ConsulServiceRegistration(String consulHost, int consulPort) {
        // Kiwi Consul client pulls in Orbitz's com.orbitz.consul underneath
        // Build a Consul instance pointing to the given agent and get the AgentClient
        Consul consul = Consul.builder()
                .withUrl("http://" + consulHost + ":" + consulPort)
                .build();
        this.consulClient = consul.agentClient();
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
        Registration service = ImmutableRegistration.builder()
                .id(serviceId)
                .name(serviceName)
                .address(host)
                .port(port)
                .tags(List.of("grpc"))
                .build();

        consulClient.register(service);
        LOG.infof("Registered service: %s at %s:%d", serviceName, host, port);
    }

    /**
     * Deregisters a previously registered service instance from Consul.
     *
     * @param serviceId the instance id to deregister
     */
    public void deregisterService(String serviceId) {
        consulClient.deregister(serviceId);
        LOG.infof("Deregistered service: %s", serviceId);
    }

    /**
     * Returns the underlying Consul client for advanced test operations.
     *
     * @return the Consul AgentClient
     */
    public AgentClient getConsulClient() {
        return consulClient;
    }
}
