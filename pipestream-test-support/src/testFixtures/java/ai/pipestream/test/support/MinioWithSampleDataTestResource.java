package ai.pipestream.test.support;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import org.jboss.logging.Logger;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Enumeration;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Extended MinIO test resource that automatically populates MinIO with sample data from jar files.
 * <p>
 * This resource extends {@link MinioTestResource} and adds functionality to upload files from
 * sample-data jar artifacts (like test-documents, sample-doc-types) to MinIO after it starts.
 * </p>
 *
 * <h2>Usage</h2>
 * <pre>
 * &#64;QuarkusTest
 * &#64;QuarkusTestResource(MinioWithSampleDataTestResource.class)
 * class MyTest {
 *     // MinIO is now pre-populated with test-documents
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
public class MinioWithSampleDataTestResource extends MinioTestResource {

    private static final Logger LOG = Logger.getLogger(MinioWithSampleDataTestResource.class);

    /**
     * Returns the list of sample-data artifacts to load into MinIO.
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
        // Start MinIO first
        Map<String, String> config = super.start();

        // Upload sample data
        String endpoint = getSharedEndpoint();
        if (endpoint != null) {
            try {
                uploadSampleDataToMinio(endpoint);
            } catch (Exception e) {
                LOG.errorf(e, "Failed to upload sample data to MinIO");
                // Don't fail the test - just log the error
                // Tests can still run, they just won't have pre-populated data
            }
        }

        return config;
    }

    private void uploadSampleDataToMinio(String endpoint) {
        LOG.infof("Uploading sample data to MinIO at %s", endpoint);

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

            LOG.infof("Successfully uploaded sample data to MinIO bucket: %s", BUCKET);
        }
    }

    /**
     * Uploads files from a sample-data jar artifact to MinIO.
     * <p>
     * This method locates the jar on the classpath, extracts all files (except build artifacts),
     * and uploads them to the MinIO test bucket.
     * </p>
     *
     * @param s3Client the S3 client to use for uploads
     * @param artifactName the artifact name (e.g., "test-documents", "sample-doc-types")
     * @throws Exception if upload fails
     */
    protected void uploadSampleData(S3Client s3Client, String artifactName) throws Exception {
        LOG.infof("Loading sample data from artifact: %s", artifactName);

        // Find the jar on the classpath
        String jarPath = findSampleDataJar(artifactName);
        if (jarPath == null) {
            LOG.warnf("Sample data jar not found for artifact: %s. " +
                    "Make sure to run: cd /work/sample-documents/sample-documents && ./gradlew publishAllToMavenLocal",
                    artifactName);
            return;
        }

        LOG.debugf("Found sample data jar: %s", jarPath);

        int uploadedCount = 0;
        // Open jar and upload files
        try (JarFile jarFile = new JarFile(jarPath)) {
            Enumeration<JarEntry> entries = jarFile.entries();

            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String entryName = entry.getName();

                // Skip directories, build files, and metadata
                if (entry.isDirectory() ||
                    entryName.startsWith("META-INF/") ||
                    entryName.startsWith("build/") ||
                    entryName.contains(".gradle") ||
                    entryName.endsWith(".class")) {
                    continue;
                }

                // Construct S3 key
                String s3Key = constructS3Key(entryName);

                // Upload file
                try (InputStream inputStream = jarFile.getInputStream(entry)) {
                    byte[] fileContent = inputStream.readAllBytes();

                    PutObjectRequest putRequest = PutObjectRequest.builder()
                            .bucket(BUCKET)
                            .key(s3Key)
                            .contentLength((long) fileContent.length)
                            .build();

                    s3Client.putObject(putRequest, RequestBody.fromBytes(fileContent));
                    uploadedCount++;
                    LOG.debugf("Uploaded: %s -> s3://%s/%s (%d bytes)",
                            entryName, BUCKET, s3Key, fileContent.length);
                }
            }
        }

        LOG.infof("Uploaded %d files from %s to MinIO", uploadedCount, artifactName);
    }

    /**
     * Constructs the S3 key for a jar entry.
     */
    private String constructS3Key(String jarEntryName) {
        String prefix = getUploadPrefix();

        if (!preserveDirectoryStructure()) {
            // Extract just the filename
            int lastSlash = jarEntryName.lastIndexOf('/');
            String fileName = lastSlash >= 0 ? jarEntryName.substring(lastSlash + 1) : jarEntryName;
            return prefix.isEmpty() ? fileName : prefix + "/" + fileName;
        }

        // Preserve full path
        return prefix.isEmpty() ? jarEntryName : prefix + "/" + jarEntryName;
    }

    /**
     * Finds the sample-data jar file on the classpath.
     * <p>
     * This method searches for jars matching the artifact pattern in the classpath.
     * The jar must be published to mavenLocal first.
     * </p>
     *
     * @param artifactName the artifact name (e.g., "test-documents")
     * @return the path to the jar file, or null if not found
     */
    private String findSampleDataJar(String artifactName) {
        try {
            // The jar should be on the classpath after publishToMavenLocal
            // Pattern: ai/pipestream/{artifactName}/{version}/{artifactName}-{version}.jar

            // Try to find it via classpath resource
            // The jar should contain a marker file or we can search the classpath URLs
            ClassLoader classLoader = Thread.currentThread().getContextClassLoader();

            // Get all URLs from classpath
            Enumeration<URL> resources = classLoader.getResources("");
            while (resources.hasMoreElements()) {
                URL url = resources.nextElement();
                String path = url.getPath();

                // Check if this is a jar file matching our artifact
                if (path.contains(artifactName) && path.endsWith(".jar!/")) {
                    // Extract jar path (remove the !/ suffix)
                    String jarPath = path.substring("file:".length(), path.length() - 2);
                    if (jarPath.contains(artifactName)) {
                        return jarPath;
                    }
                }
            }

            // Alternative: check mavenLocal directly
            String home = System.getProperty("user.home");
            Path mavenLocal = Paths.get(home, ".m2", "repository", "ai", "pipestream", artifactName);
            if (mavenLocal.toFile().exists()) {
                // Find the latest version directory
                java.io.File[] versionDirs = mavenLocal.toFile().listFiles(java.io.File::isDirectory);
                if (versionDirs != null && versionDirs.length > 0) {
                    // Use the first version found (in production, you'd want to be more specific)
                    java.io.File versionDir = versionDirs[0];
                    java.io.File[] jars = versionDir.listFiles((dir, name) ->
                        name.endsWith(".jar") && !name.endsWith("-sources.jar") && !name.endsWith("-javadoc.jar"));

                    if (jars != null && jars.length > 0) {
                        return jars[0].getAbsolutePath();
                    }
                }
            }

        } catch (Exception e) {
            LOG.debugf(e, "Error finding sample data jar for: %s", artifactName);
        }

        return null;
    }
}
