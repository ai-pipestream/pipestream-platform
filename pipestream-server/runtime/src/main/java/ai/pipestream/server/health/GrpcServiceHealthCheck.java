package ai.pipestream.server.health;

import io.grpc.ChannelCredentials;
import io.grpc.Grpc;
import io.grpc.InsecureChannelCredentials;
import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;
import io.grpc.TlsChannelCredentials;
import io.grpc.health.v1.HealthCheckRequest;
import io.grpc.health.v1.HealthCheckResponse.ServingStatus;
import io.grpc.health.v1.HealthGrpc;
import io.quarkus.grpc.runtime.GrpcServerRecorder;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponseBuilder;
import org.eclipse.microprofile.health.Readiness;
import org.jboss.logging.Logger;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Readiness
@ApplicationScoped
public class GrpcServiceHealthCheck implements HealthCheck {

    private static final Logger LOG = Logger.getLogger(GrpcServiceHealthCheck.class);

    private static final Set<String> RESERVED_GRPC_SERVICES = Set.of(
            "grpc.health.v1.Health",
            "grpc.reflection.v1.ServerReflection",
            "grpc.reflection.v1alpha.ServerReflection");

    @Inject
    PipestreamHealthConfig config;

    @Override
    public org.eclipse.microprofile.health.HealthCheckResponse call() {
        HealthCheckResponseBuilder builder = org.eclipse.microprofile.health.HealthCheckResponse
                .named("gRPC Services");

        if (!config.enabled() || !config.grpc().enabled()) {
            return builder.up().withData("status", "disabled").build();
        }

        List<String> services = collectGrpcServiceNames();
        if (services.isEmpty()) {
            return builder.up().withData("status", "no gRPC services registered").build();
        }

        int port = resolveGrpcPort();
        long timeoutMs = config.grpc().timeout().toMillis();
        boolean allHealthy = true;

        ManagedChannel channel = null;
        try {
            channel = buildChannel(port);
            HealthGrpc.HealthBlockingStub stub = HealthGrpc.newBlockingStub(channel)
                    .withDeadlineAfter(timeoutMs, TimeUnit.MILLISECONDS);

            for (String service : services) {
                try {
                    io.grpc.health.v1.HealthCheckResponse response = stub.check(
                            HealthCheckRequest.newBuilder().setService(service).build());
                    ServingStatus status = response.getStatus();
                    builder.withData(service, status.name());
                    if (status != ServingStatus.SERVING) {
                        allHealthy = false;
                    }
                } catch (StatusRuntimeException e) {
                    builder.withData(service, "ERROR: " + e.getStatus().getCode().name());
                    allHealthy = false;
                } catch (Exception e) {
                    builder.withData(service, "ERROR: " + e.getMessage());
                    allHealthy = false;
                }
            }
        } catch (Exception e) {
            LOG.warn("Failed to create gRPC health check channel", e);
            builder.withData("error", e.getMessage());
            allHealthy = false;
        } finally {
            if (channel != null) {
                channel.shutdownNow();
            }
        }

        return allHealthy ? builder.up().build() : builder.down().build();
    }

    private List<String> collectGrpcServiceNames() {
        try {
            List<GrpcServerRecorder.GrpcServiceDefinition> definitions = GrpcServerRecorder.getServices();
            if (definitions == null || definitions.isEmpty()) {
                return Collections.emptyList();
            }
            return definitions.stream()
                    .map(def -> def.definition.getServiceDescriptor().getName())
                    .filter(name -> name != null && !name.isBlank())
                    .filter(name -> !RESERVED_GRPC_SERVICES.contains(name))
                    .distinct()
                    .sorted()
                    .collect(Collectors.toList());
        } catch (Exception | NoClassDefFoundError e) {
            LOG.debug("gRPC services unavailable for health check", e);
            return Collections.emptyList();
        }
    }

    private ManagedChannel buildChannel(int port) throws Exception {
        ChannelCredentials credentials;
        if (config.grpc().tlsEnabled()) {
            TlsChannelCredentials.Builder tlsBuilder = TlsChannelCredentials.newBuilder();
            if (config.grpc().tlsSkipVerify()) {
                tlsBuilder.trustManager(io.netty.handler.ssl.util.InsecureTrustManagerFactory.INSTANCE
                        .getTrustManagers());
            }
            credentials = tlsBuilder.build();
        } else {
            credentials = InsecureChannelCredentials.create();
        }
        return Grpc.newChannelBuilder("localhost:" + port, credentials).build();
    }

    private int resolveGrpcPort() {
        boolean separateServer = ConfigProvider.getConfig()
                .getOptionalValue("quarkus.grpc.server.use-separate-server", Boolean.class)
                .orElse(false);
        if (separateServer) {
            return ConfigProvider.getConfig()
                    .getOptionalValue("quarkus.grpc.server.port", Integer.class)
                    .orElse(9000);
        }
        return ConfigProvider.getConfig()
                .getOptionalValue("quarkus.http.port", Integer.class)
                .orElse(8080);
    }
}
