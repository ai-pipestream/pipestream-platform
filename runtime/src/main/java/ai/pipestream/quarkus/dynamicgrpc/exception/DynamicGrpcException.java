package ai.pipestream.quarkus.dynamicgrpc.exception;

/**
 * Base exception for all dynamic gRPC extension errors that are not gRPC-specific.
 * <p>
 * For gRPC-related errors, prefer the domain-specific {@link io.grpc.StatusRuntimeException}
 * subclasses like {@link ServiceNotFoundException}, {@link ServiceDiscoveryException}, or
 * {@link ChannelCreationException}.
 * </p>
 */
public class DynamicGrpcException extends RuntimeException {

    /**
     * Creates a new DynamicGrpcException.
     *
     * @param message the exception message
     */
    public DynamicGrpcException(String message) {
        super(message);
    }

    /**
     * Creates a new DynamicGrpcException with a cause.
     *
     * @param message the exception message
     * @param cause the underlying cause
     */
    public DynamicGrpcException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Creates a new DynamicGrpcException wrapping a cause.
     *
     * @param cause the underlying cause
     */
    public DynamicGrpcException(Throwable cause) {
        super(cause);
    }
}
