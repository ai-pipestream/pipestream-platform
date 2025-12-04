/**
 * Discovery and load-balancing utilities for the Dynamic gRPC extension.
 * <p>
 * This package contains:
 * <ul>
 *   <li>{@link ai.pipestream.quarkus.dynamicgrpc.discovery.ServiceDiscovery} – the abstraction used by
 *   the runtime to obtain available service instances.</li>
 *   <li>{@link ai.pipestream.quarkus.dynamicgrpc.discovery.DynamicConsulServiceDiscovery} – a direct Consul-based
 *   discovery implementation usable even when Stork is not pre-configured.</li>
 *   <li>{@link ai.pipestream.quarkus.dynamicgrpc.discovery.RandomLoadBalancer} – a simple random
 *   selection strategy compatible with SmallRye Stork’s APIs.</li>
 *   <li>Producers like {@link ai.pipestream.quarkus.dynamicgrpc.discovery.StandaloneServiceDiscoveryProducer}
 *   and {@link ai.pipestream.quarkus.dynamicgrpc.discovery.StandaloneVertxProducer} that provide sensible
 *   defaults when running this module outside a full Quarkus application.</li>
 * </ul>
 */
package ai.pipestream.quarkus.dynamicgrpc.discovery;
