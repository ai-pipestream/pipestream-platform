package ai.pipestream.registration;

import ai.pipestream.platform.registration.v1.*;
import ai.pipestream.registration.config.RegistrationConfig;
import ai.pipestream.registration.model.ServiceInfo;
import com.google.protobuf.Timestamp;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.subscription.MultiEmitter;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

/**
 * gRPC client wrapper for the platform-registration-service.
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
     * Register a service and receive streaming status updates.
     *
     * @param serviceInfo The service information to register
     * @return Multi of registration responses
     */
    public Multi<RegisterServiceResponse> registerService(ServiceInfo serviceInfo) {
        ensureChannel();

        RegisterServiceRequest request = RegisterServiceRequest.newBuilder()
                .setServiceName(serviceInfo.getServiceName())
                .setHost(serviceInfo.getHost())
                .setPort(serviceInfo.getPort())
                .setVersion(serviceInfo.getVersion() != null ? serviceInfo.getVersion() : "")
                .putAllMetadata(serviceInfo.getMetadata())
                .build();

        LOG.infof("Registering service: %s", serviceInfo.getServiceName());

        return Multi.createFrom().emitter(emitter -> {
            asyncStub.registerService(request, new StreamObserver<RegisterServiceResponse>() {
                @Override
                public void onNext(RegisterServiceResponse response) {
                    var event = response.getEvent();
                    LOG.debugf("Received registration event: type=%s, message=%s",
                            event.getEventType(), event.getMessage());
                    emitter.emit(response);
                }

                @Override
                public void onError(Throwable t) {
                    LOG.errorf(t, "Registration failed for service: %s", serviceInfo.getServiceName());
                    emitter.fail(t);
                }

                @Override
                public void onCompleted() {
                    LOG.infof("Registration stream completed for service: %s", serviceInfo.getServiceName());
                    emitter.complete();
                }
            });
        });
    }

    /**
     * Unregister a service.
     *
     * @param serviceName The service name
     * @param host The service host
     * @param port The service port
     * @return Uni of the unregister response
     */
    public Uni<UnregisterServiceResponse> unregisterService(String serviceName, String host, int port) {
        ensureChannel();

        return Uni.createFrom().item(() -> {
            UnregisterServiceRequest request = UnregisterServiceRequest.newBuilder()
                    .setServiceName(serviceName)
                    .setHost(host)
                    .setPort(port)
                    .build();

            LOG.infof("Unregistering service: %s at %s:%d", serviceName, host, port);

            UnregisterServiceResponse response = blockingStub
                    .withDeadlineAfter(config.registrationService().timeout().toMillis(), TimeUnit.MILLISECONDS)
                    .unregisterService(request);

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
