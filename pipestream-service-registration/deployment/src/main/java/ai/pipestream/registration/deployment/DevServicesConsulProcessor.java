package ai.pipestream.registration.deployment;

import static io.quarkus.devservices.common.ContainerLocator.locateContainerWithLabels;
import static io.quarkus.devservices.common.Labels.QUARKUS_DEV_SERVICE;

import io.quarkus.deployment.IsProduction;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.BuildSteps;
import io.quarkus.deployment.builditem.CuratedApplicationShutdownBuildItem;
import io.quarkus.deployment.builditem.DevServicesComposeProjectBuildItem;
import io.quarkus.deployment.builditem.DevServicesResultBuildItem;
import io.quarkus.deployment.builditem.DevServicesSharedNetworkBuildItem;
import io.quarkus.deployment.builditem.DockerStatusBuildItem;
import io.quarkus.deployment.builditem.LaunchModeBuildItem;
import io.quarkus.deployment.console.ConsoleInstalledBuildItem;
import io.quarkus.deployment.logging.LoggingSetupBuildItem;
import io.quarkus.devservices.common.ComposeLocator;
import io.quarkus.devservices.common.ConfigureUtil;
import io.quarkus.deployment.dev.devservices.DevServicesConfig;
import io.quarkus.runtime.LaunchMode;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.jboss.logging.Logger;
import org.testcontainers.consul.ConsulContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Dev Services processor for Consul.
 *
 * <p>
 * This processor automatically starts a Consul instance in development and test modes
 * when no Consul configuration is provided. It supports:
 * </p>
 *
 * <ul>
 * <li><strong>Automatic Startup:</strong> Starts a Consul container
 * ({@code hashicorp/consul:1.22}) when Consul is not configured.</li>
 * <li><strong>Configuration Injection:</strong> Automatically configures the
 * application to use the started Consul instance.</li>
 * <li><strong>Container Sharing:</strong> Supports sharing the Consul
 * container across multiple Quarkus applications.</li>
 * </ul>
 */
@BuildSteps(onlyIfNot = IsProduction.class)
public class DevServicesConsulProcessor {

    private static final Logger log = Logger.getLogger(DevServicesConsulProcessor.class);

    private static final String DEV_SERVICE_NAME = "consul";
    private static final int CONSUL_HTTP_PORT = 8500;
    private static final String CONSUL_HOST_CONFIG = "quarkus.pipestream.service.registration.consul.host";
    private static final String CONSUL_PORT_CONFIG = "quarkus.pipestream.service.registration.consul.port";
    private static final String DEV_SERVICE_LABEL = "quarkus-dev-service-consul";
    private static final String DEFAULT_IMAGE = "hashicorp/consul:1.22";

    /**
     * Locator for finding shared dev service containers with our label.
     */
    private static final io.quarkus.devservices.common.ContainerLocator CONTAINER_LOCATOR = locateContainerWithLabels(
            CONSUL_HTTP_PORT, DEV_SERVICE_LABEL);

    // Container state for lifecycle management
    static volatile ConsulContainer runningContainer;
    static volatile Map<String, String> runningConfig;
    static volatile String runningContainerId;
    static volatile RegistrationBuildTimeConfig.DevServicesConfig cfg;
    static volatile boolean first = true;

    /**
     * Default constructor.
     */
    public DevServicesConsulProcessor() {
    }

    /**
     * Starts the Consul Dev Service.
     */
    @BuildStep
    public DevServicesResultBuildItem startConsulDevService(
            LaunchModeBuildItem launchMode,
            DockerStatusBuildItem dockerStatusBuildItem,
            DevServicesComposeProjectBuildItem composeProjectBuildItem,
            List<DevServicesSharedNetworkBuildItem> devServicesSharedNetworkBuildItem,
            RegistrationBuildTimeConfig config,
            @SuppressWarnings("OptionalUsedAsFieldOrParameterType") Optional<ConsoleInstalledBuildItem> consoleInstalledBuildItem,
            CuratedApplicationShutdownBuildItem closeBuildItem,
            LoggingSetupBuildItem loggingSetupBuildItem,
            DevServicesConfig devServicesConfig) {

        RegistrationBuildTimeConfig.DevServicesConfig configuration = config.devservices();

        // Only start Consul DevServices if explicitly enabled
        // This prevents unnecessary Consul startup for services that don't need registration
        if (!configuration.enabled().orElse(false)) {
            log.debug("Consul Dev Services not enabled, skipping. Enable with quarkus.pipestream.service.registration.devservices.consul.enabled=true");
            return null;
        }

        // Check if Consul is already configured
        Config runtimeConfig = ConfigProvider.getConfig();
        Optional<String> consulHost = runtimeConfig.getOptionalValue(CONSUL_HOST_CONFIG, String.class);
        Optional<String> consulPort = runtimeConfig.getOptionalValue(CONSUL_PORT_CONFIG, String.class);

        boolean alreadyConfigured = consulHost.isPresent() || consulPort.isPresent();

        if (alreadyConfigured) {
            log.infof("Consul already configured at %s:%s, skipping Dev Services",
                consulHost.orElse("localhost"), consulPort.orElse("8500"));
            return null;
        }

        if (runningContainer != null || runningContainerId != null) {
            boolean restartRequired = !configuration.equals(cfg);
            if (!restartRequired) {
                String containerId = runningContainer != null ? runningContainer.getContainerId() : runningContainerId;
                return DevServicesResultBuildItem.discovered()
                        .name(DEV_SERVICE_NAME)
                        .containerId(containerId)
                        .config(runningConfig)
                        .build();
            }
            shutdownConsul();
            cfg = null;
        }

        io.quarkus.deployment.console.StartupLogCompressor compressor = new io.quarkus.deployment.console.StartupLogCompressor(
                (launchMode.isTest() ? "(test) " : "") + "Consul Dev Services Starting:",
                consoleInstalledBuildItem, loggingSetupBuildItem);
        try {
            boolean useSharedNetwork = DevServicesSharedNetworkBuildItem.isSharedNetworkRequired(
                    devServicesConfig, devServicesSharedNetworkBuildItem);

            StartResult result = startConsul(dockerStatusBuildItem, composeProjectBuildItem,
                    configuration, launchMode, useSharedNetwork, devServicesConfig.timeout());
            compressor.close();

            if (result == null) {
                return null;
            }

            runningContainer = result.container();
            runningContainerId = result.containerId();
            runningConfig = result.config();
            cfg = configuration;

            if (first) {
                first = false;
                Runnable closeTask = () -> {
                    shutdownConsul();
                    first = true;
                    runningContainer = null;
                    runningContainerId = null;
                    runningConfig = null;
                    cfg = null;
                };
                closeBuildItem.addCloseTask(closeTask, true);
            }

            String containerId = runningContainer != null ? runningContainer.getContainerId() : runningContainerId;
            return DevServicesResultBuildItem.discovered()
                    .name(DEV_SERVICE_NAME)
                    .containerId(containerId)
                    .config(runningConfig)
                    .build();

        } catch (Throwable t) {
            compressor.close();
            throw new RuntimeException(t);
        }
    }

    private StartResult startConsul(DockerStatusBuildItem dockerStatusBuildItem,
            DevServicesComposeProjectBuildItem composeProjectBuildItem,
            RegistrationBuildTimeConfig.DevServicesConfig configuration,
            LaunchModeBuildItem launchMode, boolean useSharedNetwork, Optional<Duration> timeout) {

        if (!dockerStatusBuildItem.isContainerRuntimeAvailable()) {
            log.warn("Docker isn't working, please configure Consul location.");
            return null;
        }

        // Check for shared container
        var sharedContainer = CONTAINER_LOCATOR.locateContainer(
                configuration.serviceName(), configuration.shared(), launchMode.getLaunchMode());
        if (sharedContainer.isPresent()) {
            var address = sharedContainer.get();
            return new StartResult(null, address.getId(), Map.of(
                CONSUL_HOST_CONFIG, address.getHost(),
                CONSUL_PORT_CONFIG, String.valueOf(address.getPort())
            ));
        }

        // Start new container
        DockerImageName dockerImageName = DockerImageName.parse(configuration.imageName())
                .asCompatibleSubstituteFor(DEFAULT_IMAGE);

        ConsulContainer container = new ConsulContainer(dockerImageName)
                .withStartupTimeout(timeout.orElse(Duration.ofSeconds(60)));

        // Add container environment variables
        configuration.containerEnv().forEach(container::withEnv);

        // Add dev service labels
        container.withLabel(QUARKUS_DEV_SERVICE, DEV_SERVICE_LABEL);
        container.withLabel(DEV_SERVICE_LABEL, configuration.serviceName());

        container.start();

        String host = container.getHost();
        Integer port = container.getFirstMappedPort();

        Map<String, String> config = Map.of(
            CONSUL_HOST_CONFIG, host,
            CONSUL_PORT_CONFIG, String.valueOf(port)
        );

        return new StartResult(container, container.getContainerId(), config);
    }

    private void shutdownConsul() {
        if (runningContainer != null) {
            try {
                runningContainer.stop();
            } catch (Exception e) {
                log.warn("Failed to stop Consul container", e);
            }
            runningContainer = null;
        }
        runningContainerId = null;
        runningConfig = null;
        cfg = null;
    }

    /**
     * Result of starting a Consul container.
     */
    private static final class StartResult {
        private final ConsulContainer container;
        private final String containerId;
        private final Map<String, String> config;

        StartResult(ConsulContainer container, String containerId, Map<String, String> config) {
            this.container = container;
            this.containerId = containerId;
            this.config = config;
        }

        ConsulContainer container() {
            return container;
        }

        String containerId() {
            return containerId;
        }

        Map<String, String> config() {
            return config;
        }
    }
}