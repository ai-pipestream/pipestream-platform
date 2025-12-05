package ai.pipestream.quarkus.dynamicgrpc.exception;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;

/**
 * Thrown when a requested service cannot be found in service discovery.
 * <p>
 * This exception extends {@link StatusRuntimeException} with {@link Status#NOT_FOUND}
 * to align with gRPC conventions while providing domain-specific context.
 * </p>
 */
public class ServiceNotFoundException extends StatusRuntimeException {
    /**
     * The logical service name that could not be found.
     */
    private final String serviceName;

    /**
     * Creates a new ServiceNotFoundException.
     *
     * @param serviceName the name of the service that was not found
     */
    public ServiceNotFoundException(String serviceName) {
        super(Status.NOT_FOUND.withDescription(
            String.format("Service '%s' not found in service discovery", serviceName)
        ));
        this.serviceName = serviceName;
    }

    /**
     * Creates a new ServiceNotFoundException with a custom message.
     *
     * @param serviceName the name of the service that was not found
     * @param message additional context about the failure
     */
    public ServiceNotFoundException(String serviceName, String message) {
        super(Status.NOT_FOUND.withDescription(
            String.format("Service '%s' not found: %s", serviceName, message)
        ));
        this.serviceName = serviceName;
    }

    /**
     * Creates a new ServiceNotFoundException wrapping an underlying cause.
     *
     * @param serviceName the name of the service that was not found
     * @param cause the underlying exception
     */
    public ServiceNotFoundException(String serviceName, Throwable cause) {
        super(Status.NOT_FOUND
            .withDescription(String.format("Service '%s' not found in service discovery", serviceName))
            .withCause(cause)
        );
        this.serviceName = serviceName;
    }

    /**
     * Returns the name of the service that was not found.
     *
     * @return the service name
     */
    public String getServiceName() {
        return serviceName;
    }
}
