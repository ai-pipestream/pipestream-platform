package ai.pipestream.test.support;

import org.testcontainers.containers.GenericContainer;

import java.util.Map;

/**
 * WireMock resource for pipestream-engine tests.
 */
public class EngineWireMockTestResource extends BaseWireMockTestResource {

    @Override
    protected Map<String, String> buildConfig(GenericContainer<?> container) {
        // NOTE: pipestream-wiremock-server exposes multiple endpoints:
        // - Port 8080: gRPC server exposing many services incl. AccountService, modules (reflection-enabled)
        // - Port 50052: "Direct" streaming gRPC server (used for large streaming, and registration in some tests)
        String host = getHost();
        String standardPort = String.valueOf(getMappedPort(DEFAULT_HTTP_PORT));
        String directPort = String.valueOf(getMappedPort(DEFAULT_GRPC_PORT));
        String moduleAddress = host + ":" + standardPort;

        // Build configuration map - need more than 10 entries so use HashMap
        java.util.Map<String, String> config = new java.util.HashMap<>();

        // Stork static discovery for dynamic-grpc module services
        // Service names must match what ModuleCapabilityService uses (moduleId or grpcServiceName from graph)
        // For tests, we use the module ID directly as the service name
        config.put("stork.tika-parser.service-discovery.type", "static");
        config.put("stork.tika-parser.service-discovery.address-list", moduleAddress);

        config.put("stork.text-chunker.service-discovery.type", "static");
        config.put("stork.text-chunker.service-discovery.address-list", moduleAddress);

        config.put("stork.opensearch-sink.service-discovery.type", "static");
        config.put("stork.opensearch-sink.service-discovery.address-list", moduleAddress);

        // Generic test module names used by ModuleCapabilityServiceTest
        config.put("stork.test-module.service-discovery.type", "static");
        config.put("stork.test-module.service-discovery.address-list", moduleAddress);

        config.put("stork.module-1.service-discovery.type", "static");
        config.put("stork.module-1.service-discovery.address-list", moduleAddress);

        config.put("stork.module-2.service-discovery.type", "static");
        config.put("stork.module-2.service-discovery.address-list", moduleAddress);

        // Repository service discovery (for hydration tests)
        config.put("stork.repository-service.service-discovery.type", "static");
        config.put("stork.repository-service.service-discovery.address-list", moduleAddress);

        // Registration service config - use the direct server port
        config.put("pipestream.registration.registration-service.host", host);
        config.put("pipestream.registration.registration-service.port", directPort);

        return config;
    }
}
