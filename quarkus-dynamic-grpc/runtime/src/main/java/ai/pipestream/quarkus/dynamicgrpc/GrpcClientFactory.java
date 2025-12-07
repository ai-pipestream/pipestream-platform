package ai.pipestream.quarkus.dynamicgrpc;

import io.grpc.Channel;
import io.quarkus.grpc.MutinyStub;
import io.smallrye.mutiny.Uni;

import java.util.function.Function;

/**
 * Interface for dynamic gRPC client creation and management.
 * <p>
 * This factory enables creating gRPC clients with service names known only at runtime,
 * which is not possible with standard Quarkus gRPC that requires compile-time configuration.
 * </p>
 */
public interface GrpcClientFactory {

    /**
     * Get a typed Mutiny stub using a method reference (zero reflection).
     *
     * @param <T>         The Mutiny stub type (must extend MutinyStub)
     * @param serviceName The logical service name for discovery
     * @param stubCreator Method reference to create stub (e.g., MutinyFooServiceGrpc::newMutinyStub)
     * @return A Uni emitting the typed Stub
     */
    <T extends MutinyStub> Uni<T> getClient(String serviceName, Function<Channel, T> stubCreator);

    /**
     * Get a raw Channel for advanced use cases.
     *
     * @param serviceName The logical service name for discovery
     * @return A Uni emitting the Channel
     */
    Uni<Channel> getChannel(String serviceName);

    /**
     * Get the number of active service connections being managed.
     *
     * @return the count of currently cached channels
     */
    int getActiveServiceCount();

    /**
     * Evict (close) a cached channel for a service to force reconnection.
     *
     * @param serviceName the service whose channel should be removed
     */
    void evictChannel(String serviceName);

    /**
     * Get cache statistics for debugging and monitoring.
     *
     * @return a human-readable summary of cache statistics
     */
    String getCacheStats();
}
