package ai.pipestream.quarkus.dynamicgrpc;

import ai.pipestream.quarkus.dynamicgrpc.GrpcClientFactory;
import ai.pipestream.quarkus.dynamicgrpc.base.ConsulServiceRegistration;
import ai.pipestream.quarkus.dynamicgrpc.base.ConsulTestResource;
import ai.pipestream.quarkus.dynamicgrpc.it.proto.HelloReply;
import ai.pipestream.quarkus.dynamicgrpc.it.proto.HelloRequest;
import ai.pipestream.quarkus.dynamicgrpc.it.proto.MutinyGreeterGrpc;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stress tests for GrpcClientFactory under heavy load.
 * Simulates real production scenarios with many services and high concurrency.
 */
@QuarkusTest
@WithTestResource(ConsulTestResource.class)
public class StressTest {

    private static final Logger LOG = Logger.getLogger(StressTest.class);

    @Inject
    GrpcClientFactory clientFactory;

    @ConfigProperty(name = "quarkus.dynamic-grpc.consul.host")
    String consulHost;

    @ConfigProperty(name = "quarkus.dynamic-grpc.consul.port")
    int consulPort;

    private static final List<Server> stressServers = new ArrayList<>();
    private static final List<Integer> stressPorts = new ArrayList<>();
    private ConsulServiceRegistration consulRegistration;

    @BeforeAll
    static void startStressServers() throws IOException {
        // Start 10 different gRPC services for stress testing
        for (int i = 0; i < 10; i++) {
            int port;
            try (ServerSocket socket = new ServerSocket(0)) {
                port = socket.getLocalPort();
            }
            stressPorts.add(port);

            Server server = ServerBuilder.forPort(port)
                .addService(new StressGreeterService("stress-service-" + i))
                .build()
                .start();

            stressServers.add(server);
            LOG.infof("Started stress test server %d on port: %d", i, port);
        }
    }

    @AfterAll
    static void stopStressServers() throws InterruptedException {
        for (Server server : stressServers) {
            server.shutdown();
            server.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @BeforeEach
    void setup() {
        consulRegistration = new ConsulServiceRegistration(consulHost, consulPort);
    }

    @Test
    @DisplayName("Handle 100 concurrent requests across 10 services")
    void testHighConcurrencyMultipleServices() throws InterruptedException {
        // Register 10 services
        for (int i = 0; i < 10; i++) {
            String serviceName = "stress-service-" + i;
            consulRegistration.registerService(
                serviceName,
                serviceName + "-instance",
                "127.0.0.1",
                stressPorts.get(i)
            );
        }

        Thread.sleep(1000); // Wait for registration

        ExecutorService executor = Executors.newFixedThreadPool(20);
        CountDownLatch latch = new CountDownLatch(100);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);

        // Submit 100 concurrent requests across different services
        for (int i = 0; i < 100; i++) {
            final int serviceIndex = i % 10; // Round-robin across services
            final int requestNum = i;

            executor.submit(() -> {
                try {
                    String serviceName = "stress-service-" + serviceIndex;
                    var client = clientFactory.getClient(serviceName, MutinyGreeterGrpc::newMutinyStub)
                        .await().atMost(Duration.ofSeconds(10));

                    HelloRequest request = HelloRequest.newBuilder()
                        .setName("Request-" + requestNum)
                        .build();

                    HelloReply reply = client.sayHello(request)
                        .await().atMost(Duration.ofSeconds(5));

                    if (reply.getMessage().contains("Hello")) {
                        successCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    failureCount.incrementAndGet();
                    LOG.errorf(e, "Request %d failed", requestNum);
                } finally {
                    latch.countDown();
                }
            });
        }

        // Wait for all requests to complete
        boolean completed = latch.await(60, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        assertThat(successCount.get()).isGreaterThanOrEqualTo(95); // At least 95% success rate
        LOG.infof("Stress test: %d successes, %d failures out of 100 requests",
            successCount.get(), failureCount.get());

        // Cleanup
        for (int i = 0; i < 10; i++) {
            consulRegistration.deregisterService("stress-service-" + i + "-instance");
        }
    }

    @Test
    @DisplayName("Sustained load - 500 requests over 10 seconds")
    void testSustainedLoad() throws InterruptedException {
        String serviceName = "sustained-load-service";
        consulRegistration.registerService(
            serviceName,
            serviceName + "-instance",
            "127.0.0.1",
            stressPorts.get(0)
        );

        Thread.sleep(500);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);
        long startTime = System.currentTimeMillis();

        ExecutorService executor = Executors.newFixedThreadPool(10);

        // Submit 500 requests over time
        for (int i = 0; i < 500; i++) {
            final int requestNum = i;

            executor.submit(() -> {
                try {
                    var client = clientFactory.getClient(serviceName, MutinyGreeterGrpc::newMutinyStub)
                        .await().atMost(Duration.ofSeconds(5));

                    HelloRequest request = HelloRequest.newBuilder()
                        .setName("Sustained-" + requestNum)
                        .build();

                    HelloReply reply = client.sayHello(request)
                        .await().atMost(Duration.ofSeconds(5));

                    if (reply.getMessage().contains("Hello")) {
                        successCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    failureCount.incrementAndGet();
                }
            });

            // Small delay between submissions to simulate real load
            if (i % 50 == 0) {
                Thread.sleep(100);
            }
        }

        executor.shutdown();
        boolean completed = executor.awaitTermination(30, TimeUnit.SECONDS);
        long duration = System.currentTimeMillis() - startTime;

        assertThat(completed).isTrue();
        assertThat(successCount.get()).isGreaterThanOrEqualTo(490); // 98% success rate
        LOG.infof("Sustained load: %d successes, %d failures in %dms",
            successCount.get(), failureCount.get(), duration);

        consulRegistration.deregisterService(serviceName + "-instance");
    }

    @Test
    @DisplayName("Rapid service switching - many services accessed quickly")
    void testRapidServiceSwitching() throws InterruptedException {
        // Register 10 services
        for (int i = 0; i < 10; i++) {
            String serviceName = "switch-service-" + i;
            consulRegistration.registerService(
                serviceName,
                serviceName + "-instance",
                "127.0.0.1",
                stressPorts.get(i)
            );
        }

        Thread.sleep(1000);

        AtomicInteger successCount = new AtomicInteger(0);

        // Rapidly switch between services
        for (int round = 0; round < 20; round++) {
            for (int serviceIdx = 0; serviceIdx < 10; serviceIdx++) {
                String serviceName = "switch-service-" + serviceIdx;

                try {
                    var client = clientFactory.getClient(serviceName, MutinyGreeterGrpc::newMutinyStub)
                        .await().atMost(Duration.ofSeconds(5));

                    HelloRequest request = HelloRequest.newBuilder()
                        .setName("Switch-" + round + "-" + serviceIdx)
                        .build();

                    HelloReply reply = client.sayHello(request)
                        .await().atMost(Duration.ofSeconds(3));

                    if (reply.getMessage().contains("Hello")) {
                        successCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    LOG.errorf(e, "Failed on service %s", serviceName);
                }
            }
        }

        // 200 total requests (20 rounds * 10 services)
        assertThat(successCount.get()).isGreaterThanOrEqualTo(190); // 95% success

        // Should have cached 10 channels
        assertThat(clientFactory.getActiveServiceCount()).isGreaterThanOrEqualTo(10);

        // Cleanup
        for (int i = 0; i < 10; i++) {
            consulRegistration.deregisterService("switch-service-" + i + "-instance");
        }
    }

    @Test
    @DisplayName("Memory stability - ensure no leaks with repeated access")
    void testMemoryStability() throws InterruptedException {
        String serviceName = "memory-test-service";
        consulRegistration.registerService(
            serviceName,
            serviceName + "-instance",
            "127.0.0.1",
            stressPorts.get(0)
        );

        Thread.sleep(500);

        int initialChannelCount = clientFactory.getActiveServiceCount();

        // Access same service 1000 times
        for (int i = 0; i < 1000; i++) {
            var client = clientFactory.getClient(serviceName, MutinyGreeterGrpc::newMutinyStub)
                .await().atMost(Duration.ofSeconds(3));

            HelloRequest request = HelloRequest.newBuilder()
                .setName("Memory-" + i)
                .build();

            client.sayHello(request)
                .await().atMost(Duration.ofSeconds(2));
        }

        // Should still only have ONE channel for this service (no leak)
        int finalChannelCount = clientFactory.getActiveServiceCount();
        assertThat(finalChannelCount).isLessThanOrEqualTo(initialChannelCount + 1);

        LOG.infof("Memory stability: %d channels after 1000 requests", finalChannelCount);

        consulRegistration.deregisterService(serviceName + "-instance");
    }

    /**
     * Test greeter service for stress testing.
     */
    static class StressGreeterService extends MutinyGreeterGrpc.GreeterImplBase {
        private final String serviceId;

        StressGreeterService(String serviceId) {
            this.serviceId = serviceId;
        }

        @Override
        public Uni<HelloReply> sayHello(HelloRequest request) {
            HelloReply response = HelloReply.newBuilder()
                .setMessage("Hello " + request.getName() + " from " + serviceId)
                .build();
            return Uni.createFrom().item(response);
        }
    }
}
