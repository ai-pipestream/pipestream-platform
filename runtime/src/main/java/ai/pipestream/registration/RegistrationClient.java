package ai.pipestream.registration;

import ai.pipestream.platform.registration.v1.*;
import ai.pipestream.registration.config.RegistrationConfig;
import ai.pipestream.registration.model.ServiceInfo;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.concurrent.TimeUnit;

/**
 * gRPC client wrapper for the platform-registration-service.
 *
 * Uses the unified Register/Unregister RPCs.
 */
@ApplicationScoped
public class RegistrationClient {

    private static final Logger LOG = Logger.getLogger(RegistrationClient.class);
    private static final int SHUTDOWN_TIMEOUT_SECONDS = 5;

    private final RegistrationConfig config;
    private volatile ManagedChannel channel;
    private volatile PlatformRegistrationServiceGrpc.PlatformRegistrationServiceStub asyncStub;
    private volatile PlatformRegistrationServiceGrpc.PlatformRegistrationServiceBlockingStub blockingStub;

    @Inject
    public RegistrationClient(RegistrationConfig config) {
        this.config = config;
    }

    private void ensureChannel() {
        if (channel == null) {
            synchronized (this) {
                if (channel == null) {
                    String host = config.registrationService().host();
                    int port = config.registrationService().port();

                    LOG.infof("Creating gRPC channel to registration service at %s:%d", host, port);

                    channel = ManagedChannelBuilder
                            .forAddress(host, port)
                            .usePlaintext()
                            .build();

                    asyncStub = PlatformRegistrationServiceGrpc.newStub(channel);
                    blockingStub = PlatformRegistrationServiceGrpc.newBlockingStub(channel);
                }
            }
        }
    }

    /**
     * Register a service or module and receive streaming status updates.
     *
     * @param serviceInfo The service information to register
     * @return Multi of registration responses
     */
    public Multi<RegisterResponse> register(ServiceInfo serviceInfo) {
        ensureChannel();

        // Build connectivity
        Connectivity.Builder connectivityBuilder = Connectivity.newBuilder()
                .setAdvertisedHost(serviceInfo.getAdvertisedHost())
                .setAdvertisedPort(serviceInfo.getAdvertisedPort())
                .setTlsEnabled(serviceInfo.isTlsEnabled());

        if (serviceInfo.getInternalHost() != null) {
            connectivityBuilder.setInternalHost(serviceInfo.getInternalHost());
        }
        if (serviceInfo.getInternalPort() != null) {
            connectivityBuilder.setInternalPort(serviceInfo.getInternalPort());
        }

        // Build request
        RegisterRequest.Builder requestBuilder = RegisterRequest.newBuilder()
                .setName(serviceInfo.getName())
                .setType(serviceInfo.getType())
                .setConnectivity(connectivityBuilder.build())
                .putAllMetadata(serviceInfo.getMetadata())
                .addAllTags(serviceInfo.getTags())
                .addAllCapabilities(serviceInfo.getCapabilities());

        if (serviceInfo.getVersion() != null) {
            requestBuilder.setVersion(serviceInfo.getVersion());
        }

        RegisterRequest request = requestBuilder.build();

        LOG.infof("Registering %s: %s", serviceInfo.getType().name(), serviceInfo.getName());

        return Multi.createFrom().emitter(emitter -> {
            asyncStub.register(request, new StreamObserver<RegisterResponse>() {
                @Override
                public void onNext(RegisterResponse response) {
                    var event = response.getEvent();
                    LOG.debugf("Received registration event: type=%s, message=%s",
                            event.getEventType(), event.getMessage());
                    emitter.emit(response);
                }

                @Override
                public void onError(Throwable t) {
                    LOG.errorf(t, "Registration failed for %s: %s",
                            serviceInfo.getType().name(), serviceInfo.getName());
                    emitter.fail(t);
                }

                @Override
                public void onCompleted() {
                    LOG.infof("Registration stream completed for %s: %s",
                            serviceInfo.getType().name(), serviceInfo.getName());
                    emitter.complete();
                }
            });
        });
    }

    /**
     * Unregister a service or module.
     *
     * @param name The service/module name
     * @param host The host
     * @param port The port
     * @return Uni of the unregister response
     */
    public Uni<UnregisterResponse> unregister(String name, String host, int port) {
        ensureChannel();

        return Uni.createFrom().item(() -> {
            UnregisterRequest request = UnregisterRequest.newBuilder()
                    .setName(name)
                    .setHost(host)
                    .setPort(port)
                    .build();

            LOG.infof("Unregistering: %s at %s:%d", name, host, port);

            UnregisterResponse response = blockingStub
                    .withDeadlineAfter(config.registrationService().timeout().toMillis(), TimeUnit.MILLISECONDS)
                    .unregister(request);

            LOG.infof("Unregister response: success=%s, message=%s", response.getSuccess(), response.getMessage());
            return response;
        });
    }

    @PreDestroy
    void shutdown() {
        if (channel != null) {
            LOG.info("Shutting down registration client channel");
            try {
                channel.shutdown().awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                LOG.warn("Interrupted while shutting down channel");
                channel.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
}
