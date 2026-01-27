package ai.pipestream.test.support.deployment;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.jboss.logging.Logger;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.DockerImageName;

import io.quarkus.deployment.IsNormal;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.DevServicesResultBuildItem;
import io.quarkus.deployment.builditem.DockerStatusBuildItem;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.LaunchModeBuildItem;
import io.quarkus.deployment.builditem.RunTimeConfigurationDefaultBuildItem;
import io.quarkus.deployment.console.ConsoleInstalledBuildItem;
import io.quarkus.deployment.logging.LoggingSetupBuildItem;

/**
 * Quarkus build processor that provides S3 (SeaweedFS) dev services for local development.
 */
public class S3DevServicesProcessor {

    private static final String FEATURE = "pipestream-test-support";
    private static final String SEAWEEDFS_IMAGE = "chrislusf/seaweedfs:latest";
    private static final int S3_PORT = 8333;
    private static final int MASTER_PORT = 9333;
    private static final String ACCESS_KEY = "any";
    private static final String SECRET_KEY = "any";
    private static final String S3_CONFIG_PATH = "/etc/seaweedfs/s3.json";
    private static final Logger LOG = Logger.getLogger(S3DevServicesProcessor.class);

    /**
     * S3 configuration JSON for SeaweedFS authentication.
     * Without this config, SeaweedFS rejects signed requests from AWS SDK.
     */
    private static final String S3_CONFIG_JSON = """
            {
              "identities": [
                {
                  "name": "dev_user",
                  "credentials": [
                    {
                      "accessKey": "%s",
                      "secretKey": "%s"
                    }
                  ],
                  "actions": ["Admin", "Read", "Write", "List", "Tagging"]
                }
              ]
            }
            """.formatted(ACCESS_KEY, SECRET_KEY);

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

    @BuildStep
    public void disableS3DevServices(BuildProducer<RunTimeConfigurationDefaultBuildItem> runTimeConfig) {
        // Disable the default S3 dev service (LocalStack) provided by quarkus-amazon-s3
        // We want to control the S3 experience explicitly via SeaweedFS
        runTimeConfig.produce(new RunTimeConfigurationDefaultBuildItem("quarkus.s3.devservices.enabled", "false"));
    }

    @BuildStep(onlyIfNot = IsNormal.class)
    public DevServicesResultBuildItem startS3DevService(
            DockerStatusBuildItem dockerStatusBuildItem,
            LaunchModeBuildItem launchMode,
            Optional<ConsoleInstalledBuildItem> consoleInstalled,
            LoggingSetupBuildItem loggingSetup) {

        if (!dockerStatusBuildItem.isContainerRuntimeAvailable()) {
            return null;
        }

        // Only start if S3 client is present on the classpath
        if (!isS3ClientPresent()) {
            LOG.debug("S3Client not found on classpath, skipping SeaweedFS DevService");
            return null;
        }

        try {
            GenericContainer<?> seaweedfs = new GenericContainer<>(DockerImageName.parse(SEAWEEDFS_IMAGE))
                    .withExposedPorts(S3_PORT, MASTER_PORT)
                    .withCopyToContainer(
                            Transferable.of(S3_CONFIG_JSON.getBytes(StandardCharsets.UTF_8)),
                            S3_CONFIG_PATH)
                    .withCommand("server", "-s3", "-filer", "-dir=/data",
                            "-s3.allowEmptyFolder=true", "-s3.config=" + S3_CONFIG_PATH)
                    .waitingFor(Wait.forLogMessage(".*Start Seaweed S3 API Server.*", 1));

            seaweedfs.start();

            String endpoint = "http://" + seaweedfs.getHost() + ":" + seaweedfs.getMappedPort(S3_PORT);

            Map<String, String> config = new HashMap<>();
            config.put("quarkus.s3.endpoint-override", endpoint);
            config.put("quarkus.s3.aws.region", "us-east-1");
            config.put("quarkus.s3.aws.credentials.type", "static");
            config.put("quarkus.s3.aws.credentials.static-provider.access-key-id", ACCESS_KEY);
            config.put("quarkus.s3.aws.credentials.static-provider.secret-access-key", SECRET_KEY);
            config.put("quarkus.s3.path-style-access", "true");

            LOG.info("SeaweedFS (S3) DevService started at " + endpoint);

            return new DevServicesResultBuildItem.RunningDevService("s3-seaweedfs", seaweedfs.getContainerId(), seaweedfs::close, config)
                    .toBuildItem();

        } catch (Exception e) {
            throw new RuntimeException("Failed to start SeaweedFS DevServices", e);
        }
    }

    private boolean isS3ClientPresent() {
        try {
            Class.forName("software.amazon.awssdk.services.s3.S3Client", false, Thread.currentThread().getContextClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}