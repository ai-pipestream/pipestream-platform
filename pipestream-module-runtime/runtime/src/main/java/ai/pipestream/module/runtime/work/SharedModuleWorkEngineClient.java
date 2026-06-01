package ai.pipestream.module.runtime.work;

import ai.pipestream.module.work.v1.ModuleWorkServiceGrpc;
import ai.pipestream.server.grpc.EphemeralGrpcChannelFactory;
import io.grpc.ManagedChannel;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * JVM-scoped engine channel for demand-pull workers. Matches the old
 * {@code @GrpcClient("engine")} lifetime.
 *
 * <p>The channel is a process-wide singleton shared by every worker virtual
 * thread, so {@link #reconnect()} ({@code channel.shutdownNow()}) cancels all
 * of their in-flight Work streams at once. It is therefore only invoked on
 * shutdown ({@link #onShutdown()}); the worker loop must NOT call it per
 * stream error (that cascades into a fleet-wide cancellation storm — see
 * {@link ModuleWorkEngineClient}). A channel that genuinely dies is rebuilt
 * lazily by {@link #channel()} on the next {@link #stub()} call.
 */
@ApplicationScoped
public class SharedModuleWorkEngineClient implements ModuleWorkEngineClient {

    private static final Logger LOG = Logger.getLogger(SharedModuleWorkEngineClient.class);

    private final EphemeralGrpcChannelFactory channelFactory;
    private final WorkerLoopConfig config;
    private final Object lock = new Object();
    private volatile ManagedChannel channel;

    @Inject
    public SharedModuleWorkEngineClient(
            EphemeralGrpcChannelFactory channelFactory,
            WorkerLoopConfig config) {
        this.channelFactory = channelFactory;
        this.config = config;
    }

    @Override
    public ModuleWorkServiceGrpc.ModuleWorkServiceStub stub() {
        return ModuleWorkServiceGrpc.newStub(channel());
    }

    @Override
    public void reconnect() {
        synchronized (lock) {
            ManagedChannel old = channel;
            channel = null;
            if (old != null) {
                LOG.debugf("Reconnecting engine channel for module=%s", config.moduleId());
                EphemeralGrpcChannelFactory.shutdownNow(old);
            }
        }
    }

    @PreDestroy
    void onShutdown() {
        reconnect();
    }

    private ManagedChannel channel() {
        ManagedChannel current = channel;
        if (current == null || current.isShutdown() || current.isTerminated()) {
            synchronized (lock) {
                current = channel;
                if (current == null || current.isShutdown() || current.isTerminated()) {
                    current = channelFactory.open(config.grpcClientName());
                    channel = current;
                    LOG.debugf("Opened engine channel for module=%s client=%s",
                            config.moduleId(), config.grpcClientName());
                }
            }
        }
        return current;
    }
}
