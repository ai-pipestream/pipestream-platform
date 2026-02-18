package ai.pipestream.registration.model;

/**
 * Result of a direct Consul registration attempt.
 *
 * @param serviceId   The Consul service ID (null on failure)
 * @param serviceName The service name that was registered
 * @param healthy     Whether the service is healthy in Consul after registration
 * @param message     Human-readable status message
 */
public record RegistrationResult(String serviceId, String serviceName, boolean healthy, String message) {

    /**
     * Creates a successful registration result.
     */
    public static RegistrationResult success(String serviceId, String serviceName) {
        return new RegistrationResult(serviceId, serviceName, true,
                "Service registered and healthy: " + serviceId);
    }

    /**
     * Creates a failed registration result.
     */
    public static RegistrationResult failure(String serviceName, String reason) {
        return new RegistrationResult(null, serviceName, false, reason);
    }

    /**
     * Creates a failed registration result with a known service ID (e.g., registered but unhealthy).
     */
    public static RegistrationResult unhealthy(String serviceId, String serviceName) {
        return new RegistrationResult(serviceId, serviceName, false,
                "Service registered but failed health check: " + serviceId);
    }
}
