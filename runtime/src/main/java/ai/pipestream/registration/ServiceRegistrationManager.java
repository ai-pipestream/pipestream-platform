package ai.pipestream.registration;

import ai.pipestream.platform.registration.v1.RegisterResponse;
import ai.pipestream.platform.registration.v1.EventType;
import ai.pipestream.registration.config.RegistrationConfig;
import ai.pipestream.registration.model.RegistrationState;
import ai.pipestream.registration.model.ServiceInfo;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
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
 * <p>Automatically registers the service on startup and deregisters gracefully on shutdown.
 * Health checking is handled by Consul via standard gRPC health checks.
 */
@ApplicationScoped
public class ServiceRegistrationManager {

    private static final Logger LOG = Logger.getLogger(ServiceRegistrationManager.class);
    private static final double DEFAULT_RETRY_JITTER = 0.2;

    private final RegistrationClient registrationClient;
    private final ServiceMetadataCollector metadataCollector;
    private final RegistrationConfig config;

    private final AtomicReference<String> serviceId = new AtomicReference<>();
    private final AtomicReference<RegistrationState> state = new AtomicReference<>(RegistrationState.UNREGISTERED);
    private volatile Cancellable registrationSubscription;

    @Inject
    public ServiceRegistrationManager(RegistrationClient registrationClient,
                                       ServiceMetadataCollector metadataCollector,
                                       RegistrationConfig config) {
        this.registrationClient = registrationClient;
        this.metadataCollector = metadataCollector;
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

        registrationSubscription = registrationClient.register(serviceInfo)
                .onItem().invoke(this::handleRegistrationResponse)
                .onFailure().invoke(t -> LOG.warnf(t, "Registration attempt failed"))
                .onFailure().retry()
                    .withBackOff(initialDelay, maxDelay)
                    .withJitter(DEFAULT_RETRY_JITTER)
                    .atMost(maxAttempts)
                .subscribe().with(
                        response -> LOG.debugf("Registration update received: %s", response.getEvent().getEventType()),
                        failure -> {
                            LOG.errorf(failure, "Registration failed after %d attempts", maxAttempts);
                            state.set(RegistrationState.FAILED);
                        },
                        () -> LOG.info("Registration stream completed")
                );
    }

    private void handleRegistrationResponse(RegisterResponse response) {
        var event = response.getEvent();
        LOG.infof("Registration event: type=%s, message=%s", event.getEventType(), event.getMessage());

        if (event.getEventType() == EventType.EVENT_TYPE_COMPLETED) {
            String newServiceId = event.getServiceId();
            serviceId.set(newServiceId);
            state.set(RegistrationState.REGISTERED);
            LOG.infof("Service registered successfully with ID: %s", newServiceId);
        } else if (event.getEventType() == EventType.EVENT_TYPE_FAILED) {
            LOG.errorf("Registration failed: %s", event.getErrorDetail());
            state.set(RegistrationState.FAILED);
        }
    }

    private void deregister(String serviceId) {
        state.set(RegistrationState.DEREGISTERING);

        try {
            ServiceInfo info = metadataCollector.collect();
            LOG.infof("Deregistering: %s at %s:%d",
                    info.getName(), info.getAdvertisedHost(), info.getAdvertisedPort());

            registrationClient.unregister(info.getName(), info.getAdvertisedHost(), info.getAdvertisedPort())
                    .await().atMost(Duration.ofSeconds(10));

            state.set(RegistrationState.DEREGISTERED);
            LOG.info("Service deregistered successfully");
        } catch (Exception e) {
            LOG.warnf(e, "Failed to deregister service");
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
