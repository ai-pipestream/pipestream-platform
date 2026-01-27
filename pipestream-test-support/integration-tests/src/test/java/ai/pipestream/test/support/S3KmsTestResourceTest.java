package ai.pipestream.test.support;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

import java.net.URI;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class S3KmsTestResourceTest {

    @Test
    void startsS3AndCreatesBucket() {
        S3TestResource resource = new S3TestResource();
        Map<String, String> config = resource.start();

        try {
            String endpoint = config.get("quarkus.s3.endpoint-override");
            assertNotNull(endpoint);
            assertEquals("us-east-1", config.get("quarkus.s3.aws.region"));
            assertEquals(resource.getAccessKey(),
                    config.get("quarkus.s3.aws.credentials.static-provider.access-key-id"));
            assertEquals(resource.getSecretKey(),
                    config.get("quarkus.s3.aws.credentials.static-provider.secret-access-key"));
            assertEquals("true", config.get("quarkus.s3.path-style-access"));

            AwsBasicCredentials credentials = AwsBasicCredentials.create(resource.getAccessKey(), resource.getSecretKey());
            try (S3Client s3 = S3Client.builder()
                    .credentialsProvider(StaticCredentialsProvider.create(credentials))
                    .region(Region.of("us-east-1"))
                    .endpointOverride(URI.create(endpoint))
                    .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                    .build()) {
                boolean bucketExists = s3.listBuckets().buckets().stream()
                        .anyMatch(bucket -> bucket.name().equals(resource.getBucket()));
                assertTrue(bucketExists);
            }
        } finally {
            resource.stop();
        }
    }

}