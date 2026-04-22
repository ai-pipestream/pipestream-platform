package ai.pipestream.test.support;

/**
 * Single source of truth for test container image versions and resolution.
 * All test resources that create containers should reference these constants
 * instead of hardcoding version strings.
 */
public final class TestContainerConstants {

    private TestContainerConstants() {
    }

    /**
     * WireMock server container constants.
     */
    public static final class WireMock {
        private WireMock() {}

        public static final String VERSION = "0.1.56";
        public static final String IMAGE_PROPERTY = "pipestream.wiremock.image";
        public static final String IMAGE_ENV = "PIPESTREAM_WIREMOCK_IMAGE";
        public static final String IMAGE_ENV_FALLBACK = "WIREMOCK_IMAGE";
        public static final String DEFAULT_IMAGE = "docker.io/pipestreamai/pipestream-wiremock-server:" + VERSION;
        public static final int DEFAULT_HTTP_PORT = 8080;
        public static final int DEFAULT_GRPC_PORT = 50052;
    }

    /**
     * Resolves a container image name from system properties, environment variables, or default.
     * Resolution order:
     * <ol>
     *   <li>System property (e.g., {@code pipestream.wiremock.image})</li>
     *   <li>Primary environment variable (e.g., {@code PIPESTREAM_WIREMOCK_IMAGE})</li>
     *   <li>Fallback environment variable (e.g., {@code WIREMOCK_IMAGE})</li>
     *   <li>Default image</li>
     * </ol>
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
