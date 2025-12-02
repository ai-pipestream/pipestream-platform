package ai.pipestream.registration;

import ai.pipestream.platform.registration.v1.RegisterServiceResponse;
import ai.pipestream.platform.registration.v1.RegistrationStatus;
import ai.pipestream.registration.config.RegistrationConfig;
import ai.pipestream.registration.model.RegistrationState;
import ai.pipestream.registration.model.ServiceInfo;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.subscription.Cancellable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Manages the service registration lifecycle.
 * 
 * <p>Automatically registers the service on startup, reports health periodically,
 * and deregisters gracefully on shutdown.
 */
@ApplicationScoped
public class ServiceRegistrationManager {

    private static final Logger LOG = Logger.getLogger(ServiceRegistrationManager.class);

    private final RegistrationClient registrationClient;
    private final ServiceMetadataCollector metadataCollector;
    private final HealthReporter healthReporter;
    private final RegistrationConfig config;

    private final AtomicReference<String> serviceId = new AtomicReference<>();
    private final AtomicReference<RegistrationState> state = new AtomicReference<>(RegistrationState.UNREGISTERED);
    private volatile Cancellable registrationSubscription;

    @Inject
    public ServiceRegistrationManager(RegistrationClient registrationClient,
                                       ServiceMetadataCollector metadataCollector,
                                       HealthReporter healthReporter,
                                       RegistrationConfig config) {
        this.registrationClient = registrationClient;
        this.metadataCollector = metadataCollector;
        this.healthReporter = healthReporter;
        this.config = config;
    }

    void onStart(@Observes StartupEvent ev) {
        if (!config.enabled()) {
            LOG.info("Service registration is disabled");
            return;
        }

        LOG.info("Starting service registration");
        registerWithRetry();
    }

    void onStop(@Observes ShutdownEvent ev) {
        if (!config.enabled()) {
            return;
        }

        LOG.info("Shutting down service registration");
        
        // Cancel any ongoing registration
        if (registrationSubscription != null) {
            registrationSubscription.cancel();
        }

        // Stop health reporting
        healthReporter.stopHealthReporting();

        // Deregister the service
        String currentServiceId = serviceId.get();
        if (currentServiceId != null && state.get() == RegistrationState.REGISTERED) {
            deregister(currentServiceId);
        }
    }

    private void registerWithRetry() {
        state.set(RegistrationState.REGISTERING);
        ServiceInfo serviceInfo = metadataCollector.collect();
        
        AtomicInteger attempts = new AtomicInteger(0);
        int maxAttempts = config.retry().maxAttempts();
        Duration initialDelay = config.retry().initialDelay();
        Duration maxDelay = config.retry().maxDelay();
        double multiplier = config.retry().multiplier();

        registrationSubscription = registrationClient.registerService(serviceInfo)
                .onItem().invoke(this::handleRegistrationResponse)
                .onFailure().invoke(t -> LOG.warnf(t, "Registration attempt failed"))
                .onFailure().retry()
                    .withBackOff(initialDelay, maxDelay)
                    .withJitter(0.2)
                    .atMost(maxAttempts)
                .subscribe().with(
                        response -> LOG.debugf("Registration update received: %s", response.getStatus()),
                        failure -> {
                            LOG.errorf(failure, "Registration failed after %d attempts", maxAttempts);
                            state.set(RegistrationState.FAILED);
                        },
                        () -> LOG.info("Registration stream completed")
                );
    }

    private void handleRegistrationResponse(RegisterServiceResponse response) {
        LOG.infof("Registration response: status=%s, serviceId=%s, message=%s",
                response.getStatus(), response.getServiceId(), response.getMessage());

        if (response.getStatus() == RegistrationStatus.REGISTRATION_STATUS_REGISTERED) {
            String newServiceId = response.getServiceId();
            serviceId.set(newServiceId);
            state.set(RegistrationState.REGISTERED);
            
            LOG.infof("Service registered successfully with ID: %s", newServiceId);
            
            // Start health reporting
            healthReporter.startHealthReporting(newServiceId);
        } else if (response.getStatus() == RegistrationStatus.REGISTRATION_STATUS_FAILED) {
            LOG.errorf("Registration failed: %s", response.getMessage());
            state.set(RegistrationState.FAILED);
        }
    }

    private void deregister(String serviceId) {
        state.set(RegistrationState.DEREGISTERING);
        
        try {
            LOG.infof("Deregistering service: %s", serviceId);
            
            registrationClient.unregisterService(serviceId)
                    .await().atMost(Duration.ofSeconds(10));
            
            state.set(RegistrationState.DEREGISTERED);
            LOG.info("Service deregistered successfully");
        } catch (Exception e) {
            LOG.warnf(e, "Failed to deregister service: %s", serviceId);
            // Still mark as deregistered since we're shutting down anyway
            state.set(RegistrationState.DEREGISTERED);
        }
    }

    /**
     * Returns the current registration state.
     */
    public RegistrationState getState() {
        return state.get();
    }

    /**
     * Returns the registered service ID, or null if not registered.
     */
    public String getServiceId() {
        return serviceId.get();
    }
}
