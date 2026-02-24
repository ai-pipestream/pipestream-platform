package ai.pipestream.server.health;

import ai.pipestream.server.config.PipestreamServerConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Liveness;

import java.lang.management.ManagementFactory;

@Liveness
@ApplicationScoped
public class PipestreamLivenessCheck implements HealthCheck {

    @Inject
    PipestreamServerConfig serverConfig;

    @Inject
    PipestreamHealthConfig healthConfig;

    @Override
    public HealthCheckResponse call() {
        if (!healthConfig.enabled() || !healthConfig.liveness().enabled()) {
            return HealthCheckResponse.named("Pipestream Server")
                    .up()
                    .withData("status", "disabled")
                    .build();
        }

        return HealthCheckResponse.named("Pipestream Server")
                .up()
                .withData("server.class", serverConfig.serverClass())
                .withData("uptime.ms", ManagementFactory.getRuntimeMXBean().getUptime())
                .withData("thread.count", Thread.activeCount())
                .build();
    }
}
