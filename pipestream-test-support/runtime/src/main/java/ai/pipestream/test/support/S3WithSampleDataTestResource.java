package ai.pipestream.test.support;

import org.jboss.logging.Logger;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Extended S3 test resource that automatically populates S3 with sample data from jar files.
 * <p>
 * This resource extends {@link S3TestResource} and adds functionality to upload files from
 * sample-data jar artifacts (like test-documents, sample-doc-types) to S3 after it starts.
 * </p>
 *
 * <h2>Usage</h2>
 * <pre>
 * &#64;QuarkusTest
 * &#64;QuarkusTestResource(S3WithSampleDataTestResource.class)
 * class MyTest {
 *     // S3 is now pre-populated with test-documents
 * }
 * </pre>
 *
 * <h2>Prerequisites</h2>
 * <p>
 * The sample-data artifacts must be published to mavenLocal first:
 * </p>
 * <pre>
 * cd /work/sample-documents/sample-documents
 * ./gradlew publishAllToMavenLocal
 * </pre>
 *
 * <h2>Customization</h2>
 * <p>
 * Override {@link #getSampleDataArtifacts()} to specify which jars to load.
 * Override {@link #uploadSampleData(S3Client, String)} to customize upload behavior.
 * </p>
 *
 * @since 1.0.0
 */
public class S3WithSampleDataTestResource extends S3TestResource {

    private static final Logger LOG = Logger.getLogger(S3WithSampleDataTestResource.class);

    /** Whether sample data has already been uploaded to the shared container (upload once per JVM). */
    private static volatile boolean sampleDataUploaded;

    private static final Object UPLOAD_LOCK = new Object();

    /**
     * Returns the list of sample-data artifacts to load into S3.
     * <p>
     * Default is just "test-documents". Override to add more artifacts:
     * </p>
     * <pre>
     * protected String[] getSampleDataArtifacts() {
     *     return new String[]{"test-documents", "sample-doc-types"};
     * }
     * </pre>
     */
    protected String[] getSampleDataArtifacts() {
        return new String[]{"test-documents"};
    }

    /**
     * Returns the S3 key prefix for uploaded files.
     * Default is empty (root of bucket).
     */
    protected String getUploadPrefix() {
        return "";
    }

    /**
     * Whether to preserve directory structure from jar when uploading.
     * Default is true (e.g., sample_text/sample.txt -> sample_text/sample.txt in S3).
     */
    protected boolean preserveDirectoryStructure() {
        return true;
    }

    @Override
    public Map<String, String> start() {
        // Start S3 (LocalStack/DevServices) first (or reuse singleton)
        Map<String, String> config = super.start();

        // Upload sample data once per JVM when using the shared container
        String endpoint = getSharedEndpoint();
        if (endpoint != null) {
            synchronized (UPLOAD_LOCK) {
                if (!sampleDataUploaded) {
                    try {
                        uploadSampleDataToS3(endpoint);
                        sampleDataUploaded = true;
                    } catch (Exception e) {
                        LOG.errorf(e, "Failed to upload sample data to S3");
                        // Don't fail the test - just log the error
                        // Tests can still run, they just won't have pre-populated data
                    }
                }
            }
        }

        return config;
    }

    private void uploadSampleDataToS3(String endpoint) {
        LOG.infof("Uploading sample data to S3 at %s", endpoint);

        // Create S3 client
        AwsBasicCredentials credentials = AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY);
        try (S3Client s3Client = S3Client.builder()
                .credentialsProvider(StaticCredentialsProvider.create(credentials))
                .region(Region.of("us-east-1"))
                .endpointOverride(URI.create(endpoint))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(true)
                        .build())
                .build()) {

            // Upload each sample-data artifact
            for (String artifact : getSampleDataArtifacts()) {
                try {
                    uploadSampleData(s3Client, artifact);
                } catch (Exception e) {
                    LOG.warnf(e, "Failed to upload sample data from artifact: %s", artifact);
                }
            }

            LOG.infof("Successfully uploaded sample data to S3 bucket: %s", BUCKET);
        }
    }

    /**
     * Uploads files from a sample-data artifact to S3 by scanning the classpath.
     * <p>
     * This method finds the artifact's resources on the classpath and uploads all files
     * (except build artifacts and metadata) to the S3 test bucket.
     * </p>
     *
     * @param s3Client the S3 client to use for uploads
     * @param artifactName the artifact name (e.g., "test-documents", "sample-doc-types")
     * @throws Exception if upload fails
     */
    protected void uploadSampleData(S3Client s3Client, String artifactName) throws Exception {
        LOG.infof("Loading sample data from artifact: %s", artifactName);

        // Find a known resource to locate the artifact on the classpath
        // We look for a marker directory that should exist in the sample-data artifacts
        String markerResource = "sample_text";
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        URL resourceUrl = classLoader.getResource(markerResource);

        if (resourceUrl == null) {
            LOG.warnf("Sample data not found on classpath for artifact: %s (marker: %s). " +
                    "Ensure the artifact is declared as a dependency.", artifactName, markerResource);
            return;
        }

        LOG.debugf("Found sample data at: %s", resourceUrl);

        int uploadedCount = 0;

        try {
            URI uri = resourceUrl.toURI();
            Path rootPath;
            FileSystem fileSystem = null;

            if ("jar".equals(uri.getScheme())) {
                // Resource is inside a JAR - need to create a FileSystem to walk it
                String[] parts = uri.toString().split("!");
                URI jarUri = URI.create(parts[0]);
                fileSystem = FileSystems.newFileSystem(jarUri, Collections.emptyMap());
                rootPath = fileSystem.getPath("/");
            } else {
                // Resource is on the file system (e.g., during development)
                rootPath = Path.of(uri).getParent();
            }

            try (Stream<Path> paths = Files.walk(rootPath)) {
                for (Path path : (Iterable<Path>) paths::iterator) {
                    if (Files.isDirectory(path)) {
                        continue;
                    }

                    String relativePath = rootPath.relativize(path).toString();

                    // Skip metadata and build files
                    if (relativePath.startsWith("META-INF/") ||
                        relativePath.startsWith("build/") ||
                        relativePath.contains(".gradle") ||
                        relativePath.endsWith(".class")) {
                        continue;
                    }

                    // Construct S3 key
                    String s3Key = constructS3Key(relativePath);

                    // Upload file
                    try (InputStream inputStream = Files.newInputStream(path)) {
                        byte[] fileContent = inputStream.readAllBytes();

                        PutObjectRequest putRequest = PutObjectRequest.builder()
                                .bucket(BUCKET)
                                .key(s3Key)
                                .contentLength((long) fileContent.length)
                                .build();

                        s3Client.putObject(putRequest, RequestBody.fromBytes(fileContent));
                        uploadedCount++;
                        LOG.debugf("Uploaded: %s -> s3://%s/%s (%d bytes)",
                                relativePath, BUCKET, s3Key, fileContent.length);
                    }
                }
            } finally {
                if (fileSystem != null) {
                    fileSystem.close();
                }
            }
        } catch (URISyntaxException | IOException e) {
            LOG.errorf(e, "Failed to process sample data from classpath");
            throw e;
        }

        LOG.infof("Uploaded %d files from %s to S3", uploadedCount, artifactName);
    }

    /**
     * Constructs the S3 key for a resource path.
     */
    private String constructS3Key(String resourcePath) {
        String prefix = getUploadPrefix();
        // Normalize path separators
        String normalizedPath = resourcePath.replace(File.separatorChar, '/');

        if (!preserveDirectoryStructure()) {
            // Extract just the filename
            int lastSlash = normalizedPath.lastIndexOf('/');
            String fileName = lastSlash >= 0 ? normalizedPath.substring(lastSlash + 1) : normalizedPath;
            return prefix.isEmpty() ? fileName : prefix + "/" + fileName;
        }

        // Preserve full path
        return prefix.isEmpty() ? normalizedPath : prefix + "/" + normalizedPath;
    }
}
