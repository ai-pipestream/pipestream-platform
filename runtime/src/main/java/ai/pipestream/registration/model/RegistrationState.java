package ai.pipestream.registration.model;

/**
 * Tracks the current state of service registration.
 */
public enum RegistrationState {
    /**
     * Initial state - not yet registered.
     */
    UNREGISTERED,

    /**
     * Registration is in progress.
     */
    REGISTERING,

    /**
     * Successfully registered.
     */
    REGISTERED,

    /**
     * Registration failed.
     */
    FAILED,

    /**
     * Deregistration is in progress.
     */
    DEREGISTERING,

    /**
     * Successfully deregistered.
     */
    DEREGISTERED
}
