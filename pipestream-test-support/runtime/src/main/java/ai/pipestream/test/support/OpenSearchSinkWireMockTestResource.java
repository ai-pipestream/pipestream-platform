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
    private static final String OPENSEARCH_IMAGE = "opensearchproject/opensearch:3";
    private static final String WIREMOCK_IMAGE = System.getProperty("pipestream.wiremock.image",
            System.getenv().getOrDefault("PIPESTREAM_WIREMOCK_IMAGE", "ghcr.io/ai-pipestream/pipestream-wiremock-server:0.1.35"));

    private OpenSearchContainer<?> opensearchContainer;
    private GenericContainer<?> wiremockContainer;
    private Network network;

    @Override
    public Map<String, String> start() {
        network = Network.newNetwork();

        // Start OpenSearch
        opensearchContainer = new OpenSearchContainer<>(DockerImageName.parse(OPENSEARCH_IMAGE))
                .withNetwork(network)
                .withNetworkAliases("opensearch")
                .withEnv("DISABLE_SECURITY_PLUGIN", "true")
                .withEnv("discovery.type", "single-node")
                .withEnv("bootstrap.memory_lock", "true")
                .withEnv("OPENSEARCH_JAVA_OPTS", "-Xms512m -Xmx512m");
        opensearchContainer.start();
        String osHost = opensearchContainer.getHttpHostAddress();
        LOG.infof("Started OpenSearch at %s", osHost);

        // Start WireMock with OpenSearch host so the mock can create indices
        String osHostForWiremock = "http://opensearch:9200";
        wiremockContainer = new GenericContainer<>(DockerImageName.parse(WIREMOCK_IMAGE))
                .withNetwork(network)
                .withExposedPorts(8080, 50052)
                .withEnv("OPENSEARCH_HOSTS", osHostForWiremock)
                .withEnv("WIREMOCK_OPENSEARCH_DEFAULT_DIMENSION", "3")
                .waitingFor(Wait.forLogMessage(".*Direct Streaming gRPC Server started.*", 1));
        wiremockContainer.start();

        String wiremockHost = wiremockContainer.getHost();
        int grpcPort = wiremockContainer.getMappedPort(50052);

        // Opensearch host for the sink (running on host, needs port-mapped address)
        String sinkOsHost = opensearchContainer.getHttpHostAddress();

        Map<String, String> config = new HashMap<>();
        config.put("opensearch.hosts", sinkOsHost.replace("http://", ""));
        config.put("opensearch.protocol", "http");
        config.put("quarkus.compose.devservices.enabled", "false");
        config.put("pipestream.registration.enabled", "false");

        // Route opensearch-manager gRPC client to WireMock
        config.put("stork.opensearch-manager.service-discovery.type", "static");
        config.put("stork.opensearch-manager.service-discovery.address-list", wiremockHost + ":" + grpcPort);

        // Use opensearch-manager (WireMock) for schema
        config.put("opensearch.sink.use-opensearch-manager", "true");

        LOG.infof("OpenSearchSinkWireMockTestResource: OpenSearch=%s, WireMock gRPC=%s:%d", sinkOsHost, wiremockHost, grpcPort);
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
    }
}
