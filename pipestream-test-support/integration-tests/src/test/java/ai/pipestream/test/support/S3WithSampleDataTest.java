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
 * Test to verify that {@link S3WithSampleDataTestResource} correctly uploads
 * sample data from test-documents jar to S3.
 */
class S3WithSampleDataTest {

    private static S3WithSampleDataTestResource testResource;

    @BeforeAll
    static void setupS3() {
        testResource = new S3WithSampleDataTestResource();
        testResource.start();
    }

    @AfterAll
    static void teardownS3() {
        if (testResource != null) {
            testResource.stop();
        }
    }

    @Test
    void testS3HasSampleData() throws Exception {
        // Verify S3 started and has sample data
        String endpoint = S3TestResource.getSharedEndpoint();
        assertNotNull(endpoint, "S3 endpoint should be available");

        // Create S3 client
        AwsBasicCredentials credentials = AwsBasicCredentials.create(
                testResource.getAccessKey(),
                testResource.getSecretKey()
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
                    .bucket(testResource.getBucket())
                    .build();

            ListObjectsV2Response listResponse = s3.listObjectsV2(listRequest);
            List<S3Object> objects = listResponse.contents();

            // Verify we have files from test-documents
            assertFalse(objects.isEmpty(), "S3 should contain sample files from test-documents jar");

            System.out.println("=== Sample files in S3 ===");
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
                    .bucket(testResource.getBucket())
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
        String endpoint = S3TestResource.getSharedEndpoint();
        assertNotNull(endpoint);

        AwsBasicCredentials credentials = AwsBasicCredentials.create(
                testResource.getAccessKey(),
                testResource.getSecretKey()
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
                    .bucket(testResource.getBucket())
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
        }
    }
}