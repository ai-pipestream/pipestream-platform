package ai.pipestream.registration.deployment;

import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

import java.util.Map;
import java.util.Optional;

/**
 * Build-time configuration for the service registration extension.
 */
@ConfigMapping(prefix = "quarkus.pipestream.service.registration")
@ConfigRoot(phase = ConfigPhase.BUILD_TIME)
public interface RegistrationBuildTimeConfig {

    /**
     * Dev Services configuration for Consul.
     */
    DevServicesConfig devservices();

    /**
     * Configuration for Dev Services.
     */
    interface DevServicesConfig {
        /**
         * If Dev Services for Consul has been explicitly enabled or disabled.
         *
         * <p>
         * By default, Dev Services is enabled unless a consul host/port is configured.
         * </p>
         */
        Optional<Boolean> enabled();

        /**
         * The Consul image to use.
         *
         * <p>
         * Defaults to {@code hashicorp/consul:1.22} if not specified.
         * </p>
         */
        @WithDefault("hashicorp/consul:1.22")
        String imageName();

        /**
         * Optional fixed port the dev service will listen to.
         *
         * <p>
         * If not defined, a random available port will be chosen.
         * </p>
         */
        Optional<Integer> port();

        /**
         * Indicates if the Consul instance managed by Quarkus Dev Services is shared.
         *
         * <p>
         * When shared, Quarkus looks for running containers using label-based service discovery.
         * If a matching container is found, it is used, and a new one is not started.
         * </p>
         */
        @WithDefault("false")
        boolean shared();

        /**
         * The value of the {@code quarkus-dev-service-consul} label.
         *
         * <p>
         * This label is used to identify the shared container.
         * </p>
         */
        @WithDefault("consul-registration")
        String serviceName();

        /**
         * Environment variables that are passed to the container.
         */
        Map<String, String> containerEnv();
    }
}