package ai.pipestream.quarkus.opensearch.client;

import ai.pipestream.quarkus.opensearch.config.OpenSearchRuntimeConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.apache.hc.client5.http.auth.AuthScope;
import org.apache.hc.client5.http.auth.UsernamePasswordCredentials;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.auth.BasicCredentialsProvider;
import org.apache.hc.client5.http.impl.nio.PoolingAsyncClientConnectionManager;
import org.apache.hc.client5.http.impl.nio.PoolingAsyncClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.ClientTlsStrategyBuilder;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.nio.ssl.TlsStrategy;
import org.apache.hc.core5.reactor.IOReactorConfig;
import org.apache.hc.core5.ssl.SSLContextBuilder;
import org.apache.hc.core5.util.Timeout;
import org.jboss.logging.Logger;
import org.opensearch.client.json.jackson.JacksonJsonpMapper;
import org.opensearch.client.opensearch.OpenSearchAsyncClient;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.transport.httpclient5.ApacheHttpClient5Transport;
import org.opensearch.client.transport.httpclient5.ApacheHttpClient5TransportBuilder;

import javax.net.ssl.SSLContext;
import java.util.Arrays;

/**
 * CDI producer for OpenSearch clients.
 * Produces both synchronous and asynchronous clients configured from application properties.
 */
@ApplicationScoped
public class OpenSearchClientProducer {

    private static final Logger LOG = Logger.getLogger(OpenSearchClientProducer.class);

    @Inject
    OpenSearchRuntimeConfig config;

    private ApacheHttpClient5Transport transport;

    @Produces
    @Singleton
    public ApacheHttpClient5Transport transport() {
        if (transport == null) {
            transport = createTransport();
        }
        return transport;
    }

    @Produces
    @Singleton
    public OpenSearchClient openSearchClient(ApacheHttpClient5Transport transport) {
        LOG.infof("Creating OpenSearchClient connected to %s://%s", config.protocol(), config.hosts());
        return new OpenSearchClient(transport);
    }

    @Produces
    @Singleton
    public OpenSearchAsyncClient openSearchAsyncClient(ApacheHttpClient5Transport transport) {
        LOG.infof("Creating OpenSearchAsyncClient connected to %s://%s", config.protocol(), config.hosts());
        return new OpenSearchAsyncClient(transport);
    }

    private ApacheHttpClient5Transport createTransport() {
        HttpHost[] hosts = parseHosts();
        LOG.infof("Configuring OpenSearch transport for hosts: %s", Arrays.toString(hosts));

        ApacheHttpClient5TransportBuilder builder = ApacheHttpClient5TransportBuilder.builder(hosts);
        builder.setMapper(new JacksonJsonpMapper());

        // Configure HTTP client
        builder.setHttpClientConfigCallback(httpClientBuilder -> {
            // Configure connection pool
            try {
                PoolingAsyncClientConnectionManagerBuilder connectionManagerBuilder = 
                    PoolingAsyncClientConnectionManagerBuilder.create();

                // Configure SSL if using HTTPS
                if ("https".equalsIgnoreCase(config.protocol())) {
                    TlsStrategy tlsStrategy = buildTlsStrategy();
                    connectionManagerBuilder.setTlsStrategy(tlsStrategy);
                }

                // Configure connection settings
                ConnectionConfig connectionConfig = ConnectionConfig.custom()
                        .setConnectTimeout(Timeout.ofMilliseconds(config.connectionTimeout()))
                        .setSocketTimeout(Timeout.ofMilliseconds(config.socketTimeout()))
                        .build();
                connectionManagerBuilder.setDefaultConnectionConfig(connectionConfig);

                PoolingAsyncClientConnectionManager connectionManager = connectionManagerBuilder.build();
                connectionManager.setMaxTotal(config.maxConnections());
                connectionManager.setDefaultMaxPerRoute(config.maxConnectionsPerRoute());

                httpClientBuilder.setConnectionManager(connectionManager);

                // Configure IO reactor
                IOReactorConfig ioReactorConfig = IOReactorConfig.custom()
                        .setSoTimeout(Timeout.ofMilliseconds(config.socketTimeout()))
                        .build();
                httpClientBuilder.setIOReactorConfig(ioReactorConfig);

                // Configure authentication if provided
                if (config.username().isPresent() && config.password().isPresent()) {
                    BasicCredentialsProvider credentialsProvider = new BasicCredentialsProvider();
                    credentialsProvider.setCredentials(
                            new AuthScope(null, -1),
                            new UsernamePasswordCredentials(
                                    config.username().get(),
                                    config.password().get().toCharArray()
                            )
                    );
                    httpClientBuilder.setDefaultCredentialsProvider(credentialsProvider);
                }

                // Disable content compression to avoid GZIP issues with testcontainers
                RequestConfig requestConfig = RequestConfig.custom()
                        .setContentCompressionEnabled(false)
                        .build();
                httpClientBuilder.setDefaultRequestConfig(requestConfig);

            } catch (Exception e) {
                throw new RuntimeException("Failed to configure OpenSearch HTTP client", e);
            }

            return httpClientBuilder;
        });

        return builder.build();
    }

    private TlsStrategy buildTlsStrategy() {
        try {
            SSLContextBuilder sslBuilder = new SSLContextBuilder();

            if (!config.sslVerify()) {
                sslBuilder.loadTrustMaterial(null, (chain, authType) -> true);
            }

            SSLContext sslContext = sslBuilder.build();

            ClientTlsStrategyBuilder tlsStrategyBuilder = ClientTlsStrategyBuilder.create()
                    .setSslContext(sslContext);

            if (!config.sslVerifyHostname()) {
                tlsStrategyBuilder.setHostnameVerifier((hostname, session) -> true);
            }

            return tlsStrategyBuilder.build();
        } catch (Exception e) {
            throw new RuntimeException("Failed to build TLS strategy", e);
        }
    }

    private HttpHost[] parseHosts() {
        String hostsConfig = config.hosts();
        String protocol = config.protocol();

        return Arrays.stream(hostsConfig.split(","))
                .map(String::trim)
                .map(hostPort -> {
                    String[] parts = hostPort.split(":");
                    String host = parts[0];
                    int port = parts.length > 1 ? Integer.parseInt(parts[1]) : 9200;
                    return new HttpHost(protocol, host, port);
                })
                .toArray(HttpHost[]::new);
    }
}
