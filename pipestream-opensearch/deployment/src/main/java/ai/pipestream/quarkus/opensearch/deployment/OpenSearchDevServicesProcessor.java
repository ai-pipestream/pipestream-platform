package ai.pipestream.quarkus.opensearch.deployment;

import ai.pipestream.quarkus.opensearch.config.OpenSearchBuildTimeConfig;
import io.quarkus.deployment.IsNormal;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.DevServicesResultBuildItem;
import io.quarkus.deployment.builditem.DockerStatusBuildItem;
import io.quarkus.deployment.builditem.LaunchModeBuildItem;
import io.quarkus.runtime.configuration.ConfigUtils;
import org.jboss.logging.Logger;
import org.opensearch.testcontainers.OpenSearchContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.HashMap;
import java.util.Map;

/**
 * OpenSearch DevServices processor.
 * 
 * <p>Starts an OpenSearch testcontainer when:
 * <ul>
 *   <li>Not in NORMAL (production) mode</li>
 *   <li>DevServices is enabled (default: true)</li>
 *   <li>Docker is available</li>
 *   <li>opensearch.hosts is not already configured</li>
 * </ul>
 * 
 * <p>The processor automatically provides configuration for the OpenSearch client
 * by setting opensearch.hosts and opensearch.protocol properties.
 */
public class OpenSearchDevServicesProcessor {

    private static final Logger LOG = Logger.getLogger(OpenSearchDevServicesProcessor.class);

    private static final String OPENSEARCH_HOSTS_CONFIG = "opensearch.hosts";
    private static final String OPENSEARCH_PROTOCOL_CONFIG = "opensearch.protocol";

    @BuildStep(onlyIfNot = IsNormal.class)
    public DevServicesResultBuildItem startOpenSearchDevService(
            DockerStatusBuildItem dockerStatusBuildItem,
            LaunchModeBuildItem launchMode,
            OpenSearchBuildTimeConfig config) {

        OpenSearchBuildTimeConfig.DevServicesConfig dsConfig = config.devservices();

        // Check if DevServices should be disabled
        if (!dsConfig.enabled()) {
            LOG.debug("OpenSearch DevServices disabled by configuration");
            return null;
        }

        if (ConfigUtils.isPropertyNonEmpty(OPENSEARCH_HOSTS_CONFIG)) {
            LOG.debugf("OpenSearch DevServices disabled: %s is already configured", 
                    OPENSEARCH_HOSTS_CONFIG);
            return null;
        }

        if (!dockerStatusBuildItem.isContainerRuntimeAvailable()) {
            LOG.warn("OpenSearch DevServices disabled: Docker is not available. " +
                    "Please configure " + OPENSEARCH_HOSTS_CONFIG + " manually.");
            return null;
        }

        try {
            LOG.info("Starting OpenSearch DevServices container...");

            DockerImageName imageName = DockerImageName.parse(dsConfig.imageName());

            OpenSearchContainer<?> container = new OpenSearchContainer<>(imageName)
                    .withEnv("DISABLE_SECURITY_PLUGIN", "true")
                    .withEnv("discovery.type", "single-node")
                    .withEnv("bootstrap.memory_lock", "true")
                    .withEnv("OPENSEARCH_JAVA_OPTS", dsConfig.javaOpts());

            // Configure fixed port if specified
            dsConfig.port().ifPresent(port -> {
                if (port > 0) {
                    LOG.debugf("Configuring OpenSearch container with fixed port: %d", port);
                }
            });

            // Add label for container identification
            container.withLabel("quarkus-dev-service-opensearch", dsConfig.serviceName());

            container.start();

            // Get the host address (includes host:port)
            String httpHostAddress = container.getHttpHostAddress();
            // Remove protocol prefix if present
            String hosts = httpHostAddress.replace("http://", "").replace("https://", "");

            Map<String, String> configMap = new HashMap<>();
            configMap.put(OPENSEARCH_HOSTS_CONFIG, hosts);
            configMap.put(OPENSEARCH_PROTOCOL_CONFIG, "http");

            LOG.infof("OpenSearch DevServices started at %s (image: %s)", 
                    httpHostAddress, dsConfig.imageName());

            // Run extension-specific initialization
            initializeOpenSearch(httpHostAddress, dsConfig);

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

    /**
     * Extension-specific initialization that runs after container start.
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
