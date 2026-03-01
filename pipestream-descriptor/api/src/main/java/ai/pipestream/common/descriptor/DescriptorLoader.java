package ai.pipestream.common.descriptor;

import com.google.protobuf.Descriptors.FileDescriptor;

import java.util.List;

/**
 * Generic interface for loading Protocol Buffer descriptors from various sources.
 */
public interface DescriptorLoader {

    /**
     * Loads all available file descriptors from this loader's source.
     *
     * @return A list of FileDescriptors
     * @throws DescriptorLoadException if loading fails
     */
    List<FileDescriptor> loadDescriptors() throws DescriptorLoadException;

    /**
     * Loads a specific file descriptor by name.
     *
     * @param fileName The proto file name (e.g., "my_types.proto")
     * @return The FileDescriptor, or null if not found
     * @throws DescriptorLoadException if loading fails
     */
    FileDescriptor loadDescriptor(String fileName) throws DescriptorLoadException;

    /**
     * Checks if this loader is available and can load descriptors.
     */
    boolean isAvailable();

    /**
     * Gets a human-readable name for this loader type.
     */
    String getLoaderType();

    /**
     * Exception thrown when descriptor loading fails.
     */
    class DescriptorLoadException extends Exception {
        public DescriptorLoadException(String message) {
            super(message);
        }

        public DescriptorLoadException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
