package ai.pipestream.registration;

import ai.pipestream.platform.registration.v1.RegisterResponse;
import ai.pipestream.platform.registration.v1.PlatformEventType;
import ai.pipestream.registration.config.RegistrationConfig;
import ai.pipestream.registration.model.RegistrationResult;
import ai.pipestream.registration.model.RegistrationState;
import ai.pipestream.registration.model.ServiceInfo;
import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.smallrye.mutiny.subscription.Cancellable;
import io.vertx.mutiny.core.Vertx;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Manages the service registration lifecycle.
 *
 * <p>Supports two modes:
 * <ul>
 *   <li>{@code direct} (default) — registers directly with Consul via {@link DirectRegistrationService}</li>
 *   <li>{@code grpc} — uses the legacy gRPC streaming path via {@link RegistrationClient}</li>
 * </ul>
 */
@SuppressWarnings("unused")
@ApplicationScoped
public class ServiceRegistrationManager {

    private static final Logger LOG = Logger.getLogger(ServiceRegistrationManager.class);
    private static final double DEFAULT_RETRY_JITTER = 0.2;
    private static final String MODE_DIRECT = "direct";
    private static final String MODE_GRPC = "grpc";

    private final RegistrationClient registrationClient;
    private final DirectRegistrationService directRegistrationService;
    private final ServiceMetadataCollector metadataCollector;
    private final RegistrationConfig config;
    private final Vertx vertx;

    @ConfigProperty(name = "pipestream.consul.host", defaultValue = "localhost")
    String consulHost;

    @ConfigProperty(name = "pipestream.consul.port", defaultValue = "8500")
    int consulPort;

    private final AtomicReference<String> serviceId = new AtomicReference<>();
    private final AtomicReference<RegistrationState> state = new AtomicReference<>(RegistrationState.UNREGISTERED);
    private volatile Cancellable registrationSubscription;
    private final Object timeoutLock = new Object();
    private volatile Long requiredTimeoutTimerId;
    private volatile Long reRegistrationTimerId;

    @Inject
    public ServiceRegistrationManager(RegistrationClient registrationClient,
                                       DirectRegistrationService directRegistrationService,
                                       ServiceMetadataCollector metadataCollector,
                                       RegistrationConfig config,
                                       Vertx vertx) {
        this.registrationClient = registrationClient;
        this.directRegistrationService = directRegistrationService;
        this.metadataCollector = metadataCollector;
        this.config = config;
        this.vertx = vertx;
    }

    void onStart(@Observes StartupEvent ev) {
        if (!config.enabled()) {
            LOG.info("Service registration is disabled");
            return;
        }

        LOG.infof("Starting service registration (mode=%s)", config.mode());
        scheduleRequiredTimeout();
        registerWithRetry();
    }

    void onStop(@Observes ShutdownEvent ev) {
        if (!config.enabled()) {
            return;
        }

        LOG.info("Shutting down service registration");
        cancelRequiredTimeout();
        cancelReRegistrationTimer();

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

    private boolean isDirectMode() {
        return MODE_DIRECT.equalsIgnoreCase(config.mode());
    }

    private void registerWithRetry() {
        state.set(RegistrationState.REGISTERING);
        ServiceInfo serviceInfo = metadataCollector.collect();

        if (isDirectMode()) {
            registerDirectWithRetry(serviceInfo);
        } else {
            registerGrpcWithRetry(serviceInfo);
        }
    }

    /**
     * Direct mode: register with Consul via DirectRegistrationService with retry logic.
     */
    private void registerDirectWithRetry(ServiceInfo serviceInfo) {
        long maxAttempts = config.required() ? Long.MAX_VALUE : config.retry().maxAttempts();
        Duration initialDelay = config.retry().initialDelay();
        Duration maxDelay = config.retry().maxDelay();

        registrationSubscription = directRegistrationService.register(serviceInfo)
                .onItem().invoke(result -> handleDirectRegistrationResult(result))
                .onFailure().invoke(t -> LOG.warnf(t, "Direct registration attempt failed"))
                .onFailure().retry()
                    .withBackOff(initialDelay, maxDelay)
                    .withJitter(DEFAULT_RETRY_JITTER)
                    .atMost(maxAttempts)
                .subscribe().with(
                        result -> {
                            if (!result.healthy()) {
                                // Result was handled in invoke above, but if not healthy after retries,
                                // schedule re-registration if enabled
                                if (state.get() != RegistrationState.REGISTERED && config.reRegistration().enabled()) {
                                    LOG.warnf("Direct registration returned unhealthy, will retry in %s",
                                            config.reRegistration().interval());
                                    scheduleReRegistration();
                                }
                            }
                        },
                        failure -> {
                            if (config.reRegistration().enabled()) {
                                LOG.warnf(failure, "Direct registration failed after %d attempts, will retry in %s",
                                        maxAttempts, config.reRegistration().interval());
                                scheduleReRegistration();
                            } else {
                                LOG.errorf(failure, "Direct registration failed after %d attempts", maxAttempts);
                                state.set(RegistrationState.FAILED);
                            }
                        }
                );
    }

    private void handleDirectRegistrationResult(RegistrationResult result) {
        if (result.healthy()) {
            serviceId.set(result.serviceId());
            state.set(RegistrationState.REGISTERED);
            cancelRequiredTimeout();
            LOG.infof("Service registered successfully (direct mode) with ID: %s", result.serviceId());
        } else {
            LOG.errorf("Direct registration failed: %s", result.message());
            state.set(RegistrationState.FAILED);
        }
    }

    /**
     * gRPC mode: register via platform-registration-service gRPC stream (legacy path).
     */
    private void registerGrpcWithRetry(ServiceInfo serviceInfo) {
        long maxAttempts = config.required() ? Long.MAX_VALUE : config.retry().maxAttempts();
        Duration initialDelay = config.retry().initialDelay();
        Duration maxDelay = config.retry().maxDelay();

        registrationSubscription = registrationClient.register(serviceInfo)
                .onItem().invoke(this::handleGrpcRegistrationResponse)
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
                                LOG.warnf(failure, "Registration stream failed after successful registration, will re-register");
                                handleConnectionLoss();
                            } else if (config.reRegistration().enabled()) {
                                LOG.warnf(failure, "Registration failed after %d attempts, will retry in %s",
                                        maxAttempts, config.reRegistration().interval());
                                scheduleReRegistration();
                            } else {
                                LOG.errorf(failure, "Registration failed after %d attempts", maxAttempts);
                                state.set(RegistrationState.FAILED);
                            }
                        },
                        () -> {
                            RegistrationState currentState = state.get();
                            if (currentState == RegistrationState.REGISTERED) {
                                LOG.debug("Registration stream completed normally after successful registration");
                            } else {
                                LOG.info("Registration stream completed");
                            }
                        }
                );
    }

    private void handleGrpcRegistrationResponse(RegisterResponse response) {
        var event = response.getEvent();
        LOG.infof("Registration event: type=%s, message=%s", event.getEventType(), event.getMessage());

        if (event.getEventType() == PlatformEventType.PLATFORM_EVENT_TYPE_COMPLETED) {
            String newServiceId = event.getServiceId();
            serviceId.set(newServiceId);
            state.set(RegistrationState.REGISTERED);
            cancelRequiredTimeout();
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

            if (isDirectMode()) {
                directRegistrationService.unregister(info.getName(), info.getAdvertisedHost(), info.getAdvertisedPort())
                        .await().atMost(Duration.ofSeconds(10));
            } else {
                registrationClient.unregister(info.getName(), info.getAdvertisedHost(), info.getAdvertisedPort())
                        .await().atMost(Duration.ofSeconds(10));
            }

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
     * Handles connection loss after successful registration (gRPC mode only).
     */
    private void handleConnectionLoss() {
        RegistrationState currentState = state.get();
        if (currentState != RegistrationState.REGISTERED) {
            return;
        }

        LOG.info("Handling connection loss - scheduling re-registration");
        scheduleReRegistration();
    }

    private void scheduleReRegistration() {
        Duration interval = config.reRegistration().interval();
        LOG.infof("Scheduling re-registration in %s", interval);

        resetForReRegistration();

        reRegistrationTimerId = vertx.setTimer(interval.toMillis(), id -> {
            reRegistrationTimerId = null;
            if (state.get() == RegistrationState.REGISTERED || state.get() == RegistrationState.DEREGISTERING) {
                return;
            }
            LOG.info("Starting new registration attempt");
            scheduleRequiredTimeout();
            registerWithRetry();
        });
    }

    private void resetForReRegistration() {
        if (registrationSubscription != null) {
            registrationSubscription.cancel();
            registrationSubscription = null;
        }
        if (!isDirectMode()) {
            registrationClient.resetChannel();
        }
        state.set(RegistrationState.UNREGISTERED);
        serviceId.set(null);
    }

    private void cancelReRegistrationTimer() {
        Long timerId = reRegistrationTimerId;
        reRegistrationTimerId = null;
        if (timerId != null) {
            vertx.cancelTimer(timerId);
        }
    }

    private void scheduleRequiredTimeout() {
        if (!config.required()) {
            return;
        }
        Duration timeout = config.requiredTimeout();
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            LOG.warn("Registration required but required-timeout is not positive; skipping timeout enforcement");
            return;
        }
        synchronized (timeoutLock) {
            if (requiredTimeoutTimerId != null) {
                return;
            }
            requiredTimeoutTimerId = vertx.setTimer(timeout.toMillis(), id -> handleRequiredTimeout(timeout));
        }
        LOG.infof("Registration required: waiting up to %s for registration to complete", timeout);
    }

    private void cancelRequiredTimeout() {
        Long timerId;
        synchronized (timeoutLock) {
            timerId = requiredTimeoutTimerId;
            requiredTimeoutTimerId = null;
        }
        if (timerId != null) {
            vertx.cancelTimer(timerId);
        }
    }

    private void handleRequiredTimeout(Duration timeout) {
        synchronized (timeoutLock) {
            requiredTimeoutTimerId = null;
        }
        if (state.get() == RegistrationState.REGISTERED) {
            return;
        }
        String diagnostics = buildRegistrationDiagnostics();
        LOG.errorf("Registration required but not completed within %s. %s", timeout, diagnostics);
        if (registrationSubscription != null) {
            registrationSubscription.cancel();
        }
        state.set(RegistrationState.FAILED);
        Quarkus.asyncExit(1);
    }

    private String buildRegistrationDiagnostics() {
        if (isDirectMode()) {
            return String.format("Mode=direct Consul=%s:%d", consulHost, consulPort);
        }
        String discoveryName = config.registrationService().discoveryName().orElse("platform-registration");
        String host = config.registrationService().host().orElse("<unset>");
        String port = config.registrationService().port().map(String::valueOf).orElse("<unset>");
        return String.format("Mode=grpc Discovery=%s Consul=%s:%d Host=%s Port=%s",
                discoveryName, consulHost, consulPort, host, port);
    }
}
