/**
 * Core API for the Quarkus Dynamic gRPC extension.
 * <p>
 * This package provides the types required to discover gRPC service instances at runtime and to
 * obtain client Channels or typed Mutiny stubs without compile-time configuration. The main entry
 * point is {@link ai.pipestream.quarkus.dynamicgrpc.GrpcClientFactory}, which can be injected and
 * used to resolve a service name (as registered in Consul) to a gRPC {@link io.grpc.Channel} or a
 * typed {@link io.quarkus.grpc.MutinyStub}.
 * </p>
 *
 * <h2>Typical usage</h2>
 * <pre>{@code
 * @Inject GrpcClientFactory clients;
 *
 * Uni<MyServiceGrpc.MutinyMyServiceStub> stub = clients.getClient(
 *     "orders-service",
 *     MyServiceGrpc::newMutinyStub
 * );
 *
 * // or obtain a raw Channel
 * Uni<Channel> channel = clients.getChannel("orders-service");
 * }</pre>
 *
 * <h2>Configuration</h2>
 * The runtime behavior is configured via MicroProfile/Quarkus configuration keys. The most
 * relevant keys include:
 * <ul>
 *   <li>{@code quarkus.dynamic-grpc.consul.host} – Consul host (default {@code localhost})</li>
 *   <li>{@code quarkus.dynamic-grpc.consul.port} – Consul port (default {@code 8500})</li>
 *   <li>{@code quarkus.dynamic-grpc.consul.refresh-period} – Stork refresh period (default {@code 10s})</li>
 *   <li>{@code quarkus.dynamic-grpc.consul.use-health-checks} – Use Consul health checks (default {@code false})</li>
 *   <li>{@code quarkus.dynamic-grpc.channel.idle-ttl-minutes} – Channel cache idle TTL (default {@code 15})</li>
 *   <li>{@code quarkus.dynamic-grpc.channel.max-size} – Channel cache max size (default {@code 1000})</li>
 *   <li>{@code quarkus.dynamic-grpc.channel.shutdown-timeout-seconds} – Cleanup timeout (default {@code 2})</li>
 * </ul>
 *
 * <h2>How it works</h2>
 * <ol>
 *   <li>{@link ai.pipestream.quarkus.dynamicgrpc.ServiceDiscoveryManager} dynamically defines a Stork
 *       service backed by Consul based on configuration and the requested service name.</li>
 *   <li>Instances are discovered via SmallRye Stork or the direct Consul discovery implementation
 *       available under {@code discovery} package.</li>
 *   <li>{@link ai.pipestream.quarkus.dynamicgrpc.ChannelManager} creates and caches gRPC channels
 *       using Stork’s name resolution and evicts them after a configurable idle TTL.</li>
 * </ol>
 */
package ai.pipestream.quarkus.dynamicgrpc;
