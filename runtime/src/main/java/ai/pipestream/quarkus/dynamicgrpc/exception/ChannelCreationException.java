package ai.pipestream.quarkus.dynamicgrpc.exception;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;

/**
 * Thrown when gRPC channel creation fails.
 * <p>
 * This exception extends {@link StatusRuntimeException} with {@link Status#INTERNAL}
 * to indicate an internal failure in creating or configuring a gRPC channel.
 * </p>
 */
public class ChannelCreationException extends StatusRuntimeException {

    private final String serviceName;

    /**
     * Creates a new ChannelCreationException.
     *
     * @param serviceName the name of the service for which channel creation failed
     * @param message description of the creation failure
     */
    public ChannelCreationException(String serviceName, String message) {
        super(Status.INTERNAL.withDescription(
            String.format("Failed to create gRPC channel for service '%s': %s", serviceName, message)
        ));
        this.serviceName = serviceName;
    }

    /**
     * Creates a new ChannelCreationException wrapping an underlying cause.
     *
     * @param serviceName the name of the service for which channel creation failed
     * @param message description of the creation failure
     * @param cause the underlying exception
     */
    public ChannelCreationException(String serviceName, String message, Throwable cause) {
        super(Status.INTERNAL
            .withDescription(String.format("Failed to create gRPC channel for service '%s': %s", serviceName, message))
            .withCause(cause)
        );
        this.serviceName = serviceName;
    }

    /**
     * Returns the name of the service for which channel creation failed.
     *
     * @return the service name
     */
    public String getServiceName() {
        return serviceName;
    }
}
