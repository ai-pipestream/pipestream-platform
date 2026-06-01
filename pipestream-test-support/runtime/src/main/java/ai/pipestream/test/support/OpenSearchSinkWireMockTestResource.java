package ai.pipestream.test.support;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import org.jboss.logging.Logger;
import org.opensearch.testcontainers.OpenSearchContainer;
import org.testcontainers.consul.ConsulContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Composite test resource for module-opensearch-sink tests.
 * <p>
 * Starts THREE containers on a shared network:
 * <ol>
 *   <li>OpenSearch (HTTP 9200 + gRPC 9400 for DocumentService bulk).</li>
 *   <li>WireMock with the {@code OpenSearchManagerService} streaming mock
 *       on its gRPC port.</li>
 *   <li>Consul (HashiCorp 1.22 in {@code -dev} mode) so Stork can resolve
 *       {@code opensearch-manager} via its configured Consul discovery
 *       without needing a Consul on the dev host.</li>
 * </ol>
 *
 * <p>The WireMock instance is registered in Consul under the name
 * {@code opensearch-manager} pointing at WireMock's HOST-MAPPED gRPC port
 * (so the sink, which runs in the test JVM outside the docker network,
 * can reach it). The sink's {@code application.properties} already has
 * {@code quarkus.stork."opensearch-manager".service-discovery.consul-host}
 * and {@code consul-port} pointing at {@code ${CONSUL_HOST:localhost}} /
 * {@code ${CONSUL_PORT:8500}}; this resource overrides them at runtime to
 * point at the testcontainer Consul.
 */
public class OpenSearchSinkWireMockTestResource implements QuarkusTestResourceLifecycleManager {

    private static final Logger LOG = Logger.getLogger(OpenSearchSinkWireMockTestResource.class);
    private static final String OPENSEARCH_IMAGE = "opensearchproject/opensearch:3.5.0";
    private static final String WIREMOCK_IMAGE = BaseWireMockTestResource.resolveImage();
    private static final DockerImageName CONSUL_IMAGE = DockerImageName.parse("hashicorp/consul:1.22");

    private OpenSearchContainer<?> opensearchContainer;
    private GenericContainer<?> wiremockContainer;
    private ConsulContainer consulContainer;
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

        // Start Consul so Stork's configured consul service-discovery resolves
        // against a real agent. -dev mode is fine; we tear it down in stop().
        consulContainer = new ConsulContainer(CONSUL_IMAGE)
                .withNetwork(network)
                .withNetworkAliases("consul")
                .withConsulCommand("agent -dev -ui -client=0.0.0.0 -log-level=WARN");
        consulContainer.start();
        String consulHost = consulContainer.getHost();
        int consulPort = consulContainer.getMappedPort(8500);
        LOG.infof("Started Consul at %s:%d", consulHost, consulPort);

        // Register the WireMock gRPC port under the name Stork looks up. The
        // sink runs in the test JVM (outside the docker network), so the
        // address Consul hands back must be the host-mapped address.
        registerInConsul(consulHost, consulPort, "opensearch-manager", wiremockHost, grpcPort);

        Map<String, String> config = new HashMap<>();
        config.put("opensearch.hosts", osHostAddress.replace("http://", ""));
        config.put("opensearch.protocol", "http");
        config.put("quarkus.compose.devservices.enabled", "false");
        config.put("pipestream.registration.enabled", "false");

        // Point Stork's configured Consul lookup at the testcontainer. Both
        // quoted and unquoted forms — application.properties uses the quoted
        // variant; SmallRye Config normally treats them the same but a
        // belt-and-braces override here costs nothing.
        config.put("quarkus.stork.\"opensearch-manager\".service-discovery.type", "consul");
        config.put("quarkus.stork.\"opensearch-manager\".service-discovery.consul-host", consulHost);
        config.put("quarkus.stork.\"opensearch-manager\".service-discovery.consul-port", String.valueOf(consulPort));
        config.put("quarkus.stork.opensearch-manager.service-discovery.type", "consul");
        config.put("quarkus.stork.opensearch-manager.service-discovery.consul-host", consulHost);
        config.put("quarkus.stork.opensearch-manager.service-discovery.consul-port", String.valueOf(consulPort));

        // OpenSearch native gRPC (DocumentService bulk) - point at real OpenSearch
        config.put("quarkus.dynamic-grpc.service.opensearch-grpc.address", osGrpcAddress);

        LOG.infof("OpenSearchSinkWireMockTestResource: OpenSearch=%s, gRPC=%s, WireMock=%s, Consul=%s:%d",
                osHostAddress, osGrpcAddress, wiremockAddress, consulHost, consulPort);
        return config;
    }

    /**
     * PUT /v1/agent/service/register on the test Consul. Throws if Consul
     * rejects the registration so failures here surface immediately rather
     * than as a downstream Stork "no instances" timeout.
     */
    private static void registerInConsul(String consulHost, int consulPort, String serviceName,
                                         String address, int port) {
        String body = String.format(
                "{\"ID\":\"%s-test-1\",\"Name\":\"%s\",\"Address\":\"%s\",\"Port\":%d,\"Tags\":[\"grpc\"]}",
                serviceName, serviceName, address, port);
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://" + consulHost + ":" + consulPort + "/v1/agent/service/register"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(10))
                    .PUT(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("Consul register " + serviceName + " failed: HTTP "
                        + response.statusCode() + " — " + response.body());
            }
            LOG.infof("Registered %s -> %s:%d in Consul", serviceName, address, port);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to register " + serviceName + " in Consul", e);
        }
    }

    @Override
    public void stop() {
        if (wiremockContainer != null) {
            wiremockContainer.stop();
        }
        if (opensearchContainer != null) {
            opensearchContainer.stop();
        }
        if (consulContainer != null) {
            consulContainer.stop();
        }
        if (network != null) {
            network.close();
        }
    }
}
