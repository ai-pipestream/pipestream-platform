package ai.pipestream.quarkus.dynamicgrpc.exception;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;

/**
 * Thrown when service discovery operations fail.
 * <p>
 * This exception extends {@link StatusRuntimeException} with {@link Status#UNAVAILABLE}
 * to indicate that the service discovery mechanism itself encountered an error, making
 * services temporarily unavailable.
 * </p>
 */
public class ServiceDiscoveryException extends StatusRuntimeException {

    private final String serviceName;

    /**
     * Creates a new ServiceDiscoveryException.
     *
     * @param serviceName the name of the service being discovered
     * @param message description of the discovery failure
     */
    public ServiceDiscoveryException(String serviceName, String message) {
        super(Status.UNAVAILABLE.withDescription(
            String.format("Service discovery failed for '%s': %s", serviceName, message)
        ));
        this.serviceName = serviceName;
    }

    /**
     * Creates a new ServiceDiscoveryException wrapping an underlying cause.
     *
     * @param serviceName the name of the service being discovered
     * @param message description of the discovery failure
     * @param cause the underlying exception
     */
    public ServiceDiscoveryException(String serviceName, String message, Throwable cause) {
        super(Status.UNAVAILABLE
            .withDescription(String.format("Service discovery failed for '%s': %s", serviceName, message))
            .withCause(cause)
        );
        this.serviceName = serviceName;
    }

    /**
     * Returns the name of the service that failed discovery.
     *
     * @return the service name
     */
    public String getServiceName() {
        return serviceName;
    }
}
