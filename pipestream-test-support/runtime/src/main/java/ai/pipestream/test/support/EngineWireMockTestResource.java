package ai.pipestream.test.support;

import org.testcontainers.containers.GenericContainer;

import java.util.Map;

/**
 * WireMock resource for pipestream-engine tests.
 *
 * <p>Wires both styles of gRPC client config so the engine can talk to the
 * wiremock container regardless of which client path it uses:
 * <ul>
 *   <li><b>Stork static service-discovery</b> ({@code stork.<name>.*}) — for
 *       any code path that resolves via Stork name resolver. Mostly retained
 *       for backwards-compat.</li>
 *   <li><b>Direct {@code @GrpcClient} host+port overrides</b>
 *       ({@code quarkus.grpc.clients.<name>.host} / {@code .port}) — required
 *       for OOTB Quarkus {@code @GrpcClient}-injected channels under
 *       {@code @QuarkusTest}, because Quarkus's test-mode auto-rebinds gRPC
 *       client channels to the in-process server's test port unless the
 *       resource explicitly overrides {@code .host} and {@code .port}.
 *       Without this, every {@code @GrpcClient} call lands on the engine
 *       itself instead of the wiremock container, surfacing as
 *       {@code UNIMPLEMENTED: Method not found: PipeStepProcessorService/ProcessData}
 *       even though the wiremock is healthy and serving stubs.</li>
 * </ul>
 *
 * <p>The pattern mirrors {@link SidecarWireMockTestResource} which has had
 * the explicit {@code quarkus.grpc.clients.<name>.host/port} block since
 * the kafka-sidecar moved to OOTB {@code @GrpcClient}. The engine moved to
 * the same pattern in commit {@code f1fdf3b} ({@code engine: synchronous
 * processNode + OOTB @GrpcClient}); this resource catches up to that.
 */
public class EngineWireMockTestResource extends BaseWireMockTestResource {

    /**
     * Service names whose {@code @GrpcClient} channels need direct
     * host+port overrides in {@code @QuarkusTest} mode. Includes:
     * <ul>
     *   <li>Production module names registered in
     *       {@code ai.pipestream.engine.grpc.GrpcClients}: parser, chunker,
     *       embedder, semantic-graph, opensearch-sink, echo, proxy,
     *       module-testing-sidecar.</li>
     *   <li>Test-fixture module names registered in
     *       {@code ai.pipestream.engine.grpc.TestGrpcClients}: tika-parser,
     *       text-chunker, test-module, module-1, module-2.</li>
     *   <li>Core services: engine, engine-kafka-sidecar, repository,
     *       opensearch-manager.</li>
     *   <li>Legacy / alternate names referenced by older tests:
     *       repo-service, repository-service.</li>
     * </ul>
     */
    private static final String[] GRPC_CLIENT_NAMES = {
            // production modules
            "parser", "chunker", "embedder", "semantic-graph",
            "opensearch-sink", "echo", "proxy", "module-testing-sidecar",
            // test fixtures
            "tika-parser", "text-chunker", "test-module", "module-1", "module-2",
            // core services
            "engine", "engine-kafka-sidecar", "repository", "opensearch-manager",
            // legacy / alternate names
            "repo-service", "repository-service",
    };

    @Override
    protected Map<String, String> buildConfig(GenericContainer<?> container) {
        // NOTE: pipestream-wiremock-server exposes multiple endpoints:
        // - Port 8080: gRPC server exposing many services incl. AccountService, modules (reflection-enabled)
        // - Port 50052: "Direct" streaming gRPC server (used for large streaming, and registration in some tests)
        String host = getHost();
        String standardPort = String.valueOf(getMappedPort(DEFAULT_HTTP_PORT));
        String directPort = String.valueOf(getMappedPort(DEFAULT_GRPC_PORT));
        String moduleAddress = host + ":" + standardPort;

        java.util.Map<String, String> config = new java.util.HashMap<>();

        for (String name : GRPC_CLIENT_NAMES) {
            // Stork static discovery — kept for backwards-compat with any
            // code path that still resolves via Stork name-resolver.
            config.put("stork." + name + ".service-discovery.type", "static");
            config.put("stork." + name + ".service-discovery.address-list", moduleAddress);
            config.put("stork." + name + ".load-balancer.type", "round-robin");

            // Direct @GrpcClient host+port override — REQUIRED for OOTB
            // Quarkus @GrpcClient injection under @QuarkusTest, because
            // Quarkus's test-mode auto-rebinds the channel to the in-process
            // server's test port unless host AND port are both set
            // explicitly here. Without these three lines, every @GrpcClient
            // call lands on the engine itself and surfaces as UNIMPLEMENTED
            // even though the wiremock container is healthy.
            config.put("quarkus.grpc.clients." + name + ".host", host);
            config.put("quarkus.grpc.clients." + name + ".port", standardPort);
            config.put("quarkus.grpc.clients." + name + ".use-quarkus-grpc-client", "true");
        }

        // Registration service config - use the direct server port
        config.put("pipestream.registration.registration-service.host", host);
        config.put("pipestream.registration.registration-service.port", directPort);

        return config;
    }
}
