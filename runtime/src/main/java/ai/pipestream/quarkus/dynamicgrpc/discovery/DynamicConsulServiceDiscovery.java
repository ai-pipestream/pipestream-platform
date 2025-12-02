package ai.pipestream.quarkus.dynamicgrpc.discovery;

import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.vertx.UniHelper;
import io.smallrye.stork.api.LoadBalancer;
import io.smallrye.stork.api.Metadata;
import io.smallrye.stork.api.MetadataKey;
import io.vertx.ext.consul.ConsulClient;
import io.vertx.ext.consul.ConsulClientOptions;
import io.vertx.ext.consul.ServiceEntry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Dynamic Consul-based service discovery that uses Stork's LoadBalancer
 * for intelligent instance selection without requiring pre-configuration.
 * <p>
 * This class is typed as DynamicConsulServiceDiscovery only (not ServiceDiscovery) to avoid
 * ambiguous dependencies. It's made available as ServiceDiscovery through producers.
 * </p>
 */
@ApplicationScoped
@Typed(DynamicConsulServiceDiscovery.class)
@ServiceDiscoveryImpl(ServiceDiscoveryImpl.Type.CONSUL_DIRECT)
public class DynamicConsulServiceDiscovery implements ServiceDiscovery {

    /**
     * Creates a new DynamicConsulServiceDiscovery.
     * The Consul client is initialized in the PostConstruct lifecycle.
     */
    public DynamicConsulServiceDiscovery() {
    }

    private static final Logger LOG = Logger.getLogger(DynamicConsulServiceDiscovery.class);

    @Inject
    io.vertx.core.Vertx vertx;

    @ConfigProperty(name = "quarkus.dynamic-grpc.consul.host", defaultValue = "localhost")
    String consulHost;

    @ConfigProperty(name = "quarkus.dynamic-grpc.consul.port", defaultValue = "8500")
    int consulPort;

    private ConsulClient consulClient;

    private final LoadBalancer loadBalancer = new RandomLoadBalancer();

    /**
     * Initializes the Consul client using the configured host and port.
     * Invoked automatically by CDI when the bean is constructed.
     */
    @PostConstruct
    void init() {
        LOG.infof("Creating ConsulClient for service discovery on %s:%d", consulHost, consulPort);

        ConsulClientOptions options = new ConsulClientOptions()
                .setHost(consulHost)
                .setPort(consulPort);

        this.consulClient = ConsulClient.create(vertx, options);
        LOG.infof("ConsulClient connected to %s:%d", consulHost, consulPort);
    }

    /**
     * Closes the Consul client before the bean is destroyed.
     */
    @PreDestroy
    void cleanup() {
        if (consulClient != null) {
            LOG.info("Closing ConsulClient");
            consulClient.close();
        }
    }

    /**
     * Discovers a single healthy instance for the given service name using Consul, selecting the
     * instance via a {@link io.smallrye.stork.api.LoadBalancer}.
     *
     * @param serviceName the logical service name registered in Consul
     * @return a Uni emitting the selected service instance or failing if none found
     */
    @Override
    public Uni<io.smallrye.stork.api.ServiceInstance> discoverService(String serviceName) {
        LOG.debugf("Discovering service %s dynamically from Consul", serviceName);

        return findHealthyInstances(serviceName)
                .map(instances -> {
                    if (instances == null || instances.isEmpty()) {
                        throw new ServiceDiscoveryException(
                                "No healthy instances found for service: " + serviceName
                        );
                    }

                    List<io.smallrye.stork.api.ServiceInstance> storkInstances = new ArrayList<>();
                    long id = 0;
                    for (ServiceEntry entry : instances) {
                        storkInstances.add(new StorkServiceInstanceAdapter(
                                id++,
                                entry.getService().getAddress(),
                                entry.getService().getPort()
                        ));
                    }

                    io.smallrye.stork.api.ServiceInstance selected = loadBalancer.selectServiceInstance(storkInstances);

                    LOG.debugf("Selected instance for service %s: %s:%d (id=%d)",
                            serviceName, selected.getHost(), selected.getPort(), selected.getId());

                    return new ConsulServiceInstance(
                            String.valueOf(selected.getId()),
                            selected.getHost(),
                            selected.getPort(),
                            serviceName
                    );
                });
    }

    /**
     * Discovers all healthy instances for the given service name from Consul.
     *
     * @param serviceName the logical service name registered in Consul
     * @return a Uni emitting a list of available instances (possibly empty)
     */
    @Override
    public Uni<List<io.smallrye.stork.api.ServiceInstance>> discoverAllInstances(String serviceName) {
        LOG.debugf("Discovering all instances for service %s from Consul", serviceName);

        return findHealthyInstances(serviceName)
                .map(instances -> {
                    if (instances == null) {
                        return List.of();
                    }

                    return instances.stream()
                            .map(entry -> {
                                String host = entry.getService().getAddress();
                                int port = entry.getService().getPort();
                                String id = entry.getService().getId();
                                return (io.smallrye.stork.api.ServiceInstance)
                                        new ConsulServiceInstance(id, host, port, serviceName);
                            })
                            .collect(Collectors.toList());
                });
    }

    /**
     * Queries Consul for healthy nodes of the given service name.
     *
     * @param serviceName the logical Consul service name
     * @return a Uni emitting the raw Consul ServiceEntry list (possibly empty)
     * @throws ServiceDiscoveryException if the Consul client is not initialized
     */
    private Uni<List<ServiceEntry>> findHealthyInstances(String serviceName) {
        if (consulClient == null) {
            throw new ServiceDiscoveryException("ConsulClient not initialized. Check configuration.");
        }

        return UniHelper.toUni(consulClient.healthServiceNodes(serviceName, true))
                .map(serviceList -> {
                    if (serviceList == null || serviceList.getList() == null) {
                        LOG.warnf("No healthy nodes found for service '%s' in Consul", serviceName);
                        return List.<ServiceEntry>of();
                    }

                    LOG.debugf("Found %d healthy instances for service '%s'",
                            serviceList.getList().size(), serviceName);

                    return serviceList.getList();
                })
                .onFailure().invoke(error ->
                        LOG.errorf(error, "Failed to query Consul for service '%s'", serviceName)
                )
                .map(list -> (List<ServiceEntry>) list);
    }

    /**
     * Simple ServiceInstance implementation for Consul service entries.
     */
    private static class ConsulServiceInstance implements io.smallrye.stork.api.ServiceInstance {
        private final String id;
        private final String host;
        private final int port;
        private final String serviceName;

        ConsulServiceInstance(String id, String host, int port, String serviceName) {
            this.id = id;
            this.host = host;
            this.port = port;
            this.serviceName = serviceName;
        }

        @Override
        public long getId() {
            String numericId = id.replaceAll("[^0-9]", "");
            if (numericId.isEmpty()) {
                return 0L;
            }
            try {
                return Long.parseLong(numericId.substring(0, Math.min(8, numericId.length())));
            } catch (NumberFormatException e) {
                return 0L;
            }
        }

        @Override
        public String getHost() {
            return host;
        }

        @Override
        public int getPort() {
            return port;
        }

        @Override
        public boolean isSecure() {
            return false;
        }

        @Override
        public Optional<String> getPath() {
            return Optional.empty();
        }

        @Override
        public Metadata<? extends MetadataKey> getMetadata() {
            return Metadata.empty();
        }
    }

    /**
     * Adapter for Stork ServiceInstance.
     */
    private static class StorkServiceInstanceAdapter implements io.smallrye.stork.api.ServiceInstance {
        private final long id;
        private final String host;
        private final int port;

        StorkServiceInstanceAdapter(long id, String host, int port) {
            this.id = id;
            this.host = host;
            this.port = port;
        }

        @Override
        public long getId() {
            return id;
        }

        @Override
        public String getHost() {
            return host;
        }

        @Override
        public int getPort() {
            return port;
        }

        @Override
        public boolean isSecure() {
            return false;
        }

        @Override
        public Optional<String> getPath() {
            return Optional.empty();
        }

        @Override
        public Metadata<? extends MetadataKey> getMetadata() {
            return Metadata.empty();
        }
    }

    /**
     * Custom exception for service discovery failures.
     */
    public static class ServiceDiscoveryException extends RuntimeException {
        /**
         * Creates a new ServiceDiscoveryException with a message.
         *
         * @param message explanation of the discovery failure
         */
        public ServiceDiscoveryException(String message) {
            super(message);
        }

        /**
         * Creates a new ServiceDiscoveryException with a message and a cause.
         *
         * @param message explanation of the discovery failure
         * @param cause   the underlying cause
         */
        public ServiceDiscoveryException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
