package ai.pipestream.test.support;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import org.jboss.logging.Logger;

/**
 * Shared test resource for setting up MinIO container to simulate AWS S3 API.
 * <p>
 * Provides MinIO instance for testing S3-compatible operations.
 * Can be used by any service that needs S3 testing infrastructure.
 */
public class MinioTestResource implements QuarkusTestResourceLifecycleManager {

    private static final String DEFAULT_IMAGE = "chainguard/minio:latest";
    private static final String ACCESS_KEY = "testuser";
    private static final String SECRET_KEY = "testpassword";
    private static final String BUCKET = "test-bucket";
    private static final Logger LOG = Logger.getLogger(MinioTestResource.class);

    private MinIOContainer minio;

    @Override
    public Map<String, String> start() {
        minio = new MinIOContainer(DockerImageName.parse(DEFAULT_IMAGE).asCompatibleSubstituteFor("minio/minio"))
                .withUserName(ACCESS_KEY)
                .withPassword(SECRET_KEY);
        minio.start();

        String endpoint = minio.getS3URL();

        // #region agent log
        debugLog("H1", "MinioTestResource.java:start", "minio_started",
                "{\"endpoint\":\"" + jsonEscape(endpoint) + "\",\"bucket\":\"" + BUCKET + "\",\"image\":\"" + jsonEscape(DEFAULT_IMAGE) + "\"}");
        // #endregion

        createBucket(endpoint);

        return Map.of(
                "quarkus.s3.endpoint-override", endpoint,
                "quarkus.s3.aws.region", "us-east-1",
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
            s3.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
            // #region agent log
            debugLog("H2", "MinioTestResource.java:createBucket", "bucket_created",
                    "{\"bucket\":\"" + BUCKET + "\",\"endpoint\":\"" + jsonEscape(endpoint) + "\"}");
            // #endregion
        } catch (Exception e) {
            // #region agent log
            debugLog("H2", "MinioTestResource.java:createBucket", "bucket_create_failed",
                    "{\"bucket\":\"" + BUCKET + "\",\"endpoint\":\"" + jsonEscape(endpoint) + "\",\"exception\":\"" + e.getClass().getSimpleName() + "\"}");
            // #endregion
            throw e;
        }
    }

    /**
     * Get the MinIO endpoint URL.
     *
     * @return endpoint URL or null if container is not running
     */
    public String getEndpoint() {
        if (minio == null || !minio.isRunning()) {
            return null;
        }
        return minio.getS3URL();
    }

    /**
     * Get the default test bucket name.
     *
     * @return bucket name
     */
    public String getBucket() {
        return BUCKET;
    }

    /**
     * Get the MinIO access key.
     *
     * @return access key
     */
    public String getAccessKey() {
        return ACCESS_KEY;
    }

    /**
     * Get the MinIO secret key.
     *
     * @return secret key
     */
    public String getSecretKey() {
        return SECRET_KEY;
    }

    @Override
    public void stop() {
        if (minio != null) {
            minio.stop();
        }
    }

    private static void debugLog(String hypothesisId, String location, String message, String dataJson) {
        LOG.debugf("debug.%s %s %s", hypothesisId, location, message);
        String payload = "{\"sessionId\":\"debug-session\",\"runId\":\"pre-fix\",\"hypothesisId\":\""
                + jsonEscape(hypothesisId) + "\",\"location\":\"" + jsonEscape(location)
                + "\",\"message\":\"" + jsonEscape(message) + "\",\"data\":" + dataJson
                + ",\"timestamp\":" + System.currentTimeMillis() + "}\n";
        try {
            Files.writeString(Path.of("/work/core-services/connector-admin/.cursor/debug.log"),
                    payload, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignored) {
        }
    }

    private static String jsonEscape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
