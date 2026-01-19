package ai.pipestream.apicurio.registry.protobuf.deployment;

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
import io.quarkus.deployment.console.StartupLogCompressor;
import io.quarkus.deployment.dev.devservices.DevServicesConfig;
import io.quarkus.deployment.logging.LoggingSetupBuildItem;
import io.quarkus.devservices.common.ComposeLocator;
import io.quarkus.devservices.common.ConfigureUtil;
import io.quarkus.devservices.common.ContainerLocator;
import io.quarkus.runtime.LaunchMode;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.jboss.logging.Logger;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Starts Apicurio Registry as a Dev Service for the Protobuf extension.
 *
 * <p>
 * This processor handles the automatic provisioning of an Apicurio Registry
 * container
 * when running in dev or test mode. It ensures that a registry is available for
 * Protobuf schema registration and lookup.
 * </p>
 *
 * <h2>Features</h2>
 * <ul>
 * <li><strong>Automatic Startup:</strong> Starts an Apicurio Registry container
 * if no URL is configured.</li>
 * <li><strong>Version Support:</strong> Defaults to Apicurio Registry v3
 * ({@code apicurio/apicurio-registry:3.1.6}).</li>
 * <li><strong>Configuration Injection:</strong> Automatically configures the
 * application to use the started registry.</li>
 * <li><strong>Container Sharing:</strong> Supports sharing the registry
 * container across multiple Quarkus applications.</li>
 * </ul>
 */
@BuildSteps(onlyIfNot = IsProduction.class, onlyIf = DevServicesConfig.Enabled.class)
public class DevServicesApicurioRegistryProtobufProcessor {

    private static final Logger log = Logger.getLogger(DevServicesApicurioRegistryProtobufProcessor.class);

    private static final String DEV_SERVICE_NAME = "apicurio-registry-protobuf";
    private static final int APICURIO_REGISTRY_PORT = 8080;
    private static final String APICURIO_REGISTRY_URL_CONFIG = "mp.messaging.connector.smallrye-kafka.apicurio.registry.url";
    private static final String APICURIO_REGISTRY_URL_SIMPLE = "apicurio.registry.url";
    private static final String DEV_SERVICE_LABEL = "quarkus-dev-service-apicurio-registry-protobuf";
    private static final String DEFAULT_IMAGE = "apicurio/apicurio-registry:3.1.6";

    /**
     * Locator for finding shared dev service containers with our label.
     */
    private static final ContainerLocator CONTAINER_LOCATOR = locateContainerWithLabels(
            APICURIO_REGISTRY_PORT, DEV_SERVICE_LABEL);

    // Container state for lifecycle management
    static volatile ApicurioRegistryContainer runningContainer;
    static volatile Map<String, String> runningConfig;
    static volatile String runningContainerId;
    static volatile ApicurioRegistryDevServiceCfg cfg;
    static volatile boolean first = true;

    /**
     * Default constructor.
     */
    public DevServicesApicurioRegistryProtobufProcessor() {
    }

    /**
     * Starts the Apicurio Registry Dev Service.
     *
     * @param launchMode                the launch mode
     * @param dockerStatusBuildItem     the Docker status
     * @param composeProjectBuildItem   the Compose project information used to discover services defined via docker-compose
     * @param devServicesSharedNetworkBuildItem a list of shared network build items indicating whether Dev Services use a shared Docker network
     * @param config                    the build time configuration
     * @param consoleInstalledBuildItem the console installed build item
     * @param closeBuildItem            the shutdown build item
     * @param loggingSetupBuildItem     the logging setup build item
     * @param devServicesConfig         the dev services configuration
     * @return the dev services result build item
     */
    @BuildStep
    public DevServicesResultBuildItem startApicurioRegistryDevService(
            LaunchModeBuildItem launchMode,
            DockerStatusBuildItem dockerStatusBuildItem,
            @SuppressWarnings("OptionalUsedAsFieldOrParameterType") Optional<DevServicesComposeProjectBuildItem> composeProjectBuildItem,
            List<DevServicesSharedNetworkBuildItem> devServicesSharedNetworkBuildItem,
            ApicurioRegistryProtobufBuildTimeConfig config,
            ProtobufChannelsBuildItem protobufChannels,
            @SuppressWarnings("OptionalUsedAsFieldOrParameterType") Optional<ConsoleInstalledBuildItem> consoleInstalledBuildItem,
            CuratedApplicationShutdownBuildItem closeBuildItem,
            LoggingSetupBuildItem loggingSetupBuildItem,
            DevServicesConfig devServicesConfig) {

        ApicurioRegistryDevServiceCfg configuration = getConfiguration(config.devservices());

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
            shutdownApicurioRegistry();
            cfg = null;
        }

        StartupLogCompressor compressor = new StartupLogCompressor(
                (launchMode.isTest() ? "(test) " : "") + "Apicurio Registry Protobuf Dev Services Starting:",
                consoleInstalledBuildItem, loggingSetupBuildItem);
        try {
            boolean useSharedNetwork = DevServicesSharedNetworkBuildItem.isSharedNetworkRequired(
                    devServicesConfig, devServicesSharedNetworkBuildItem);

            StartResult result = startApicurioRegistry(dockerStatusBuildItem, composeProjectBuildItem,
                    configuration, launchMode, useSharedNetwork, devServicesConfig.timeout(), protobufChannels);
            compressor.close();

            if (result == null) {
                return null;
            }

            runningContainer = result.container();
            runningContainerId = result.containerId();
            runningConfig = result.config();
            cfg = configuration;

            log.infof("Dev Services for Apicurio Registry (Protobuf) started. The registry is available at %s",
                    runningConfig.get(APICURIO_REGISTRY_URL_CONFIG));

            if (first) {
                first = false;
                Runnable closeTask = () -> {
                    shutdownApicurioRegistry();
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
            compressor.closeAndDumpCaptured();
            throw new RuntimeException(t);
        }
    }

    private Map<String, String> getRegistryUrlConfigs(String baseUrl) {
        // Use v3 API endpoint for Apicurio v3
        String fullUrl = baseUrl + "/apis/registry/v3";
        // Set both the connector-level config and the simpler apicurio.registry.url
        // This ensures both Kafka serializers and any direct Apicurio clients work
        return Map.of(
                APICURIO_REGISTRY_URL_CONFIG, fullUrl,
                APICURIO_REGISTRY_URL_SIMPLE, fullUrl);
    }

    private void shutdownApicurioRegistry() {
        if (runningContainer != null) {
            try {
                runningContainer.stop();
            } catch (Throwable e) {
                log.error("Failed to stop Apicurio Registry", e);
            } finally {
                runningContainer = null;
                runningConfig = null;
            }
        }
    }

    private StartResult startApicurioRegistry(
            DockerStatusBuildItem dockerStatusBuildItem,
            @SuppressWarnings("OptionalUsedAsFieldOrParameterType") Optional<DevServicesComposeProjectBuildItem> composeProjectBuildItem,
            ApicurioRegistryDevServiceCfg config,
            LaunchModeBuildItem launchMode,
            boolean useSharedNetwork,
            @SuppressWarnings("OptionalUsedAsFieldOrParameterType") Optional<Duration> timeout,
            ProtobufChannelsBuildItem protobufChannels) {

        log.warn("DEBUG: Starting Apicurio Registry Dev Services check...");

        if (!config.devServicesEnabled) {
            log.warn("DEBUG: Not starting dev services for Apicurio Registry, as it has been disabled in the config.");
            return null;
        }

        // Check both the connector-level URL and the simpler apicurio.registry.url property
        if (isPropertySet(APICURIO_REGISTRY_URL_CONFIG)) {
            log.warn("DEBUG: Not starting dev services for Apicurio Registry, " + APICURIO_REGISTRY_URL_CONFIG
                    + " is configured.");
            return null;
        }

        if (isPropertySet(APICURIO_REGISTRY_URL_SIMPLE)) {
            log.warn("DEBUG: Not starting dev services for Apicurio Registry, " + APICURIO_REGISTRY_URL_SIMPLE
                    + " is configured.");
            return null;
        }

        // If we detected Protobuf channels via annotations, we definitely need the registry.
        // This handles cases where connector config is injected dynamically and not visible in ConfigProvider.
        boolean hasDetectedProtobufChannels = protobufChannels.hasChannels();

        if (!hasKafkaChannelWithoutRegistry() && !hasDetectedProtobufChannels) {
            log.warn(
                    "DEBUG: Not starting dev services for Apicurio Registry, all the channels have a registry URL configured.");
            return null;
        }

        if (!dockerStatusBuildItem.isContainerRuntimeAvailable()) {
            log.warn("Docker isn't working, please run Apicurio Registry yourself.");
            return null;
        }

        // First, try to locate an existing shared dev service container
        var sharedContainer = CONTAINER_LOCATOR.locateContainer(
                config.serviceName, config.shared, launchMode.getLaunchMode());
        if (sharedContainer.isPresent()) {
            var address = sharedContainer.get();
            log.warn("DEBUG: Found existing shared Apicurio Registry dev service at " + address.getUrl());
            return new StartResult(null, address.getId(), getRegistryUrlConfigs("http://" + address.getUrl()));
        } else {
            log.warn("DEBUG: Shared container not found via label: " + config.serviceName);
        }

        // Second, try to locate a container from Compose Dev Services (only if Compose is available)
        if (composeProjectBuildItem.isPresent()) {
            var composeContainer = ComposeLocator.locateContainer(
                    composeProjectBuildItem.get(),
                    List.of(config.imageName, "apicurio"),
                    APICURIO_REGISTRY_PORT,
                    launchMode.getLaunchMode(),
                    useSharedNetwork);
            if (composeContainer.isPresent()) {
                var address = composeContainer.get();
                log.warn("DEBUG: Found Apicurio Registry from Compose Dev Services at " + address.getUrl());
                return new StartResult(null, address.getId(), getRegistryUrlConfigs("http://" + address.getUrl()));
            } else {
                log.warn("DEBUG: Container not found in Compose project.");
            }
        } else {
            log.debug("DEBUG: Compose Dev Services not available, skipping Compose container lookup.");
        }

        // No existing container found, start our own
        log.warn("DEBUG: No existing container found, starting new ApicurioRegistryContainer...");
        ApicurioRegistryContainer container = new ApicurioRegistryContainer(
                DockerImageName.parse(config.imageName),
                config.fixedExposedPort,
                launchMode.getLaunchMode() == LaunchMode.DEVELOPMENT ? config.serviceName : null,
                composeProjectBuildItem.map(DevServicesComposeProjectBuildItem::getDefaultNetworkId).orElse(null),
                useSharedNetwork);
        timeout.ifPresent(container::withStartupTimeout);
        container.withEnv(config.containerEnv);
        container.start();

        return new StartResult(container, container.getContainerId(), getRegistryUrlConfigs(container.getUrl()));
    }

    private boolean isPropertySet(String propertyName) {
        Config config = ConfigProvider.getConfig();
        return config.getOptionalValue(propertyName, String.class)
                .filter(s -> !s.isEmpty())
                .isPresent();
    }

    private boolean hasKafkaChannelWithoutRegistry() {
        Config config = ConfigProvider.getConfig();
        for (String name : config.getPropertyNames()) {
            boolean isIncoming = name.startsWith("mp.messaging.incoming.");
            boolean isOutgoing = name.startsWith("mp.messaging.outgoing.");
            boolean isConnector = name.endsWith(".connector");
            boolean isKafka = isConnector
                    && "smallrye-kafka".equals(config.getOptionalValue(name, String.class).orElse("ignored"));
            if ((isIncoming || isOutgoing) && isKafka) {
                String registryUrlProp = name.replace(".connector", ".apicurio.registry.url");
                if (!isPropertySet(registryUrlProp)) {
                    return true;
                }
            }
        }
        return false;
    }

    private ApicurioRegistryDevServiceCfg getConfiguration(
            ApicurioRegistryProtobufBuildTimeConfig.DevServicesConfig cfg) {
        return new ApicurioRegistryDevServiceCfg(cfg);
    }

    /**
     * Result of starting or discovering the Apicurio Registry container.
     *
     * @param container   the container we started (null if discovered from compose/shared)
     * @param containerId the container ID (from our container or discovered)
     * @param config      the configuration to inject
     */
    private record StartResult(ApicurioRegistryContainer container, String containerId, Map<String, String> config) {
    }

    private static final class ApicurioRegistryDevServiceCfg {
        private final boolean devServicesEnabled;
        private final String imageName;
        private final Integer fixedExposedPort;
        private final boolean shared;
        private final String serviceName;
        private final Map<String, String> containerEnv;

        public ApicurioRegistryDevServiceCfg(ApicurioRegistryProtobufBuildTimeConfig.DevServicesConfig config) {
            this.devServicesEnabled = config.enabled().orElse(true);
            this.imageName = config.imageName().orElse(DEFAULT_IMAGE);
            this.fixedExposedPort = config.port().orElse(0);
            this.shared = config.shared();
            this.serviceName = config.serviceName();
            this.containerEnv = config.containerEnv();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o)
                return true;
            if (o == null || getClass() != o.getClass())
                return false;
            ApicurioRegistryDevServiceCfg that = (ApicurioRegistryDevServiceCfg) o;
            return devServicesEnabled == that.devServicesEnabled
                    && Objects.equals(imageName, that.imageName)
                    && Objects.equals(fixedExposedPort, that.fixedExposedPort)
                    && shared == that.shared
                    && Objects.equals(serviceName, that.serviceName)
                    && Objects.equals(containerEnv, that.containerEnv);
        }

        @Override
        public int hashCode() {
            return Objects.hash(devServicesEnabled, imageName, fixedExposedPort, shared, serviceName, containerEnv);
        }
    }

    private static final class ApicurioRegistryContainer extends GenericContainer<ApicurioRegistryContainer> {
        private final int fixedExposedPort;
        private final boolean useSharedNetwork;
        private final String hostName;

        @SuppressWarnings("resource")
        private ApicurioRegistryContainer(DockerImageName dockerImageName, int fixedExposedPort, String serviceName,
                String defaultNetworkId, boolean useSharedNetwork) {
            super(dockerImageName);
            this.fixedExposedPort = fixedExposedPort;
            this.useSharedNetwork = useSharedNetwork;

            if (serviceName != null) {
                withLabel(DEV_SERVICE_LABEL, serviceName);
                withLabel(QUARKUS_DEV_SERVICE, serviceName);
            }
            withEnv("QUARKUS_PROFILE", "prod");

            // Configure network for shared network support
            this.hostName = ConfigureUtil.configureNetwork(this, defaultNetworkId, useSharedNetwork, "apicurio-registry");
        }

        @Override
        protected void configure() {
            super.configure();

            if (useSharedNetwork) {
                // When using shared network, don't expose ports externally
                return;
            }

            if (fixedExposedPort > 0) {
                addFixedExposedPort(fixedExposedPort, APICURIO_REGISTRY_PORT);
            } else {
                addExposedPorts(APICURIO_REGISTRY_PORT);
            }
        }

        public String getUrl() {
            return String.format("http://%s:%s", getHostToUse(), getPortToUse());
        }

        private String getHostToUse() {
            return useSharedNetwork ? hostName : getHost();
        }

        private int getPortToUse() {
            return useSharedNetwork ? APICURIO_REGISTRY_PORT : getMappedPort(APICURIO_REGISTRY_PORT);
        }
    }
}
