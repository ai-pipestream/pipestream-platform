package ai.pipestream.registration;

import ai.pipestream.registration.consul.ConsulRegistrar;
import ai.pipestream.registration.model.ServiceInfo;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Identity of <em>this</em> running service process — the same string used
 * when registering with Consul.
 *
 * <p>Format: {@code <service-name>-<advertised-host>-<advertised-port>}
 * (e.g. {@code connector-intake-172.17.0.1-28108}). Stable for the JVM
 * lifetime; uniquely identifies one replica even when the service is
 * scaled out across multiple processes.
 *
 * <p>Use this anywhere you need a per-replica identity — Redis private
 * queue names, distributed-lock owner ids, log prefixes, etc. Avoid
 * inventing your own scheme; this one matches what Consul sees, which
 * makes correlation trivial.
 */
@ApplicationScoped
public class LocalServiceIdentity {

    private final ServiceMetadataCollector collector;

    private volatile String serviceId;
    private volatile String serviceName;
    private volatile String advertisedHost;
    private volatile Integer advertisedPort;

    /**
     * Default constructor for CDI.
     */
    LocalServiceIdentity() {
        this.collector = null;
    }

    @Inject
    public LocalServiceIdentity(ServiceMetadataCollector collector) {
        this.collector = collector;
    }

    /**
     * Returns the unique service id for this process — same value Consul
     * sees. Lazily resolved on first call, then cached for the JVM lifetime.
     */
    public String serviceId() {
        ensureResolved();
        return serviceId;
    }

    /**
     * Returns the configured service name (e.g. {@code connector-intake}).
     */
    public String serviceName() {
        ensureResolved();
        return serviceName;
    }

    /**
     * Returns the host this process advertises.
     */
    public String advertisedHost() {
        ensureResolved();
        return advertisedHost;
    }

    /**
     * Returns the port this process advertises.
     */
    public int advertisedPort() {
        ensureResolved();
        return advertisedPort;
    }

    private void ensureResolved() {
        if (serviceId != null) return;
        synchronized (this) {
            if (serviceId != null) return;
            ServiceInfo info = collector.collect();
            this.serviceName = info.getName();
            this.advertisedHost = info.getAdvertisedHost();
            this.advertisedPort = info.getAdvertisedPort();
            this.serviceId = ConsulRegistrar.generateServiceId(
                    info.getName(), info.getAdvertisedHost(), info.getAdvertisedPort());
        }
    }
}
