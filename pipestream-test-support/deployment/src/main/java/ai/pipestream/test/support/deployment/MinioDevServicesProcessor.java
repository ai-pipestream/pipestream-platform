package ai.pipestream.test.support.deployment;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.testcontainers.containers.MinIOContainer;
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

public class MinioDevServicesProcessor {

    private static final String FEATURE = "pipestream-test-support";

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

    @BuildStep
    public void disableS3DevServices(BuildProducer<RunTimeConfigurationDefaultBuildItem> runTimeConfig) {
        // Disable the default S3 dev service (LocalStack) provided by quarkus-amazon-s3
        runTimeConfig.produce(new RunTimeConfigurationDefaultBuildItem("quarkus.s3.devservices.enabled", "false"));
    }

    @BuildStep(onlyIfNot = IsNormal.class)
    public DevServicesResultBuildItem startMinioDevService(
            DockerStatusBuildItem dockerStatusBuildItem,
            LaunchModeBuildItem launchMode,
            Optional<ConsoleInstalledBuildItem> consoleInstalled,
            LoggingSetupBuildItem loggingSetup) {

        if (!dockerStatusBuildItem.isContainerRuntimeAvailable()) {
            return null;
        }

        try {
            // Using a standard MinIO image
            // We use a specific version to ensure stability, matching common usage
            MinIOContainer minio = new MinIOContainer(DockerImageName.parse("minio/minio:RELEASE.2023-09-04T19-57-37Z"));
            minio.start();

            Map<String, String> config = new HashMap<>();
            config.put("quarkus.s3.endpoint-override", minio.getS3URL());
            config.put("quarkus.s3.aws.region", "us-east-1");
            config.put("quarkus.s3.aws.credentials.type", "static");
            config.put("quarkus.s3.aws.credentials.static-provider.access-key-id", minio.getUserName());
            config.put("quarkus.s3.aws.credentials.static-provider.secret-access-key", minio.getPassword());
            config.put("quarkus.s3.path-style-access", "true");

            return new DevServicesResultBuildItem.RunningDevService("minio", minio.getContainerId(), minio::close, config)
                    .toBuildItem();

        } catch (Exception e) {
            throw new RuntimeException("Failed to start MinIO DevServices", e);
        }
    }
}
