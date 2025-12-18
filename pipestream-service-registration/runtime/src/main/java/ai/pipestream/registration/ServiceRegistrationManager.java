package ai.pipestream.registration;

import ai.pipestream.platform.registration.v1.RegisterResponse;
import ai.pipestream.platform.registration.v1.PlatformEventType;
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
import java.util.concurrent.atomic.AtomicReference;

/**
 * Manages the service registration lifecycle.
 *
 * <p>Automatically registers the service on startup and deregisters gracefully on shutdown.
 * Health checking is handled by Consul via standard gRPC health checks.
 */
@SuppressWarnings("unused")
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

        int maxAttempts = config.retry().maxAttempts();
        Duration initialDelay = config.retry().initialDelay();
        Duration maxDelay = config.retry().maxDelay();

        registrationSubscription = registrationClient.register(serviceInfo)
                .onItem().invoke(this::handleRegistrationResponse)
                .onFailure().invoke(t -> {
                    LOG.warnf(t, "Registration attempt failed");
                    // If we were REGISTERED and connection fails, reset for re-registration
                    if (state.get() == RegistrationState.REGISTERED && config.reRegistration().enabled()) {
                        LOG.warn("Connection lost after successful registration, will re-register");
                        handleConnectionLoss();
                    }
                })
                .onFailure().retry()
                    .withBackOff(initialDelay, maxDelay)
                    .withJitter(DEFAULT_RETRY_JITTER)
                    .atMost(maxAttempts)
                .subscribe().with(
                        response -> LOG.debugf("Registration update received: %s", response.getEvent().getEventType()),
                        failure -> {
                            RegistrationState currentState = state.get();
                            if (currentState == RegistrationState.REGISTERED && config.reRegistration().enabled()) {
                                // Connection lost after registration - trigger re-registration
                                LOG.warnf(failure, "Registration stream failed after successful registration, will re-register");
                                handleConnectionLoss();
                            } else {
                                LOG.errorf(failure, "Registration failed after %d attempts", maxAttempts);
                                state.set(RegistrationState.FAILED);
                            }
                        },
                        () -> {
                            // Stream completed normally
                            RegistrationState currentState = state.get();
                            // Don't treat completion as connection loss if we successfully registered
                            // The stream is expected to complete after COMPLETED event is sent by the server
                            if (currentState == RegistrationState.REGISTERED) {
                                LOG.debug("Registration stream completed normally after successful registration");
                                // This is expected behavior - the server closes the stream after sending COMPLETED
                                // Do NOT trigger re-registration here
                            } else {
                                LOG.info("Registration stream completed");
                            }
                        }
                );
    }

    private void handleRegistrationResponse(RegisterResponse response) {
        var event = response.getEvent();
        LOG.infof("Registration event: type=%s, message=%s", event.getEventType(), event.getMessage());

        if (event.getEventType() == PlatformEventType.PLATFORM_EVENT_TYPE_COMPLETED) {
            String newServiceId = event.getServiceId();
            serviceId.set(newServiceId);
            state.set(RegistrationState.REGISTERED);
            LOG.infof("Service registered successfully with ID: %s", newServiceId);
        } else if (event.getEventType() == PlatformEventType.PLATFORM_EVENT_TYPE_FAILED) {
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

    /**
     * Handles connection loss after successful registration.
     * Resets the channel and state, then triggers re-registration.
     */
    private void handleConnectionLoss() {
        RegistrationState currentState = state.get();
        if (currentState != RegistrationState.REGISTERED) {
            // Only handle connection loss if we were actually registered
            return;
        }

        LOG.info("Handling connection loss - resetting channel and state for re-registration");
        
        // Cancel current subscription
        if (registrationSubscription != null) {
            registrationSubscription.cancel();
            registrationSubscription = null;
        }

        // Reset channel to force re-discovery
        registrationClient.resetChannel();

        // Reset state to allow re-registration
        state.set(RegistrationState.UNREGISTERED);
        serviceId.set(null);

        // Schedule re-registration with a delay
        Duration reRegistrationDelay = config.retry().initialDelay();
        LOG.infof("Scheduling re-registration in %s", reRegistrationDelay);
        
        // Use a simple delay mechanism - in a real implementation, you might want
        // to use a scheduler, but for now we'll trigger it immediately with retry logic
        // The retry logic in registerWithRetry will handle the backoff
        registerWithRetry();
    }
}
