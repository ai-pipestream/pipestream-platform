package ai.pipestream.server.constants;

/**
 * Common constants for container images and configurations used across Pipestream services.
 * These constants provide standardized image names, versions, and environment variable names
 * for various containers used in development and production.
 */
public final class ContainerConstants {

    private ContainerConstants() {
        // Utility class - prevent instantiation
    }

    /**
     * WireMock Server constants for gRPC mocking in tests
     */
    public static final class WireMock {
        private WireMock() {}

        public static final String VERSION = "0.1.38";
        public static final String IMAGE_PROPERTY = "pipestream.wiremock.image";
        public static final String IMAGE_ENV = "PIPESTREAM_WIREMOCK_IMAGE";
        public static final String IMAGE_ENV_FALLBACK = "WIREMOCK_IMAGE";
        public static final String DEFAULT_IMAGE = "ghcr.io/ai-pipestream/pipestream-wiremock-server:" + VERSION;
        public static final String DEFAULT_DOCKER_IO_IMAGE = "docker.io/pipestreamai/pipestream-wiremock-server:" + VERSION;
        public static final int DEFAULT_HTTP_PORT = 8080;
        public static final int DEFAULT_GRPC_PORT = 50052;
    }

    /**
     * Resolves a container image name from system properties, environment variables, or default.
     * Resolution order:
     * 1. System property (e.g., pipestream.wiremock.image)
     * 2. Primary environment variable (e.g., PIPESTREAM_WIREMOCK_IMAGE)
     * 3. Fallback environment variable (e.g., WIREMOCK_IMAGE)
     * 4. Default image
     *
     * @param systemProperty the system property key
     * @param primaryEnv the primary environment variable key
     * @param fallbackEnv the fallback environment variable key
     * @param defaultImage the default image if none of the above are set
     * @return the resolved image name
     */
    public static String resolveImage(String systemProperty, String primaryEnv, String fallbackEnv, String defaultImage) {
        String image = System.getProperty(systemProperty);
        if (image == null || image.isBlank()) {
            image = System.getenv(primaryEnv);
        }
        if (image == null || image.isBlank()) {
            image = System.getenv(fallbackEnv);
        }
        if (image == null || image.isBlank()) {
            image = defaultImage;
        }
        return image;
    }
}
