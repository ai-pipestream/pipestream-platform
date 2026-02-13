package ai.pipestream.registration.consul;

import io.vertx.ext.consul.ConsulClientOptions;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.ext.consul.ConsulClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.Optional;

/**
 * Shared CDI producer for the Consul client used across Pipestream services.
 * <p>
 * Reads configuration from the {@code pipestream.consul.*} namespace and produces
 * a fully configured {@link ConsulClient} bean. Supports ACL tokens, TLS,
 * datacenter scoping, and connection tuning.
 * <p>
 * Application code can override this producer by providing its own
 * {@link ConsulClient} CDI producer with higher priority.
 */
@ApplicationScoped
public class PipestreamConsulClientProducer {

    private static final Logger LOG = Logger.getLogger(PipestreamConsulClientProducer.class);

    @Inject
    Vertx vertx;

    @ConfigProperty(name = "pipestream.consul.host", defaultValue = "localhost")
    String host;

    @ConfigProperty(name = "pipestream.consul.port", defaultValue = "8500")
    int port;

    @ConfigProperty(name = "pipestream.consul.token", defaultValue = "${CONSUL_HTTP_TOKEN:}")
    Optional<String> token;

    @ConfigProperty(name = "pipestream.consul.dc")
    Optional<String> dc;

    @ConfigProperty(name = "pipestream.consul.ssl", defaultValue = "false")
    boolean ssl;

    @ConfigProperty(name = "pipestream.consul.trust-all", defaultValue = "false")
    boolean trustAll;

    @ConfigProperty(name = "pipestream.consul.verify-host", defaultValue = "true")
    boolean verifyHost;

    @ConfigProperty(name = "pipestream.consul.connect-timeout", defaultValue = "5000")
    int connectTimeout;

    @ConfigProperty(name = "pipestream.consul.idle-timeout", defaultValue = "0")
    int idleTimeout;

    @ConfigProperty(name = "pipestream.consul.timeout", defaultValue = "0")
    long timeout;

    @Produces
    @ApplicationScoped
    public ConsulClient produceConsulClient() {
        LOG.infof("Creating shared Consul client for %s:%d", host, port);

        ConsulClientOptions options = new ConsulClientOptions()
                .setHost(host)
                .setPort(port);

        // ACL token
        token.filter(t -> !t.isBlank()).ifPresent(t -> {
            LOG.debug("Using ACL token for Consul client");
            options.setAclToken(t);
        });

        // Datacenter
        dc.ifPresent(d -> {
            LOG.debugf("Using Consul datacenter: %s", d);
            options.setDc(d);
        });

        // TLS
        if (ssl) {
            LOG.debug("TLS enabled for Consul client");
            options.setSsl(true);
            options.setTrustAll(trustAll);
            options.setVerifyHost(verifyHost);
        }

        // Timeouts
        if (connectTimeout > 0) {
            options.setConnectTimeout(connectTimeout);
        }
        if (idleTimeout > 0) {
            options.setIdleTimeout(idleTimeout);
        }
        if (timeout > 0) {
            options.setTimeout(timeout);
        }

        return ConsulClient.create(vertx, options);
    }
}
