package ai.pipestream.test.support;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.ListBucketsResponse;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
public class S3DevServiceTest {

    @Test
    public void testS3ClientInjectionAndOperation() {
        S3Client s3Client = buildClient();
        assertNotNull(s3Client, "S3Client should be created");

        String bucketName = "devservices-test-bucket";

        try (s3Client) {
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

    private static S3Client buildClient() {
        Config config = ConfigProvider.getConfig();
        String endpoint = config.getValue("quarkus.s3.endpoint-override", String.class);
        String region = config.getOptionalValue("quarkus.s3.aws.region", String.class).orElse("us-east-1");
        String accessKey = config.getValue("quarkus.s3.aws.credentials.static-provider.access-key-id", String.class);
        String secretKey = config.getValue("quarkus.s3.aws.credentials.static-provider.secret-access-key", String.class);

        AwsBasicCredentials credentials = AwsBasicCredentials.create(accessKey, secretKey);
        return S3Client.builder()
                .credentialsProvider(StaticCredentialsProvider.create(credentials))
                .region(Region.of(region))
                .endpointOverride(URI.create(endpoint))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();
    }
}
