package ai.pipestream.test.support;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import org.jboss.logging.Logger;
import org.opensearch.testcontainers.OpenSearchContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.util.HashMap;
import java.util.Map;

/**
 * Composite test resource for module-opensearch-sink tests.
 * Starts OpenSearch and WireMock (with OpenSearchManagerService mock) on a shared network.
 * Configures opensearch-manager gRPC client to use WireMock.
 */
public class OpenSearchSinkWireMockTestResource implements QuarkusTestResourceLifecycleManager {

    private static final Logger LOG = Logger.getLogger(OpenSearchSinkWireMockTestResource.class);
    private static final String OPENSEARCH_IMAGE = "opensearchproject/opensearch:3.5.0";
    private static final String WIREMOCK_IMAGE = BaseWireMockTestResource.resolveImage(BaseWireMockTestResource.DEFAULT_GHCR_IMAGE);

    private OpenSearchContainer<?> opensearchContainer;
    private GenericContainer<?> wiremockContainer;
    private Network network;

    @Override
    public Map<String, String> start() {
        network = Network.newNetwork();

        // Start OpenSearch (HTTP 9200 + gRPC 9400 for DocumentService bulk indexing)
        opensearchContainer = new OpenSearchContainer<>(DockerImageName.parse(OPENSEARCH_IMAGE))
                .withNetwork(network)
                .withNetworkAliases("opensearch")
                .withExposedPorts(9200, 9400)
                .withEnv("DISABLE_SECURITY_PLUGIN", "true")
                .withEnv("discovery.type", "single-node")
                .withEnv("OPENSEARCH_JAVA_OPTS", "-Xms512m -Xmx512m")
                // OpenSearch 3.5+: transport-grpc 
                .withEnv("aux.transport.types", "[\"transport-grpc\"]")
                .withEnv("aux.transport.transport-grpc.port", "9400-9400");
        
        opensearchContainer.start();
        
        String osHostAddress = opensearchContainer.getHttpHostAddress();
        int osGrpcPort = opensearchContainer.getMappedPort(9400);
        String osGrpcAddress = opensearchContainer.getHost() + ":" + osGrpcPort;
        LOG.infof("Started OpenSearch at %s, gRPC at %s", osHostAddress, osGrpcAddress);

        // Start WireMock with OpenSearch host so the mock can create indices
        // Using the network alias "opensearch" for container-to-container communication
        String osHostForWiremock = "http://opensearch:9200";
        wiremockContainer = new GenericContainer<>(DockerImageName.parse(WIREMOCK_IMAGE))
                .withNetwork(network)
                .withExposedPorts(8080, 50052)
                .withEnv("OPENSEARCH_HOSTS", osHostForWiremock)
                .withEnv("WIREMOCK_OPENSEARCH_DEFAULT_DIMENSION", "3")
                // Wait for our new streaming gRPC server to be ready
                .waitingFor(Wait.forLogMessage(".*Direct Streaming gRPC Server started.*", 1));
        
        wiremockContainer.start();

        String wiremockHost = wiremockContainer.getHost();
        int grpcPort = wiremockContainer.getMappedPort(50052);
        String wiremockAddress = wiremockHost + ":" + grpcPort;

        Map<String, String> config = new HashMap<>();
        // Important: Use the port-mapped address for the Sink which runs outside the docker network
        config.put("opensearch.hosts", osHostAddress.replace("http://", ""));
        config.put("opensearch.protocol", "http");
        config.put("quarkus.compose.devservices.enabled", "false");
        config.put("pipestream.registration.enabled", "false");

        // Override Stork to use static discovery pointing at WireMock (avoids Consul)
        config.put("quarkus.stork.opensearch-manager.service-discovery.type", "static");
        config.put("quarkus.stork.opensearch-manager.service-discovery.address-list", wiremockAddress);
        config.put("quarkus.stork.registration-service.service-discovery.type", "static");
        config.put("quarkus.stork.registration-service.service-discovery.address-list", wiremockAddress);

        // OpenSearch native gRPC (DocumentService bulk) - point at real OpenSearch
        config.put("quarkus.dynamic-grpc.service.opensearch-grpc.address", osGrpcAddress);

        LOG.infof("OpenSearchSinkWireMockTestResource: OpenSearch=%s, gRPC=%s, WireMock=%s", osHostAddress, osGrpcAddress, wiremockAddress);
        return config;
    }

    @Override
    public void stop() {
        if (wiremockContainer != null) {
            wiremockContainer.stop();
        }
        if (opensearchContainer != null) {
            opensearchContainer.stop();
        }
        if (network != null) {
            network.close();
        }
    }
}
