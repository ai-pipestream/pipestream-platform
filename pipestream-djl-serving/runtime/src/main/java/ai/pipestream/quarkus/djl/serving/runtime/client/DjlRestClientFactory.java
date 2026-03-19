package ai.pipestream.quarkus.djl.serving.runtime.client;

import ai.pipestream.quarkus.djl.serving.runtime.config.DjlServingRuntimeConfig;
import io.quarkus.rest.client.reactive.QuarkusRestClientBuilder;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.net.URI;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class DjlRestClientFactory {

    private final DjlServingRuntimeConfig config;

    private final Map<String, DjlServingModelsEndpointClient> djlClients = new ConcurrentHashMap<>();
    private final Map<String, ConsulDiscoveryClient> consulClients = new ConcurrentHashMap<>();

    @Inject
    public DjlRestClientFactory(DjlServingRuntimeConfig config) {
        this.config = config;
    }

    public DjlServingModelsEndpointClient djlClient(String baseUrl) {
        return djlClients.computeIfAbsent(baseUrl, this::buildDjlClient);
    }

    public ConsulDiscoveryClient consulClient(String baseUrl) {
        return consulClients.computeIfAbsent(baseUrl, this::buildConsulClient);
    }

    private DjlServingModelsEndpointClient buildDjlClient(String baseUrl) {
        long timeoutMillis = config.requestTimeout().toMillis();
        return QuarkusRestClientBuilder.newBuilder()
                .baseUri(URI.create(baseUrl))
                .connectTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
                .readTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
                .build(DjlServingModelsEndpointClient.class);
    }

    private ConsulDiscoveryClient buildConsulClient(String baseUrl) {
        long timeoutMillis = config.requestTimeout().toMillis();
        return QuarkusRestClientBuilder.newBuilder()
                .baseUri(URI.create(baseUrl))
                .connectTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
                .readTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
                .build(ConsulDiscoveryClient.class);
    }
}
