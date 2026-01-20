package ai.pipestream.test.support;

import org.testcontainers.containers.GenericContainer;

import java.util.Map;

/**
 * Mock resource for KMS (Key Management Service) tests using WireMock.
 * <p>
 * Provides mock KMS endpoints for fast unit testing of credential resolution and secret management.
 * Supports both Infisical-style and AWS KMS-style reference formats.
 * </p>
 *
 * <h2>Use Case</h2>
 * <p><strong>Unit Testing:</strong> Use this for fast, isolated tests that don't need real KMS integration.</p>
 * <p><strong>For Integration Testing:</strong> Use {@link KmsTestResource} instead.</p>
 *
 * <h2>Mock Endpoints</h2>
 * <ul>
 *   <li>{@code GET /api/v1/secrets/raw/{reference}} - Infisical-style secret retrieval</li>
 *   <li>{@code POST /kms/retrieve} - AWS KMS-style secret retrieval</li>
 * </ul>
 *
 * <h2>Test Data</h2>
 * <p>
 * Pre-configured with common test secrets:
 * </p>
 * <ul>
 *   <li>{@code kms://dev/s3/access-key} → {@code test-access-key}</li>
 *   <li>{@code kms://dev/s3/secret-key} → {@code test-secret-key}</li>
 *   <li>{@code kms://dev/api/key} → {@code test-api-key}</li>
 *   <li>AWS KMS references → corresponding test values</li>
 * </ul>
 */
public class KmsMockTestResource extends BaseWireMockTestResource {

    @Override
    protected void configureContainer(GenericContainer<?> container) {
        // Configure WireMock with KMS-specific environment
        container.withEnv("WIREMOCK_KMS_ENABLED", "true");
    }

    @Override
    protected Map<String, String> buildConfig(GenericContainer<?> container) {
        String host = getHost();
        String port = String.valueOf(getMappedPort(DEFAULT_HTTP_PORT));

        Map<String, String> config = new java.util.HashMap<>();

        // Configure KMS service endpoints for testing
        config.put("kms.api.url", "http://" + host + ":" + port);
        config.put("kms.api.token", "test-token");

        // AWS KMS endpoint (if supported)
        config.put("aws.kms.endpoint", "http://" + host + ":" + port);

        // WireMock connection details for direct API calls if needed
        config.put("wiremock.kms.host", host);
        config.put("wiremock.kms.port", port);

        return config;
    }
}