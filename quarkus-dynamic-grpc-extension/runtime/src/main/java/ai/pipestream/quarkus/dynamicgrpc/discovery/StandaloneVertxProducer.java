package ai.pipestream.quarkus.dynamicgrpc.discovery;

import io.quarkus.arc.DefaultBean;
import io.vertx.core.Vertx;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Producer for Vertx when running the dynamic-grpc module standalone.
 * <p>
 * This producer provides a default Vertx instance when no other instance
 * is available (e.g., when running without Quarkus vertx extensions).
 * </p>
 * <p>
 * The DefaultBean annotation ensures this is only used when no other Vertx
 * bean is available.
 * </p>
 */
@ApplicationScoped
public class StandaloneVertxProducer {

    /**
     * Default constructor for CDI.
     */
    public StandaloneVertxProducer() {
    }

    private static final Logger LOG = Logger.getLogger(StandaloneVertxProducer.class);

    @Inject
    io.vertx.mutiny.core.Vertx mutinyVertx;

    /**
     * Produces a default Vertx instance for standalone usage.
     *
     * @return Vertx instance
     */
    @Produces
    @DefaultBean
    @ApplicationScoped
    public Vertx produceVertx() {
        LOG.info("Producing default Vertx for standalone dynamic-grpc module");
        return mutinyVertx.getDelegate();
    }
}
