package ai.pipestream.server.grpc;

import io.grpc.ManagedChannel;
import io.grpc.netty.NegotiationType;
import io.grpc.netty.NettyChannelBuilder;
import io.smallrye.stork.Stork;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.jboss.logging.Logger;

import java.util.concurrent.TimeUnit;

/**
 * Opens a {@link ManagedChannel} using the same {@code quarkus.grpc.clients.*}
 * keys as {@code @GrpcClient}. Used by {@link ai.pipestream.server.work.SharedModuleWorkEngineClient}
 * for the worker loop (one channel per JVM, reconnect on stream error) and by
 * one-shot callers such as echo-loop graph registration.
 */
@ApplicationScoped
public class EphemeralGrpcChannelFactory {

    private static final Logger LOG = Logger.getLogger(EphemeralGrpcChannelFactory.class);

    /** Default constructor for CDI. */
    public EphemeralGrpcChannelFactory() {}

    /**
     * Builds a fresh channel for the named Quarkus gRPC client
     * ({@code quarkus.grpc.clients.<clientName>.*}).
     */
    public ManagedChannel open(String clientName) {
        Config config = ConfigProvider.getConfig();
        String prefix = "quarkus.grpc.clients." + clientName + ".";
        String host = config.getOptionalValue(prefix + "host", String.class).orElse(clientName);
        String resolver = config.getOptionalValue(prefix + "name-resolver", String.class).orElse("dns");
        int maxInbound = config.getOptionalValue(prefix + "max-inbound-message-size", Integer.class)
                .orElse(Integer.MAX_VALUE);

        String target = "stork".equalsIgnoreCase(resolver)
                ? Stork.STORK + "://" + host
                : host;

        NettyChannelBuilder builder = NettyChannelBuilder.forTarget(target)
                .maxInboundMessageSize(maxInbound)
                .defaultLoadBalancingPolicy("round_robin")
                .keepAliveTime(30, TimeUnit.SECONDS)
                .keepAliveTimeout(10, TimeUnit.SECONDS)
                .keepAliveWithoutCalls(true);

        boolean plainText = config.getOptionalValue(prefix + "plain-text", Boolean.class).orElse(true);
        if (plainText) {
            builder.negotiationType(NegotiationType.PLAINTEXT);
        }

        LOG.debugf("Opening ephemeral gRPC channel: client=%s target=%s", clientName, target);
        return builder.build();
    }

    /** Non-blocking shutdown for reconnect paths — do not park worker VTs. */
    public static void shutdownNow(ManagedChannel channel) {
        if (channel == null || channel.isShutdown()) {
            return;
        }
        channel.shutdownNow();
    }

    /** Best-effort shutdown for a channel opened via {@link #open(String)}. */
    public static void shutdown(ManagedChannel channel) {
        if (channel == null || channel.isShutdown()) {
            return;
        }
        try {
            channel.shutdown();
            if (!channel.awaitTermination(5, TimeUnit.SECONDS)) {
                channel.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            channel.shutdownNow();
        }
    }
}
