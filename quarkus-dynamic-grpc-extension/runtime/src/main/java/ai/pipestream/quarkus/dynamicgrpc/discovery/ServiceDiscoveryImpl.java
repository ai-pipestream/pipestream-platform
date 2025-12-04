package ai.pipestream.quarkus.dynamicgrpc.discovery;

import jakarta.inject.Qualifier;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Qualifier for ServiceDiscovery implementations.
 */
@Qualifier
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD, ElementType.PARAMETER})
public @interface ServiceDiscoveryImpl {
    /**
     * The type of service discovery implementation.
     *
     * @return the ServiceDiscovery implementation type
     */
    Type value();

    /**
     * Supported discovery strategies.
     */
    enum Type {
        /**
         * Stork-based discovery using configured providers.
         */
        STORK,
        /**
         * Direct Consul discovery without prior Stork configuration.
         */
        CONSUL_DIRECT
    }
}
