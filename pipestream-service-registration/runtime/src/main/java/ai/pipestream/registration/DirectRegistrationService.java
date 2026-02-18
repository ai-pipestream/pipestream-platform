package ai.pipestream.registration;

import ai.pipestream.registration.consul.ConsulHealthChecker;
import ai.pipestream.registration.consul.ConsulRegistrar;
import ai.pipestream.registration.model.RegistrationResult;
import ai.pipestream.registration.model.ServiceInfo;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Handles direct Consul registration without going through the platform-registration-service gRPC endpoint.
 * <p>
 * Flow: validate → consul register → wait healthy → done.
 * This handles Consul registration only — no Apicurio, Kafka, or DB enrichment.
 */
@ApplicationScoped
public class DirectRegistrationService {

    private static final Logger LOG = Logger.getLogger(DirectRegistrationService.class);

    private final ConsulRegistrar consulRegistrar;
    private final ConsulHealthChecker healthChecker;

    @Inject
    public DirectRegistrationService(ConsulRegistrar consulRegistrar, ConsulHealthChecker healthChecker) {
        this.consulRegistrar = consulRegistrar;
        this.healthChecker = healthChecker;
    }

    /**
     * Register a service directly with Consul and wait for it to become healthy.
     *
     * @param serviceInfo The service information to register
     * @return Uni of RegistrationResult indicating success or failure
     */
    public Uni<RegistrationResult> register(ServiceInfo serviceInfo) {
        // Validate
        String validationError = validate(serviceInfo);
        if (validationError != null) {
            LOG.errorf("Registration validation failed for %s: %s", serviceInfo.getName(), validationError);
            return Uni.createFrom().item(RegistrationResult.failure(serviceInfo.getName(), validationError));
        }

        String serviceId = ConsulRegistrar.generateServiceId(
                serviceInfo.getName(),
                serviceInfo.getAdvertisedHost(),
                serviceInfo.getAdvertisedPort());

        LOG.infof("Direct registration: %s as %s", serviceInfo.getName(), serviceId);

        // Register with Consul
        return consulRegistrar.registerService(serviceInfo, serviceId)
                .onItem().transformToUni(registered -> {
                    if (!registered) {
                        LOG.errorf("Failed to register %s with Consul", serviceId);
                        return Uni.createFrom().item(
                                RegistrationResult.failure(serviceInfo.getName(), "Consul registration failed for " + serviceId));
                    }

                    LOG.infof("Consul registration succeeded for %s, waiting for health check", serviceId);

                    // Wait for healthy
                    return healthChecker.waitForHealthy(serviceInfo.getName(), serviceId)
                            .onItem().transformToUni(healthy -> {
                                if (healthy) {
                                    LOG.infof("Direct registration complete: %s is healthy", serviceId);
                                    return Uni.createFrom().item(
                                            RegistrationResult.success(serviceId, serviceInfo.getName()));
                                }

                                // Unhealthy — rollback by unregistering from Consul
                                LOG.warnf("Service %s failed health check, rolling back Consul registration", serviceId);
                                return consulRegistrar.unregisterService(serviceId)
                                        .onItem().transform(unregistered -> {
                                            if (!unregistered) {
                                                LOG.warnf("Failed to rollback Consul registration for %s", serviceId);
                                            }
                                            return RegistrationResult.unhealthy(serviceId, serviceInfo.getName());
                                        });
                            });
                });
    }

    /**
     * Unregister a service from Consul.
     *
     * @param name The service name
     * @param host The advertised host
     * @param port The advertised port
     * @return Uni&lt;Boolean&gt; true if unregistration succeeded
     */
    public Uni<Boolean> unregister(String name, String host, int port) {
        String serviceId = ConsulRegistrar.generateServiceId(name, host, port);
        LOG.infof("Direct unregistration: %s", serviceId);
        return consulRegistrar.unregisterService(serviceId);
    }

    private String validate(ServiceInfo serviceInfo) {
        if (serviceInfo.getName() == null || serviceInfo.getName().isBlank()) {
            return "Service name is required";
        }
        if (serviceInfo.getAdvertisedHost() == null || serviceInfo.getAdvertisedHost().isBlank()) {
            return "Advertised host is required";
        }
        if (serviceInfo.getAdvertisedPort() <= 0) {
            return "Advertised port must be positive";
        }
        return null;
    }
}
