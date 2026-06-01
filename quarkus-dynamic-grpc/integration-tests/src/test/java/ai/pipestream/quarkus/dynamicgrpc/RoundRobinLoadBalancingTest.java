package ai.pipestream.quarkus.dynamicgrpc;

import ai.pipestream.quarkus.dynamicgrpc.base.ConsulServiceRegistration;
import ai.pipestream.quarkus.dynamicgrpc.it.proto.HelloReply;
import ai.pipestream.quarkus.dynamicgrpc.it.proto.HelloRequest;
import ai.pipestream.quarkus.dynamicgrpc.it.proto.MutinyGreeterGrpc;
import ai.pipestream.test.support.ConsulTestResource;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.mutiny.Uni;
import io.smallrye.stork.Stork;
import jakarta.inject.Inject;
import org.awaitility.Awaitility;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the channel built by the migrated {@link ChannelManager}
 * actually rotates calls across multiple Stork-resolved instances. The
 * pre-migration {@code StorkGrpcChannel} did its own per-call instance
 * selection; the post-migration {@code NettyChannelBuilder} hands that
 * job to grpc-java's {@code round_robin} load-balancing policy. This
 * test guards against regressions in either direction (e.g., reverting
 * to the wrong default LB or losing the resolver's multi-address
 * publishing).
 *
 * <h2>Shape</h2>
 * Three independent grpc-java {@link Server}s on three random ports,
 * each tagging responses with its own {@code instance_id}. All three
 * register in Consul under the SAME logical service name, with
 * different Consul service IDs. The test fires {@link #CALLS} sequential
 * sayHello calls and tallies how many landed on each instance.
 *
 * <h2>Pass criteria</h2>
 * <ul>
 *   <li>Every instance sees at least one call (no instance is dead-lettered).</li>
 *   <li>Distribution is within ±25% of even — round_robin is round-robin,
 *       but Stork resolution timing and connection establishment can skew
 *       the very first few calls. ±25% tolerance over {@link #CALLS}=30
 *       allows for that without admitting a real broken-LB regression.</li>
 * </ul>
 */
@QuarkusTest
@WithTestResource(ConsulTestResource.class)
public class RoundRobinLoadBalancingTest {

    private static final Logger LOG = Logger.getLogger(RoundRobinLoadBalancingTest.class);

    private static final int INSTANCES = 3;
    private static final int CALLS = 30;

    @Inject
    GrpcClientFactory factory;

    @ConfigProperty(name = "quarkus.dynamic-grpc.consul.host")
    String consulHost;

    @ConfigProperty(name = "quarkus.dynamic-grpc.consul.port")
    int consulPort;

    private ConsulServiceRegistration consulRegistration;
    private String serviceName;
    private final Server[] servers = new Server[INSTANCES];

    @BeforeEach
    void setup() throws IOException {
        consulRegistration = new ConsulServiceRegistration(consulHost, consulPort);
        serviceName = "rr-lb-" + UUID.randomUUID().toString().substring(0, 8);

        for (int i = 0; i < INSTANCES; i++) {
            int port;
            try (ServerSocket s = new ServerSocket(0)) {
                port = s.getLocalPort();
            }
            String instanceId = "instance-" + i;
            servers[i] = ServerBuilder.forPort(port)
                    .addService(new TaggingGreeterService(instanceId))
                    .build()
                    .start();
            consulRegistration.registerService(serviceName, serviceName + "-" + i,
                    "127.0.0.1", port);
            LOG.infof("Registered %s/%s at 127.0.0.1:%d", serviceName, instanceId, port);
        }

        // Propagation barrier. Without this, Stork's first discovery query
        // can fire before Consul has finished indexing all N registrations,
        // and Stork caches whatever it got — say 2 of 3 instances — for the
        // entire 10s Consul refresh window. The 30 test calls then rotate
        // over only the partial set and the "all instances hit" assertion
        // flakes (e.g. {instance-0=15, instance-1=15, instance-2=0}).
        //
        // Step 1: trigger ensureServiceDefined so Stork knows about the
        //         logical service name.
        // Step 2: poll Stork directly until it reports all INSTANCES instances.
        factory.getChannel(serviceName).await().atMost(Duration.ofSeconds(10));
        Awaitility.await()
                .atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> {
                    try {
                        var instances = Stork.getInstance()
                                .getService(serviceName)
                                .getInstances()
                                .await().atMost(Duration.ofSeconds(2));
                        return instances != null && instances.size() == INSTANCES;
                    } catch (Exception e) {
                        return false;
                    }
                });

        // The channel that was built during step 1 above resolved against
        // however-many instances were visible at that moment, and grpc-java
        // load-balancers don't auto-re-resolve on schedule. Drop the cached
        // channel so the next getClient() builds a fresh one against the
        // now-fully-propagated instance list.
        factory.evictChannel(serviceName);
    }

    @AfterEach
    void cleanup() throws InterruptedException {
        if (consulRegistration != null && serviceName != null) {
            for (int i = 0; i < INSTANCES; i++) {
                try {
                    consulRegistration.deregisterService(serviceName + "-" + i);
                } catch (Exception ignore) {
                    /* shutting down */
                }
            }
        }
        for (Server s : servers) {
            if (s != null) {
                s.shutdown();
                s.awaitTermination(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    @DisplayName("Calls distribute across all 3 registered instances under round_robin LB")
    void roundRobinDistribution_allInstancesHit() {
        MutinyGreeterGrpc.MutinyGreeterStub client = factory
                .getClient(serviceName, MutinyGreeterGrpc::newMutinyStub)
                .await().atMost(Duration.ofSeconds(10));

        Map<String, Integer> hits = new HashMap<>();
        for (int i = 0; i < CALLS; i++) {
            HelloReply reply = client.sayHello(HelloRequest.newBuilder().setName("rr-" + i).build())
                    .await().atMost(Duration.ofSeconds(5));
            hits.merge(reply.getInstanceId(), 1, Integer::sum);
        }

        LOG.infof("Distribution across %d instances over %d calls: %s",
                INSTANCES, CALLS, hits);

        assertThat(hits.keySet())
                .as("every registered instance must receive at least one call — "
                        + "round_robin should not dead-letter any instance")
                .hasSize(INSTANCES);

        int idealPerInstance = CALLS / INSTANCES;
        int tolerance = (int) Math.ceil(idealPerInstance * 0.25);
        for (Map.Entry<String, Integer> e : hits.entrySet()) {
            assertThat(e.getValue())
                    .as("instance %s saw %d calls; expected ~%d±%d",
                            e.getKey(), e.getValue(), idealPerInstance, tolerance)
                    .isBetween(idealPerInstance - tolerance, idealPerInstance + tolerance);
        }
    }

    /** Standalone server impl that tags every reply with its own instance id. */
    static class TaggingGreeterService extends MutinyGreeterGrpc.GreeterImplBase {
        private final String instanceId;

        TaggingGreeterService(String instanceId) {
            this.instanceId = instanceId;
        }

        @Override
        public Uni<HelloReply> sayHello(HelloRequest request) {
            return Uni.createFrom().item(
                    HelloReply.newBuilder()
                            .setMessage("Hello " + request.getName())
                            .setInstanceId(instanceId)
                            .build());
        }
    }
}
