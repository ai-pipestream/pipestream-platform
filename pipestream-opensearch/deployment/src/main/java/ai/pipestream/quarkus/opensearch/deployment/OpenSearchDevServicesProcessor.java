package ai.pipestream.quarkus.opensearch.deployment;

import ai.pipestream.quarkus.opensearch.config.OpenSearchBuildTimeConfig;
import io.quarkus.deployment.IsDevelopment;
import io.quarkus.deployment.IsNormal;
import io.quarkus.deployment.IsTest;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.DevServicesComposeProjectBuildItem;
import io.quarkus.deployment.builditem.DevServicesResultBuildItem;
import io.quarkus.deployment.builditem.DevServicesSharedNetworkBuildItem;
import io.quarkus.deployment.builditem.DockerStatusBuildItem;
import io.quarkus.deployment.builditem.LaunchModeBuildItem;
import io.quarkus.deployment.dev.devservices.DevServicesConfig;
import io.quarkus.devservices.common.ComposeLocator;
import io.quarkus.devservices.common.ContainerAddress;
import io.quarkus.runtime.configuration.ConfigUtils;
import org.jboss.logging.Logger;
import org.opensearch.testcontainers.OpenSearchContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Compose-aware OpenSearch DevServices processor.
 * 
 * <h2>Behavior by Mode:</h2>
 * <ul>
 *   <li><b>DEV mode:</b> Checks if compose-devservices is providing OpenSearch.
 *       If found, yields container management to compose and uses its connection info.
 *       This allows OpenSearch to be part of the shared dev infrastructure.</li>
 *   <li><b>TEST mode:</b> Starts an isolated testcontainer for each test run.
 *       This ensures tests are isolated and don't depend on external compose setup.</li>
 *   <li><b>NORMAL (production) mode:</b> Does nothing - expects manual configuration.</li>
 * </ul>
 * 
 * <h2>Compose Detection:</h2>
 * <p>The processor looks for OpenSearch in compose-devservices by matching:
 * <ul>
 *   <li>Image names: "opensearchproject/opensearch", "opensearch"</li>
 *   <li>Port: 9200</li>
 * </ul>
 */
public class OpenSearchDevServicesProcessor {

    private static final Logger LOG = Logger.getLogger(OpenSearchDevServicesProcessor.class);

    private static final String OPENSEARCH_HOSTS_CONFIG = "opensearch.hosts";
    private static final String OPENSEARCH_PROTOCOL_CONFIG = "opensearch.protocol";
    private static final int OPENSEARCH_PORT = 9200;
    
    // Image patterns to match in compose-devservices
    private static final List<String> COMPOSE_IMAGE_PATTERNS = List.of(
            "opensearchproject/opensearch",
            "opensearch"
    );

    /**
     * DEV mode: Try to use compose-devservices first, fall back to testcontainer.
     */
    @BuildStep(onlyIf = IsDevelopment.class)
    public DevServicesResultBuildItem startOpenSearchDevServiceDev(
            DockerStatusBuildItem dockerStatusBuildItem,
            Optional<DevServicesComposeProjectBuildItem> compose,
            LaunchModeBuildItem launchMode,
            OpenSearchBuildTimeConfig config,
            List<DevServicesSharedNetworkBuildItem> sharedNetwork,
            DevServicesConfig devServicesConfig) {

        OpenSearchBuildTimeConfig.DevServicesConfig dsConfig = config.devservices();

        // Check if DevServices should be disabled
        if (shouldDisableDevServices(dockerStatusBuildItem, dsConfig)) {
            return null;
        }

        boolean useSharedNetwork = DevServicesSharedNetworkBuildItem.isSharedNetworkRequired(
                devServicesConfig, sharedNetwork);

        // Try to locate OpenSearch in compose-devservices
        Optional<ContainerAddress> composeContainer = Optional.empty();
        if (compose.isPresent()) {
            composeContainer = ComposeLocator.locateContainer(
                    compose.get(),
                    COMPOSE_IMAGE_PATTERNS,
                    OPENSEARCH_PORT,
                    launchMode.getLaunchMode(),
                    useSharedNetwork);
        }

        if (composeContainer.isPresent()) {
            // Found in compose - yield to compose for container management
            ContainerAddress address = composeContainer.get();
            LOG.infof("OpenSearch DevServices: Using compose-devservices container at %s", 
                    address.getUrl());
            
            // Run extension-specific initialization
            initializeOpenSearch(address.getUrl(), dsConfig);

            Map<String, String> configMap = buildConfigMap(address.getUrl());
            
            // Return "discovered" result - we don't own the container
            return new DevServicesResultBuildItem.RunningDevService(
                    "opensearch",
                    address.getId(),
                    null,  // No close action - compose manages the container
                    configMap
            ).toBuildItem();
        }

        // Not found in compose - start our own testcontainer
        LOG.info("OpenSearch DevServices: No compose container found, starting testcontainer");
        return startTestContainer(dsConfig);
    }

    /**
     * TEST mode: Always start an isolated testcontainer.
     * Tests should not rely on compose-devservices to ensure isolation.
     */
    @BuildStep(onlyIf = IsTest.class, onlyIfNot = IsDevelopment.class)
    public DevServicesResultBuildItem startOpenSearchDevServiceTest(
            DockerStatusBuildItem dockerStatusBuildItem,
            LaunchModeBuildItem launchMode,
            OpenSearchBuildTimeConfig config) {

        OpenSearchBuildTimeConfig.DevServicesConfig dsConfig = config.devservices();

        // Check if DevServices should be disabled
        if (shouldDisableDevServices(dockerStatusBuildItem, dsConfig)) {
            return null;
        }

        LOG.info("OpenSearch DevServices: Starting testcontainer for TEST mode");
        return startTestContainer(dsConfig);
    }

    private boolean shouldDisableDevServices(
            DockerStatusBuildItem dockerStatusBuildItem,
            OpenSearchBuildTimeConfig.DevServicesConfig config) {

        if (!config.enabled()) {
            LOG.debug("OpenSearch DevServices disabled by configuration");
            return true;
        }

        if (ConfigUtils.isPropertyNonEmpty(OPENSEARCH_HOSTS_CONFIG)) {
            LOG.debugf("OpenSearch DevServices disabled: %s is already configured", 
                    OPENSEARCH_HOSTS_CONFIG);
            return true;
        }

        if (!dockerStatusBuildItem.isContainerRuntimeAvailable()) {
            LOG.warn("OpenSearch DevServices disabled: Docker is not available. " +
                    "Please configure " + OPENSEARCH_HOSTS_CONFIG + " manually.");
            return true;
        }

        return false;
    }

    private DevServicesResultBuildItem startTestContainer(
            OpenSearchBuildTimeConfig.DevServicesConfig dsConfig) {
        try {
            DockerImageName imageName = DockerImageName.parse(dsConfig.imageName());

            OpenSearchContainer<?> container = new OpenSearchContainer<>(imageName)
                    .withEnv("DISABLE_SECURITY_PLUGIN", "true")
                    .withEnv("discovery.type", "single-node")
                    .withEnv("bootstrap.memory_lock", "true")
                    .withEnv("OPENSEARCH_JAVA_OPTS", dsConfig.javaOpts());

            // Add label for container identification
            container.withLabel("quarkus-dev-service-opensearch", dsConfig.serviceName());

            container.start();

            String httpHostAddress = container.getHttpHostAddress();
            LOG.infof("OpenSearch DevServices started at %s (image: %s)", 
                    httpHostAddress, dsConfig.imageName());

            // Run extension-specific initialization
            initializeOpenSearch(httpHostAddress, dsConfig);

            Map<String, String> configMap = buildConfigMap(httpHostAddress);

            return new DevServicesResultBuildItem.RunningDevService(
                    "opensearch",
                    container.getContainerId(),
                    container::close,
                    configMap
            ).toBuildItem();

        } catch (Exception e) {
            throw new RuntimeException("Failed to start OpenSearch DevServices", e);
        }
    }

    private Map<String, String> buildConfigMap(String hostUrl) {
        // Remove protocol prefix if present
        String hosts = hostUrl.replace("http://", "").replace("https://", "");
        
        Map<String, String> configMap = new HashMap<>();
        configMap.put(OPENSEARCH_HOSTS_CONFIG, hosts);
        configMap.put(OPENSEARCH_PROTOCOL_CONFIG, "http");
        return configMap;
    }

    /**
     * Extension-specific initialization that runs after container is available.
     * This is where you can create default indexes, mappings, templates, etc.
     */
    private void initializeOpenSearch(String hostUrl, OpenSearchBuildTimeConfig.DevServicesConfig config) {
        LOG.debugf("Initializing OpenSearch at %s", hostUrl);
        
        // TODO: Add extension-specific initialization here
        // Examples:
        // - Create default index templates
        // - Set up initial mappings
        // - Configure k-NN settings
        // - Register ingest pipelines
        
        LOG.infof("OpenSearch DevServices ready (service: %s)", config.serviceName());
    }
}
