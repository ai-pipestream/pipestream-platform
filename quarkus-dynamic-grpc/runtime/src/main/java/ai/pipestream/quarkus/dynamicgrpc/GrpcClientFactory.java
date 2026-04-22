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
     * Get a typed blocking stub using a method reference (zero reflection).
     * <p>
     * Intended for synchronous virtual-thread call sites that want blocking
     * semantics without Mutiny types bleeding into their code. Stork service
     * discovery + channel acquisition run internally; the caller thread
     * blocks until the stub is ready. After this returns every RPC on the
     * stub is a plain synchronous call — no {@link Uni} in the hot path.
     *
     * @param <T>         Generated gRPC blocking stub type
     *                    (typically {@code io.grpc.stub.AbstractBlockingStub})
     * @param serviceName The logical service name for discovery
     * @param stubCreator Method reference like {@code MyServiceGrpc::newBlockingStub}
     * @return The resolved blocking stub
     */
    <T> T getBlockingClient(String serviceName, Function<Channel, T> stubCreator);

    /**
     * Get a typed async stub (StreamObserver-based) using a method reference.
     * <p>
     * This is the classic non-blocking gRPC stub — RPCs accept a
     * {@code StreamObserver<Response>} and return {@code void} immediately.
     * The gRPC runtime invokes {@code onNext}/{@code onError}/{@code onCompleted}
     * on its internal netty event loops when the response arrives; no caller
     * thread parks on the call. Ideal for fire-and-forget dispatch paths
     * where blocking a virtual thread per-call serializes throughput against
     * the HTTP/2 multiplex limit.
     * <p>
     * Stork service discovery + channel acquisition run internally; the
     * caller thread blocks only until the stub is ready (same as
     * {@link #getBlockingClient}). After this returns every RPC on the stub
     * is a fully asynchronous callback-driven call — no {@link Uni}, no
     * blocking.
     *
     * @param <T>         Generated gRPC async stub type
     *                    (typically {@code io.grpc.stub.AbstractAsyncStub})
     * @param serviceName The logical service name for discovery
     * @param stubCreator Method reference like {@code MyServiceGrpc::newStub}
     * @return The resolved async stub
     */
    <T> T getAsyncClient(String serviceName, Function<Channel, T> stubCreator);

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
