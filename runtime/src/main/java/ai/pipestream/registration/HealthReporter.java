package ai.pipestream.registration;

import ai.pipestream.platform.registration.v1.HealthStatus;
import ai.pipestream.registration.config.RegistrationConfig;
import io.smallrye.health.SmallRyeHealth;
import io.smallrye.health.SmallRyeHealthReporter;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.subscription.Cancellable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Reports health status periodically to the registration service.
 * 
 * <p>Integrates with SmallRye Health to aggregate health check results
 * and report them to the platform registration service.
 */
@ApplicationScoped
public class HealthReporter {

    private static final Logger LOG = Logger.getLogger(HealthReporter.class);

    private final RegistrationClient registrationClient;
    private final RegistrationConfig config;
    private final SmallRyeHealthReporter healthReporter;

    private final AtomicReference<String> serviceId = new AtomicReference<>();
    private volatile Cancellable healthReportSchedule;

    @Inject
    public HealthReporter(RegistrationClient registrationClient,
                          RegistrationConfig config,
                          SmallRyeHealthReporter healthReporter) {
        this.registrationClient = registrationClient;
        this.config = config;
        this.healthReporter = healthReporter;
    }

    /**
     * Starts periodic health reporting for the given service.
     *
     * @param serviceId The registered service ID
     */
    public void startHealthReporting(String serviceId) {
        if (!config.healthCheck().enabled()) {
            LOG.info("Health reporting is disabled");
            return;
        }

        this.serviceId.set(serviceId);
        Duration interval = config.healthCheck().interval();

        LOG.infof("Starting health reporting for service %s with interval %s", serviceId, interval);

        healthReportSchedule = Multi.createFrom().ticks()
                .every(interval)
                .onItem().transformToUniAndMerge(tick -> reportHealth())
                .subscribe().with(
                        v -> LOG.trace("Health reported successfully"),
                        failure -> LOG.warnf(failure, "Health report failed")
                );
    }

    /**
     * Stops health reporting.
     */
    public void stopHealthReporting() {
        if (healthReportSchedule != null) {
            LOG.info("Stopping health reporting");
            healthReportSchedule.cancel();
            healthReportSchedule = null;
        }
    }

    private io.smallrye.mutiny.Uni<Void> reportHealth() {
        String currentServiceId = serviceId.get();
        if (currentServiceId == null) {
            return io.smallrye.mutiny.Uni.createFrom().voidItem();
        }

        HealthStatus status = computeAggregateHealthStatus();
        String message = status == HealthStatus.HEALTH_STATUS_UP ? "All health checks passed" : "Health check failed";

        return registrationClient.updateHealth(currentServiceId, status, message)
                .replaceWithVoid()
                .onFailure().recoverWithNull()
                .replaceWithVoid();
    }

    private HealthStatus computeAggregateHealthStatus() {
        try {
            SmallRyeHealth health = healthReporter.getHealth();
            
            if (health.isDown()) {
                return HealthStatus.HEALTH_STATUS_DOWN;
            }
            return HealthStatus.HEALTH_STATUS_UP;
        } catch (Exception e) {
            LOG.warnf(e, "Error computing aggregate health status");
            return HealthStatus.HEALTH_STATUS_UNKNOWN;
        }
    }
}
