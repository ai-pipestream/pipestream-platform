package ai.pipestream.test.support;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import org.jboss.logging.Logger;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.BucketVersioningStatus;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.PutBucketVersioningRequest;
import software.amazon.awssdk.services.s3.model.VersioningConfiguration;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Shared test resource for setting up SeaweedFS container to simulate AWS S3 API.
 * <p>
 * Provides S3-compatible instance for testing. Uses a <strong>singleton container</strong>
 * per JVM: the first test class to start gets a new SeaweedFS container; subsequent test
 * classes reuse the same container. This avoids starting many SeaweedFS servers when
 * running the full test suite.
 * </p>
 * <p>
 * Replaces previous MinIO implementation.
 * </p>
 */
public class S3TestResource implements QuarkusTestResourceLifecycleManager {

    private static final String DEFAULT_IMAGE = "chrislusf/seaweedfs:latest";
    public static final String ACCESS_KEY = "any";
    public static final String SECRET_KEY = "any";
    public static final String BUCKET = "test-bucket";
    private static final int S3_PORT = 8333;
    private static final int MASTER_PORT = 9333;
    private static final String S3_CONFIG_PATH = "/etc/seaweedfs/s3.json";
    private static final Logger LOG = Logger.getLogger(S3TestResource.class);

    /** Lock for starting the shared container so parallel test classes don't start multiple. */
    private static final Object START_LOCK = new Object();

    /** Singleton container shared across all test classes in this JVM. */
    private static GenericContainer<?> sharedContainer;

    /**
     * S3 configuration JSON for SeaweedFS authentication.
     * Without this config, SeaweedFS rejects signed requests from AWS SDK.
     */
    private static final String S3_CONFIG_JSON = """
            {
              "identities": [
                {
                  "name": "test_user",
                  "credentials": [
                    {
                      "accessKey": "%s",
                      "secretKey": "%s"
                    }
                  ],
                  "actions": ["Admin", "Read", "Write", "List", "Tagging"]
                }
              ]
            }
            """.formatted(ACCESS_KEY, SECRET_KEY);

    // Static field to share endpoint across all tests
    private static String sharedEndpoint;

    /** Instance reference to the shared container (for getEndpoint() etc.). */
    private GenericContainer<?> s3Container;

    @Override
    public Map<String, String> start() {
        synchronized (START_LOCK) {
            if (sharedContainer != null && sharedContainer.isRunning()) {
                s3Container = sharedContainer;
                String endpoint = "http://" + sharedContainer.getHost() + ":" + sharedContainer.getMappedPort(S3_PORT);
                sharedEndpoint = endpoint;
                LOG.info("=== S3TestResource (SeaweedFS) reusing existing container ===");
                LOG.info("endpoint = " + endpoint);
                System.setProperty("quarkus.s3.endpoint-override", endpoint);
                System.setProperty("quarkus.s3.path-style-access", "true");
                return configMap(endpoint);
            }

            GenericContainer<?> container = new GenericContainer<>(DockerImageName.parse(DEFAULT_IMAGE))
                    .withExposedPorts(S3_PORT, MASTER_PORT)
                    .withCopyToContainer(
                            Transferable.of(S3_CONFIG_JSON.getBytes(StandardCharsets.UTF_8)),
                            S3_CONFIG_PATH)
                    .withCommand("server", "-s3", "-filer", "-dir=/data",
                            "-master.volumeSizeLimitMB=128",
                            "-s3.allowEmptyFolder=true", "-s3.config=" + S3_CONFIG_PATH)
                    .waitingFor(Wait.forLogMessage(".*Start Seaweed S3 API Server.*", 1));

            container.start();
            sharedContainer = container;
            s3Container = container;

            String endpoint = "http://" + container.getHost() + ":" + container.getMappedPort(S3_PORT);
            sharedEndpoint = endpoint;

            createBucket(endpoint);

            LOG.info("=== S3TestResource (SeaweedFS) started (singleton) ===");
            LOG.info("endpoint = " + endpoint);

            System.setProperty("quarkus.s3.endpoint-override", endpoint);
            System.setProperty("quarkus.s3.path-style-access", "true");

            return configMap(endpoint);
        }
    }

    private static Map<String, String> configMap(String endpoint) {
        return Map.of(
                "quarkus.s3.devservices.enabled", "false",
                "quarkus.s3.endpoint-override", endpoint,
                "quarkus.s3.aws.region", "us-east-1",
                "quarkus.s3.aws.credentials.type", "static",
                "quarkus.s3.aws.credentials.static-provider.access-key-id", ACCESS_KEY,
                "quarkus.s3.aws.credentials.static-provider.secret-access-key", SECRET_KEY,
                "quarkus.s3.path-style-access", "true"
        );
    }

    private static void createBucket(String endpoint) {
        AwsBasicCredentials credentials = AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY);

        try (S3Client s3 = S3Client.builder()
                .credentialsProvider(StaticCredentialsProvider.create(credentials))
                .region(Region.of("us-east-1"))
                .endpointOverride(URI.create(endpoint))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build()) {
            // Create the bucket
            s3.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
            LOG.info("Created bucket: " + BUCKET);

            // Enable versioning on the bucket
            s3.putBucketVersioning(PutBucketVersioningRequest.builder()
                    .bucket(BUCKET)
                    .versioningConfiguration(VersioningConfiguration.builder()
                            .status(BucketVersioningStatus.ENABLED)
                            .build())
                    .build());
            LOG.info("Enabled versioning on bucket: " + BUCKET);
        } catch (Exception e) {
            LOG.error("Failed to create bucket", e);
            throw new RuntimeException(e);
        }
    }

    public String getEndpoint() {
        if (s3Container == null || !s3Container.isRunning()) {
            return null;
        }
        return "http://" + s3Container.getHost() + ":" + s3Container.getMappedPort(S3_PORT);
    }

    public static String getSharedEndpoint() {
        return sharedEndpoint;
    }

    public String getBucket() {
        return BUCKET;
    }

    public String getAccessKey() {
        return ACCESS_KEY;
    }

    public String getSecretKey() {
        return SECRET_KEY;
    }

    @Override
    public void stop() {
        // Intentionally do not stop the container so other test classes in this JVM can reuse it.
        // The container is cleaned up when the JVM exits (or by Ryuk).
    }
}
