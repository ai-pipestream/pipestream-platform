package ai.pipestream.quarkus.dynamicgrpc;

import ai.pipestream.quarkus.dynamicgrpc.base.ConsulServiceRegistration;
import ai.pipestream.test.support.ConsulTestResource;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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
    private String baseServiceName;

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
        baseServiceName = "stress-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @AfterEach
    void cleanup() {
        if (consulRegistration != null && baseServiceName != null) {
            for (int i = 0; i < 10; i++) {
                try {
                    consulRegistration.deregisterService(baseServiceName + "-" + i + "-instance");
                } catch (Exception ignore) {}
            }
        }
    }

    @Test
    @DisplayName("Handle 100 concurrent requests across 10 services")
    void testHighConcurrencyMultipleServices() throws InterruptedException {
        // Register 10 services
        for (int i = 0; i < 10; i++) {
            String name = baseServiceName + "-" + i;
            consulRegistration.registerService(name, name + "-instance", "127.0.0.1", stressPorts.get(i));
        }

        // Warm up discovery for every service before the stress phase
        for (int i = 0; i < 10; i++) {
            String name = baseServiceName + "-" + i;
            clientFactory.getClient(name, MutinyGreeterGrpc::newMutinyStub)
                .await().atMost(Duration.ofSeconds(10));
        }

        ExecutorService executor = Executors.newFixedThreadPool(20);
        CountDownLatch latch = new CountDownLatch(100);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);

        // Submit 100 concurrent requests across different services
        for (int i = 0; i < 100; i++) {
            final int serviceIndex = i % 10;
            final int requestNum = i;

            executor.submit(() -> {
                try {
                    String name = baseServiceName + "-" + serviceIndex;
                    var client = clientFactory.getClient(name, MutinyGreeterGrpc::newMutinyStub)
                        .await().atMost(Duration.ofSeconds(10));

                    HelloRequest request = HelloRequest.newBuilder()
                        .setName("Request-" + requestNum)
                        .build();

                    HelloReply reply = client.sayHello(request).await().atMost(Duration.ofSeconds(5));

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

        boolean completed = latch.await(60, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).as("Stress test should complete within timeout").isTrue();
        assertThat(successCount.get())
            .as("Should have a high success rate (at least 95%) under load")
            .isGreaterThanOrEqualTo(95);
        
        LOG.infof("Stress test: %d successes, %d failures out of 100 requests",
            successCount.get(), failureCount.get());
    }

    @Test
    @DisplayName("Sustained load - 500 requests over 10 seconds")
    void testSustainedLoad() throws InterruptedException {
        String name = baseServiceName + "-sustained";
        consulRegistration.registerService(name, name + "-instance", "127.0.0.1", stressPorts.get(0));

        // Warm up discovery before the sustained load phase
        clientFactory.getClient(name, MutinyGreeterGrpc::newMutinyStub)
            .await().atMost(Duration.ofSeconds(10));

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);
        long startTime = System.currentTimeMillis();

        ExecutorService executor = Executors.newFixedThreadPool(10);

        // Submit 500 requests
        for (int i = 0; i < 500; i++) {
            final int requestNum = i;
            executor.submit(() -> {
                try {
                    var client = clientFactory.getClient(name, MutinyGreeterGrpc::newMutinyStub)
                        .await().atMost(Duration.ofSeconds(5));

                    HelloRequest request = HelloRequest.newBuilder().setName("S-" + requestNum).build();
                    HelloReply reply = client.sayHello(request).await().atMost(Duration.ofSeconds(5));

                    if (reply.getMessage().contains("Hello")) {
                        successCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    failureCount.incrementAndGet();
                }
            });

            if (i % 50 == 0) {
                Thread.sleep(100);
            }
        }

        executor.shutdown();
        boolean completed = executor.awaitTermination(30, TimeUnit.SECONDS);
        long duration = System.currentTimeMillis() - startTime;

        assertThat(completed).as("Sustained load test should complete").isTrue();
        assertThat(successCount.get())
            .as("Should have very high success rate (at least 98%) for sustained load")
            .isGreaterThanOrEqualTo(490);
        
        LOG.infof("Sustained load: %d successes, %d failures in %dms",
            successCount.get(), failureCount.get(), duration);

        consulRegistration.deregisterService(name + "-instance");
    }

    @Test
    @DisplayName("Rapid service switching - many services accessed quickly")
    void testRapidServiceSwitching() {
        // Register 10 services
        for (int i = 0; i < 10; i++) {
            String name = baseServiceName + "-switch-" + i;
            consulRegistration.registerService(name, name + "-instance", "127.0.0.1", stressPorts.get(i));
        }

        // Warm up discovery for every service before rapid-switching phase
        for (int i = 0; i < 10; i++) {
            String name = baseServiceName + "-switch-" + i;
            clientFactory.getClient(name, MutinyGreeterGrpc::newMutinyStub)
                .await().atMost(Duration.ofSeconds(10));
        }

        AtomicInteger successCount = new AtomicInteger(0);

        // Rapidly switch between services
        for (int round = 0; round < 20; round++) {
            for (int i = 0; i < 10; i++) {
                String name = baseServiceName + "-switch-" + i;
                try {
                    var client = clientFactory.getClient(name, MutinyGreeterGrpc::newMutinyStub)
                        .await().atMost(Duration.ofSeconds(5));

                    HelloRequest request = HelloRequest.newBuilder().setName("R-" + round + "-" + i).build();
                    HelloReply reply = client.sayHello(request).await().atMost(Duration.ofSeconds(3));

                    if (reply.getMessage().contains("Hello")) {
                        successCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    LOG.errorf(e, "Failed on service %s", name);
                }
            }
        }

        assertThat(successCount.get())
            .as("Should handle rapid switching between services with high success rate")
            .isGreaterThanOrEqualTo(190);

        assertThat(clientFactory.getActiveServiceCount())
            .as("Cache should have all 10 services")
            .isGreaterThanOrEqualTo(10);

        for (int i = 0; i < 10; i++) {
            try {
                consulRegistration.deregisterService(baseServiceName + "-switch-" + i + "-instance");
            } catch (Exception ignore) {}
        }
    }

    @Test
    @DisplayName("Memory stability - ensure no leaks with repeated access")
    void testMemoryStability() {
        String name = baseServiceName + "-memory";
        consulRegistration.registerService(name, name + "-instance", "127.0.0.1", stressPorts.get(0));

        // Warm up discovery before the memory stability loop
        clientFactory.getClient(name, MutinyGreeterGrpc::newMutinyStub)
            .await().atMost(Duration.ofSeconds(10));

        int initialChannelCount = clientFactory.getActiveServiceCount();

        // Access same service 1000 times
        for (int i = 0; i < 1000; i++) {
            var client = clientFactory.getClient(name, MutinyGreeterGrpc::newMutinyStub)
                .await().atMost(Duration.ofSeconds(3));

            HelloRequest request = HelloRequest.newBuilder().setName("M-" + i).build();
            client.sayHello(request).await().atMost(Duration.ofSeconds(2));
        }

        assertThat(clientFactory.getActiveServiceCount())
            .as("Should not leak channels after repeated access to same service")
            .isLessThanOrEqualTo(initialChannelCount + 1);

        consulRegistration.deregisterService(name + "-instance");
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
