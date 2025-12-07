package ai.pipestream.quarkus.dynamicgrpc;

import ai.pipestream.quarkus.dynamicgrpc.exception.ServiceDiscoveryException;
import ai.pipestream.quarkus.dynamicgrpc.exception.ServiceNotFoundException;
import ai.pipestream.quarkus.dynamicgrpc.metrics.DynamicGrpcMetrics;
import io.smallrye.mutiny.Uni;
import io.smallrye.stork.Stork;
import io.smallrye.stork.api.Service;
import io.smallrye.stork.api.ServiceDefinition;
import io.smallrye.stork.api.ServiceInstance;
import io.smallrye.stork.spi.config.SimpleServiceConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Manages dynamic definition of SmallRye Stork services backed by Consul discovery
 * and provides access to discovered {@link io.smallrye.stork.api.ServiceInstance} lists.
 * <p>
 * This component is used by {@link DynamicGrpcClientFactory} to ensure that a logical service
 * name is known to Stork and to obtain service instances discovered from Consul. It supports
 * per-service overrides of the Consul application name via the
 * {@code quarkus.dynamic-grpc.consul.application-name.<service>} configuration key.
 * </p>
 */
@ApplicationScoped
public class ServiceDiscoveryManager {

    /**
     * Default constructor for CDI frameworks.
     */
    public ServiceDiscoveryManager() {
    }

    private static final Logger LOG = Logger.getLogger(ServiceDiscoveryManager.class);

    @Inject
    DynamicGrpcMetrics metrics;

    /**
     * Consul agent host used for service discovery.
     */
    @ConfigProperty(name = "quarkus.dynamic-grpc.consul.host", defaultValue = "localhost")
    String consulHost;

    /**
     * Consul agent port used for service discovery.
     */
    @ConfigProperty(name = "quarkus.dynamic-grpc.consul.port", defaultValue = "8500")
    String consulPort;

    /**
     * Stork refresh period for pulling Consul updates (format supported by Stork, e.g. {@code 10s}).
     */
    @ConfigProperty(name = "quarkus.dynamic-grpc.consul.refresh-period", defaultValue = "10s")
    String consulRefreshPeriod;

    /**
     * Whether Consul health checks should be taken into account by discovery.
     */
    @ConfigProperty(name = "quarkus.dynamic-grpc.consul.use-health-checks", defaultValue = "false")
    boolean consulUseHealthChecks;

    /**
     * Ensures a service is defined in Stork for discovery using the same Consul application name.
     *
     * @param serviceName the logical service name to define and discover via Consul
     * @return a Uni that completes when the service is defined
     */
    public Uni<Void> ensureServiceDefined(String serviceName) {
        return ensureServiceDefinedFor(serviceName, serviceName);
    }

    /**
     * Ensures a service is defined in Stork under the given {@code storkServiceName} but discovers
     * Consul instances that are registered under {@code consulApplicationName}.
     * <p>
     * If the configuration key {@code quarkus.dynamic-grpc.consul.application-name.<storkServiceName>}
     * is present, it overrides {@code consulApplicationName} for discovery. Subsequent calls are
     * idempotent and will not re-define an already known Stork service.
     * </p>
     *
     * @param storkServiceName the logical service name as it will be known to Stork
     * @param consulApplicationName the Consul service name to query for instances when no override is provided
     * @return a Uni that completes when the service is defined or already present; fails if definition cannot be registered
     * @throws ServiceDiscoveryException if the service definition fails
     */
    public Uni<Void> ensureServiceDefinedFor(String storkServiceName, String consulApplicationName) {
        Optional<Service> existingService = Stork.getInstance().getServiceOptional(storkServiceName);
        if (existingService.isPresent()) {
            LOG.debugf("Service %s already defined in Stork", storkServiceName);
            return Uni.createFrom().voidItem();
        }

        LOG.infof("Defining new Stork service for dynamic discovery: %s (consul application: %s)", storkServiceName, consulApplicationName);

        Map<String, String> consulParams = new HashMap<>();
        consulParams.put("consul-host", consulHost);
        consulParams.put("consul-port", consulPort);
        consulParams.put("refresh-period", consulRefreshPeriod);
        consulParams.put("use-health-checks", String.valueOf(consulUseHealthChecks));

        final Config config = ConfigProvider.getConfig();
        final String overrideKey = "quarkus.dynamic-grpc.consul.application-name." + storkServiceName;
        final String applicationToDiscover = config.getOptionalValue(overrideKey, String.class)
                .orElse(consulApplicationName);
        consulParams.put("application", applicationToDiscover);

        var consulConfig = new SimpleServiceConfig.SimpleServiceDiscoveryConfig("consul", consulParams);
        ServiceDefinition definition = ServiceDefinition.of(consulConfig);

        try {
            Stork.getInstance().defineIfAbsent(storkServiceName, definition);
            LOG.infof("Successfully defined Stork service: %s with Consul discovery", storkServiceName);

            LOG.debugf("Stork will look for Consul service named: %s (overrideKey=%s, use-health-checks=%s)",
                    applicationToDiscover, overrideKey, String.valueOf(consulUseHealthChecks));

            return Uni.createFrom().voidItem();
        } catch (Exception e) {
            LOG.errorf(e, "Failed to define Stork service: %s", storkServiceName);
            metrics.recordException(e.getClass().getSimpleName(), storkServiceName, "service_definition");
            return Uni.createFrom().failure(
                    new ServiceDiscoveryException(storkServiceName, "Failed to define service in Stork", e)
            );
        }
    }

    /**
     * Gets service instances for a given service name from Stork.
     *
     * @param serviceName the service name to look up (must have been defined in Stork)
     * @return a Uni emitting the list of service instances; fails if the service is unknown or an error occurs
     * @throws ServiceNotFoundException if the service is not found
     * @throws ServiceDiscoveryException if service discovery fails
     */
    public Uni<List<ServiceInstance>> getServiceInstances(String serviceName) {
        try {
            Service service = Stork.getInstance().getService(serviceName);
            if (service == null) {
                metrics.recordServiceDiscovery(serviceName, false, 0);
                metrics.recordException(ServiceNotFoundException.class.getSimpleName(), serviceName, "discovery");
                return Uni.createFrom().failure(
                        new ServiceNotFoundException(serviceName)
                );
            }
            return service.getInstances()
                    .onItem().transform(instances -> {
                        if (instances.isEmpty()) {
                            LOG.warnf("No instances found for service: %s", serviceName);
                            metrics.recordServiceDiscovery(serviceName, true, 0);
                        } else {
                            LOG.debugf("Found %d instances for service: %s", instances.size(), serviceName);
                            metrics.recordServiceDiscovery(serviceName, true, instances.size());
                        }
                        return instances;
                    })
                    .onFailure().transform(e -> {
                        LOG.errorf(e, "Failed to discover instances for service: %s", serviceName);
                        metrics.recordServiceDiscovery(serviceName, false, 0);
                        metrics.recordException(e.getClass().getSimpleName(), serviceName, "discovery");
                        return new ServiceDiscoveryException(serviceName, "Failed to retrieve service instances", e);
                    });
        } catch (IllegalStateException e) {
            // Service not registered in Stork
            LOG.errorf(e, "Service not found in Stork: %s", serviceName);
            metrics.recordServiceDiscovery(serviceName, false, 0);
            metrics.recordException(ServiceNotFoundException.class.getSimpleName(), serviceName, "discovery");
            return Uni.createFrom().failure(
                    new ServiceNotFoundException(serviceName, e)
            );
        } catch (Exception e) {
            LOG.errorf(e, "Unexpected error getting service instances for %s", serviceName);
            metrics.recordServiceDiscovery(serviceName, false, 0);
            metrics.recordException(e.getClass().getSimpleName(), serviceName, "discovery");
            return Uni.createFrom().failure(
                    new ServiceDiscoveryException(serviceName, "Unexpected discovery error", e)
            );
        }
    }
}
