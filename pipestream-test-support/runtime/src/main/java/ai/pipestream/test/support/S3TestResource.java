package ai.pipestream.test.support;

import io.quarkus.test.common.DevServicesContext;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import org.jboss.logging.Logger;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
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
import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Shared test resource for setting up S3 test data using LocalStack dev services.
 * <p>
 * Prefers Quarkus DevServices (LocalStack) when available. Falls back to a shared LocalStack
 * container when DevServices are not running (e.g., plain unit tests).
 */
public class S3TestResource implements QuarkusTestResourceLifecycleManager, DevServicesContext.ContextAware {

    private static final String LOCALSTACK_IMAGE = "localstack/localstack:3.7.2";
    private static final int LOCALSTACK_PORT = 4566;
    public static final String ACCESS_KEY = "test";
    public static final String SECRET_KEY = "test";
    public static final String BUCKET = "test-bucket";
    private static final Logger LOG = Logger.getLogger(S3TestResource.class);

    /** Lock for starting the shared container so parallel test classes don't start multiple. */
    private static final Object START_LOCK = new Object();

    /** Singleton container shared across all test classes in this JVM (fallback only). */
    private static GenericContainer<?> sharedContainer;

    // Static field to share endpoint across all tests
    private static String sharedEndpoint;

    private DevServicesContext devServicesContext;

    @Override
    public void setIntegrationTestContext(DevServicesContext context) {
        this.devServicesContext = context;
    }

    @Override
    public Map<String, String> start() {
        synchronized (START_LOCK) {
            String devServicesEndpoint = resolveDevServicesEndpoint();
            if (devServicesEndpoint != null) {
                sharedEndpoint = devServicesEndpoint;
                ensureBucket(devServicesEndpoint);
                LOG.info("=== S3TestResource using DevServices LocalStack ===");
                LOG.info("endpoint = " + devServicesEndpoint);
                return Collections.emptyMap();
            }

            if (sharedContainer != null && sharedContainer.isRunning()) {
                String endpoint = "http://" + sharedContainer.getHost() + ":" + sharedContainer.getMappedPort(LOCALSTACK_PORT);
                sharedEndpoint = endpoint;
                LOG.info("=== S3TestResource reusing fallback LocalStack container ===");
                LOG.info("endpoint = " + endpoint);
                return configMap(endpoint);
            }

            GenericContainer<?> container = new GenericContainer<>(DockerImageName.parse(LOCALSTACK_IMAGE))
                    .withEnv("SERVICES", "s3")
                    .withEnv("DEFAULT_REGION", "us-east-1")
                    .withEnv("AWS_ACCESS_KEY_ID", ACCESS_KEY)
                    .withEnv("AWS_SECRET_ACCESS_KEY", SECRET_KEY)
                    .withExposedPorts(LOCALSTACK_PORT)
                    .waitingFor(Wait.forLogMessage(".*Ready\\..*", 1))
                    .withStartupTimeout(Duration.ofMinutes(2));

            container.start();
            sharedContainer = container;
            String endpoint = "http://" + container.getHost() + ":" + container.getMappedPort(LOCALSTACK_PORT);
            sharedEndpoint = endpoint;

            ensureBucket(endpoint);

            LOG.info("=== S3TestResource started fallback LocalStack (singleton) ===");
            LOG.info("endpoint = " + endpoint);

            return configMap(endpoint);
        }
    }

    private static Map<String, String> configMap(String endpoint) {
        return Map.of(
                "quarkus.s3.endpoint-override", endpoint,
                "quarkus.s3.aws.region", "us-east-1",
                "quarkus.s3.aws.credentials.type", "static",
                "quarkus.s3.aws.credentials.static-provider.access-key-id", ACCESS_KEY,
                "quarkus.s3.aws.credentials.static-provider.secret-access-key", SECRET_KEY,
                "quarkus.s3.path-style-access", "true"
        );
    }

    private void ensureBucket(String endpoint) {
        AwsBasicCredentials credentials = AwsBasicCredentials.create(resolveAccessKey(), resolveSecretKey());
        String region = resolveRegion();
        boolean pathStyleAccess = resolvePathStyleAccess();

        for (int attempt = 1; attempt <= 5; attempt++) {
            try (S3Client s3 = S3Client.builder()
                    .credentialsProvider(StaticCredentialsProvider.create(credentials))
                    .region(Region.of(region))
                    .endpointOverride(URI.create(endpoint))
                    .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(pathStyleAccess).build())
                    .build()) {
                boolean exists = s3.listBuckets().buckets().stream()
                        .anyMatch(bucket -> bucket.name().equals(BUCKET));
                if (!exists) {
                    s3.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
                    LOG.info("Created bucket: " + BUCKET);
                }

                s3.putBucketVersioning(PutBucketVersioningRequest.builder()
                        .bucket(BUCKET)
                        .versioningConfiguration(VersioningConfiguration.builder()
                            .status(BucketVersioningStatus.ENABLED)
                            .build())
                        .build());
                LOG.info("Enabled versioning on bucket: " + BUCKET);
                return;
            } catch (Exception e) {
                if (attempt == 5) {
                    LOG.error("Failed to create bucket after retries", e);
                    throw new RuntimeException(e);
                }
                sleepQuietly(500);
            }
        }
    }

    public String getEndpoint() {
        return sharedEndpoint;
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

    private String resolveDevServicesEndpoint() {
        if (devServicesContext == null) {
            return null;
        }
        String endpoint = devServicesContext.devServicesProperties().get("quarkus.s3.endpoint-override");
        if (endpoint != null && !endpoint.isBlank()) {
            return endpoint;
        }
        return null;
    }

    private String resolveAccessKey() {
        return resolveDevServicesProperty("quarkus.s3.aws.credentials.static-provider.access-key-id")
                .orElse(ACCESS_KEY);
    }

    private String resolveSecretKey() {
        return resolveDevServicesProperty("quarkus.s3.aws.credentials.static-provider.secret-access-key")
                .orElse(SECRET_KEY);
    }

    private String resolveRegion() {
        return resolveDevServicesProperty("quarkus.s3.aws.region")
                .orElse("us-east-1");
    }

    private boolean resolvePathStyleAccess() {
        return resolveDevServicesProperty("quarkus.s3.path-style-access")
                .map(Boolean::parseBoolean)
                .orElse(true);
    }

    private Optional<String> resolveDevServicesProperty(String key) {
        if (devServicesContext == null) {
            return Optional.empty();
        }
        String value = devServicesContext.devServicesProperties().get(key);
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(value);
    }

    private void sleepQuietly(long millis) {
        try {
            TimeUnit.MILLISECONDS.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
