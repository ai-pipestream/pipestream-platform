package ai.pipestream.registration;

import ai.pipestream.registration.config.RegistrationConfig;
import ai.pipestream.registration.model.RegistrationState;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.HealthCheckResponseBuilder;
import org.eclipse.microprofile.health.Readiness;

@Readiness
@ApplicationScoped
public class RegistrationReadinessCheck implements HealthCheck {

    private static final String CHECK_NAME = "pipestream-registration";

    private final RegistrationConfig config;
    private final ServiceRegistrationManager registrationManager;

    @Inject
    public RegistrationReadinessCheck(RegistrationConfig config,
                                      ServiceRegistrationManager registrationManager) {
        this.config = config;
        this.registrationManager = registrationManager;
    }

    @Override
    public HealthCheckResponse call() {
        RegistrationState currentState = registrationManager.getState();

        HealthCheckResponseBuilder response = HealthCheckResponse.named(CHECK_NAME)
                .withData("enabled", config.enabled())
                .withData("required", config.required())
                .withData("state", currentState.name());

        String serviceId = registrationManager.getServiceId();
        if (serviceId != null) {
            response.withData("serviceId", serviceId);
        }

        if (!config.enabled() || !config.required()) {
            return response.up().build();
        }

        if (currentState == RegistrationState.REGISTERED) {
            return response.up().build();
        }

        return response.down().build();
    }
}
