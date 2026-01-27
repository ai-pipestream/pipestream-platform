package ai.pipestream.test.support;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.ListBucketsResponse;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
public class S3DevServiceTest {

    @Inject
    S3Client s3Client;

    @Test
    public void testS3ClientInjectionAndOperation() {
        assertNotNull(s3Client, "S3Client should be injected");

        String bucketName = "devservices-test-bucket";
        
        // Create a bucket to verify interaction
        s3Client.createBucket(CreateBucketRequest.builder().bucket(bucketName).build());

        // List buckets
        ListBucketsResponse response = s3Client.listBuckets();
        assertNotNull(response);
        
        boolean bucketExists = response.buckets().stream()
                .anyMatch(b -> b.name().equals(bucketName));
        
        assertTrue(bucketExists, "Created bucket should be listed");
    }
}
