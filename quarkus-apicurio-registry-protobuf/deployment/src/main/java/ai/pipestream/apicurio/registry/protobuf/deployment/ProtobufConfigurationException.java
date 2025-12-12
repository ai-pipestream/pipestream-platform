package ai.pipestream.apicurio.registry.protobuf.deployment;

/**
 * Exception thrown when the Protobuf extension configuration is invalid.
 */
public class ProtobufConfigurationException extends RuntimeException {

    public ProtobufConfigurationException(String message) {
        super(message);
    }

    public ProtobufConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }
}
