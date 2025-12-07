package ai.pipestream.quarkus.dynamicgrpc.exception;

/**
 * Thrown when a service name fails validation (null, empty, or blank).
 * <p>
 * Extends {@link IllegalArgumentException} to maintain standard Java conventions
 * while adding context for metrics and distributed tracing.
 * </p>
 */
public class InvalidServiceNameException extends IllegalArgumentException {

    /**
     * The service name value that failed validation; may be null.
     */
    private final String attemptedServiceName;

    /**
     * Human-readable explanation of why the provided service name is invalid.
     */
    private final String validationFailureReason;

    /**
     * Creates a new InvalidServiceNameException.
     *
     * @param attemptedServiceName the invalid service name that was provided (may be null)
     * @param validationFailureReason description of why validation failed
     */
    public InvalidServiceNameException(String attemptedServiceName, String validationFailureReason) {
        super(String.format("Invalid service name: %s", validationFailureReason));
        this.attemptedServiceName = attemptedServiceName;
        this.validationFailureReason = validationFailureReason;
    }

    /**
     * Returns the service name that failed validation.
     *
     * @return the attempted service name (may be null)
     */
    public String getAttemptedServiceName() {
        return attemptedServiceName;
    }

    /**
     * Returns the reason validation failed.
     *
     * @return the validation failure reason
     */
    public String getValidationFailureReason() {
        return validationFailureReason;
    }
}
