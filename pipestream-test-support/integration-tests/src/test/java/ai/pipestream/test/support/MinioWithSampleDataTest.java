package ai.pipestream.test.support;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.io.InputStream;
import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test to verify that {@link MinioWithSampleDataTestResource} correctly uploads
 * sample data from test-documents jar to MinIO.
 * <p>
 * This test validates the fixture itself to ensure it works correctly before
 * being used in other services.
 * </p>
 *
 * <h2>Prerequisites</h2>
 * <p>
 * The sample-documents must be published to mavenLocal first:
 * </p>
 * <pre>
 * cd /work/sample-documents/sample-documents
 * ./gradlew publishAllToMavenLocal
 * </pre>
 *
 * @since 1.0.0
 */
class MinioWithSampleDataTest {

    private static MinioWithSampleDataTestResource testResource;

    @BeforeAll
    static void setupMinio() {
        testResource = new MinioWithSampleDataTestResource();
        testResource.start();
    }

    @AfterAll
    static void teardownMinio() {
        if (testResource != null) {
            testResource.stop();
        }
    }

    @Test
    void testMinioHasSampleData() throws Exception {
        // Verify MinIO started and has sample data
        String endpoint = MinioTestResource.getSharedEndpoint();
        assertNotNull(endpoint, "MinIO endpoint should be available");

        // Create S3 client
        AwsBasicCredentials credentials = AwsBasicCredentials.create(
                MinioTestResource.ACCESS_KEY,
                MinioTestResource.SECRET_KEY
        );

        try (S3Client s3 = S3Client.builder()
                .credentialsProvider(StaticCredentialsProvider.create(credentials))
                .region(Region.of("us-east-1"))
                .endpointOverride(URI.create(endpoint))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(true)
                        .build())
                .build()) {

            // List objects in test bucket
            ListObjectsV2Request listRequest = ListObjectsV2Request.builder()
                    .bucket(MinioTestResource.BUCKET)
                    .build();

            ListObjectsV2Response listResponse = s3.listObjectsV2(listRequest);
            List<S3Object> objects = listResponse.contents();

            // Verify we have files from test-documents
            assertFalse(objects.isEmpty(), "MinIO should contain sample files from test-documents jar");

            System.out.println("=== Sample files in MinIO ===");
            objects.stream()
                    .limit(10)  // Show first 10 files
                    .forEach(obj -> System.out.printf("  %s (%d bytes)%n", obj.key(), obj.size()));

            if (objects.size() > 10) {
                System.out.printf("  ... and %d more files%n", objects.size() - 10);
            }
            System.out.printf("Total: %d files%n", objects.size());

            // Verify we can download a specific file
            // test-documents contains sample_text/sample.txt
            String expectedKey = "sample_text/sample.txt";
            boolean foundSampleFile = objects.stream()
                    .anyMatch(obj -> obj.key().equals(expectedKey));

            assertTrue(foundSampleFile,
                    "Should find " + expectedKey + " from test-documents jar. " +
                    "Available files: " + objects.stream()
                            .map(S3Object::key)
                            .limit(5)
                            .toList());

            // Download and verify content
            GetObjectRequest getRequest = GetObjectRequest.builder()
                    .bucket(MinioTestResource.BUCKET)
                    .key(expectedKey)
                    .build();

            try (InputStream inputStream = s3.getObject(getRequest)) {
                byte[] content = inputStream.readAllBytes();
                assertTrue(content.length > 0, "Downloaded file should have content");
                System.out.printf("Successfully downloaded %s (%d bytes)%n",
                        expectedKey, content.length);
            }
        }
    }

    @Test
    void testSpecificSampleFiles() throws Exception {
        String endpoint = MinioTestResource.getSharedEndpoint();
        assertNotNull(endpoint);

        AwsBasicCredentials credentials = AwsBasicCredentials.create(
                MinioTestResource.ACCESS_KEY,
                MinioTestResource.SECRET_KEY
        );

        try (S3Client s3 = S3Client.builder()
                .credentialsProvider(StaticCredentialsProvider.create(credentials))
                .region(Region.of("us-east-1"))
                .endpointOverride(URI.create(endpoint))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(true)
                        .build())
                .build()) {

            // Test-documents structure based on what we saw:
            // sample_text/, sample_office_files/, sample_image/, etc.
            String[] expectedDirectories = {
                    "sample_text",
                    "sample_office_files",
                    "sample_image",
                    "sample_video"
            };

            ListObjectsV2Request listRequest = ListObjectsV2Request.builder()
                    .bucket(MinioTestResource.BUCKET)
                    .build();

            ListObjectsV2Response listResponse = s3.listObjectsV2(listRequest);
            List<String> allKeys = listResponse.contents().stream()
                    .map(S3Object::key)
                    .toList();

            // Verify we have files from expected directories
            for (String dir : expectedDirectories) {
                boolean hasFilesInDir = allKeys.stream()
                        .anyMatch(key -> key.startsWith(dir + "/"));

                assertTrue(hasFilesInDir,
                        "Should have files in " + dir + " directory. Available keys: " +
                        allKeys.stream().limit(10).toList());
            }

            System.out.println("=== Verified sample data structure ===");
            for (String dir : expectedDirectories) {
                long count = allKeys.stream()
                        .filter(key -> key.startsWith(dir + "/"))
                        .count();
                System.out.printf("  %s: %d files%n", dir, count);
            }
        }
    }
}
