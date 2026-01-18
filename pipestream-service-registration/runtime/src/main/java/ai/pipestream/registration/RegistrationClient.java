package ai.pipestream.registration;

import ai.pipestream.platform.registration.v1.*;
import ai.pipestream.registration.config.RegistrationConfig;
import ai.pipestream.registration.model.ServiceInfo;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.StreamObserver;
import io.netty.handler.ssl.SslContext;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.Vertx;
import io.vertx.ext.consul.ConsulClientOptions;
import io.vertx.mutiny.ext.consul.ConsulClient;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * gRPC client wrapper for the platform-registration-service.
 * <p>
 * Uses the unified Register/Unregister RPCs.
 */
@ApplicationScoped
public class RegistrationClient {

    private static final Logger LOG = Logger.getLogger(RegistrationClient.class);
    private static final int SHUTDOWN_TIMEOUT_SECONDS = 5;
    private static final Duration CONSUL_DISCOVERY_TIMEOUT = Duration.ofSeconds(5);

    private final RegistrationConfig config;
    private final Vertx vertx;
    private volatile ManagedChannel channel;
    private volatile PlatformRegistrationServiceGrpc.PlatformRegistrationServiceStub asyncStub;
    private volatile PlatformRegistrationServiceGrpc.PlatformRegistrationServiceBlockingStub blockingStub;
    private volatile ConsulClient consulClient;

    @Inject
    public RegistrationClient(RegistrationConfig config, Vertx vertx) {
        this.config = config;
        this.vertx = vertx;
    }

    /**
     * Ensures the gRPC channel is created, using Consul discovery if configured,
     * otherwise falling back to direct host/port connection.
     */
    private void ensureChannel() {
        if (channel == null) {
            synchronized (this) {
                if (channel == null) {
                    String host;
                    int port;
                    boolean tlsEnabled = config.registrationService().tlsEnabled();

                    // Try Consul discovery if discovery-name is configured
                    Optional<String> discoveryName = config.registrationService().discoveryName();
                    if (discoveryName.isPresent()) {
                        LOG.infof("Attempting Consul discovery for registration service: %s", discoveryName.get());
                        var discovered = discoverViaConsul(discoveryName.get());
                        if (discovered != null) {
                            host = discovered.host();
                            port = discovered.port();
                            LOG.infof("Discovered registration service via Consul: %s:%d", host, port);
                        } else {
                            LOG.warn("Consul discovery failed, falling back to direct connection");
                            host = config.registrationService().host();
                            port = config.registrationService().port();
                        }
                    } else {
                        host = config.registrationService().host();
                        port = config.registrationService().port();
                    }

                    LOG.infof("Creating gRPC channel to registration service at %s:%d (TLS: %s)", host, port, tlsEnabled);
                    LOG.debugf("Channel creation details - host: %s, port: %d, tlsEnabled: %s, discoveryName: %s", 
                        host, port, tlsEnabled, config.registrationService().discoveryName().orElse("none"));

                    ManagedChannelBuilder<?> channelBuilder = ManagedChannelBuilder.forAddress(host, port);
                    
                    if (tlsEnabled) {
                        try {
                            SslContext sslContext = GrpcSslContexts.forClient().build();
                            channelBuilder = NettyChannelBuilder.forAddress(host, port)
                                    .sslContext(sslContext);
                        } catch (Exception e) {
                            LOG.warnf(e, "Failed to configure TLS, falling back to plaintext");
                            channelBuilder.usePlaintext();
                        }
                    } else {
                        channelBuilder.usePlaintext();
                    }

                    channel = channelBuilder.build();
                    asyncStub = PlatformRegistrationServiceGrpc.newStub(channel);
                    blockingStub = PlatformRegistrationServiceGrpc.newBlockingStub(channel);
                }
            }
        }
    }

    /**
     * Discovers the registration service via Consul.
     * 
     * @param serviceName The Consul service name
     * @return Discovered host/port, or null if discovery fails
     */
    private DiscoveryResult discoverViaConsul(String serviceName) {
        try {
            ConsulClient client = getOrCreateConsulClient();
            
            return client.healthServiceNodes(serviceName, true)
                    .map(serviceList -> {
                        if (serviceList != null && serviceList.getList() != null && !serviceList.getList().isEmpty()) {
                            var entry = serviceList.getList().getFirst();
                            var service = entry.getService();
                            return new DiscoveryResult(service.getAddress(), service.getPort());
                        }
                        LOG.warnf("No healthy instances found for service '%s' in Consul", serviceName);
                        return null;
                    })
                    .onFailure().recoverWithItem(throwable -> {
                        LOG.debugf(throwable, "Consul discovery failed for service '%s'", serviceName);
                        return null;
                    })
                    .await()
                    .atMost(CONSUL_DISCOVERY_TIMEOUT);
        } catch (Exception e) {
            LOG.debugf(e, "Consul discovery failed for service '%s'", serviceName);
            return null;
        }
    }

    /**
     * Gets or creates the Consul client (lazy initialization).
     */
    private ConsulClient getOrCreateConsulClient() {
        if (consulClient == null) {
            synchronized (this) {
                if (consulClient == null) {
                    String consulHost = config.consul().host();
                    int consulPort = config.consul().port();
                    
                    LOG.debugf("Creating Consul client for %s:%d", consulHost, consulPort);
                    
                    ConsulClientOptions options = new ConsulClientOptions()
                            .setHost(consulHost)
                            .setPort(consulPort);
                    
                    consulClient = ConsulClient.create(vertx, options);
                }
            }
        }
        return consulClient;
    }

    /**
     * Resets the channel (for re-registration scenarios).
     */
    void resetChannel() {
        synchronized (this) {
            if (channel != null) {
                LOG.info("Resetting registration client channel");
                try {
                    channel.shutdown().awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    LOG.warn("Interrupted while shutting down channel for reset");
                    channel.shutdownNow();
                    Thread.currentThread().interrupt();
                }
                channel = null;
                asyncStub = null;
                blockingStub = null;
            }
        }
    }

    /**
     * Result of Consul service discovery.
     */
    private record DiscoveryResult(String host, int port) {}

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

        if (!serviceInfo.getHttpEndpoints().isEmpty()) {
            serviceInfo.getHttpEndpoints().forEach(endpoint ->
                requestBuilder.addHttpEndpoints(HttpEndpoint.newBuilder()
                    .setScheme(endpoint.getScheme())
                    .setHost(endpoint.getHost())
                    .setPort(endpoint.getPort())
                    .setBasePath(endpoint.getBasePath())
                    .setHealthPath(endpoint.getHealthPath())
                    .setTlsEnabled(endpoint.isTlsEnabled())
                    .build())
            );
        }

        if (serviceInfo.getVersion() != null) {
            requestBuilder.setVersion(serviceInfo.getVersion());
        }

        RegisterRequest request = requestBuilder.build();

        LOG.infof("Registering %s: %s", serviceInfo.getType().name(), serviceInfo.getName());
        LOG.debugf("Registration details - serviceName: %s, advertisedHost: %s, advertisedPort: %d, channelState: %s",
            serviceInfo.getName(), serviceInfo.getAdvertisedHost(), serviceInfo.getAdvertisedPort(),
            channel != null ? channel.getState(false).toString() : "null");

        return Multi.createFrom().emitter(emitter ->
                asyncStub.register(request, new StreamObserver<>() {
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
                        LOG.debugf("Registration error details - errorClass: %s, errorMessage: %s, cause: %s, channelState: %s",
                            t.getClass().getName(), t.getMessage(),
                            t.getCause() != null ? t.getCause().getClass().getName() : "null",
                            channel != null ? channel.getState(false).toString() : "null");
                        emitter.fail(t);
                    }

                    @Override
                    public void onCompleted() {
                        LOG.infof("Registration stream completed for %s: %s",
                                serviceInfo.getType().name(), serviceInfo.getName());
                        emitter.complete();
                    }
                }));
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
