package ai.pipestream.quarkus.djl.serving.deployment;

import com.github.dockerjava.api.model.DeviceRequest;
import io.quarkus.deployment.IsNormal;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.BuildSteps;
import io.quarkus.deployment.builditem.CuratedApplicationShutdownBuildItem;
import io.quarkus.deployment.builditem.DevServicesResultBuildItem;
import io.quarkus.deployment.builditem.DevServicesResultBuildItem.RunningDevService;
import io.quarkus.deployment.builditem.DockerStatusBuildItem;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.LaunchModeBuildItem;
import io.quarkus.deployment.console.ConsoleInstalledBuildItem;
import io.quarkus.deployment.console.StartupLogCompressor;
import io.quarkus.deployment.dev.devservices.DevServicesConfig;
import io.quarkus.deployment.logging.LoggingSetupBuildItem;
import org.jboss.logging.Logger;

import java.io.File;
import java.net.Socket;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@BuildSteps(onlyIfNot = IsNormal.class, onlyIf = DevServicesConfig.Enabled.class)
class DjlServingDevServicesProcessor {
    private static final Logger LOG = Logger.getLogger(DjlServingDevServicesProcessor.class);

    public static final String FEATURE = "pipestream-djl-serving";
    public static final String PROVIDER = "djl-serving";

    private static volatile RunningDevService DEV_SERVICE;
    private static volatile DjlServingBuildTimeConfig.DevServicesConfig CAPTURED_CONFIG;
    private static volatile boolean FIRST = true;

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

    @BuildStep
    void startDjlServingContainer(
            DjlServingBuildTimeConfig buildTimeConfig,
            LaunchModeBuildItem launchMode,
            DockerStatusBuildItem dockerStatusBuildItem,
            Optional<ConsoleInstalledBuildItem> consoleInstalledBuildItem,
            LoggingSetupBuildItem loggingSetupBuildItem,
            CuratedApplicationShutdownBuildItem shutdownBuildItem,
            BuildProducer<DevServicesResultBuildItem> devServicesResultProducer) {

        var devServicesConfig = buildTimeConfig.devservices();

        if (DEV_SERVICE != null) {
            boolean restartRequired = !devServicesConfig.equals(CAPTURED_CONFIG);
            if (!restartRequired) {
                devServicesResultProducer.produce(DEV_SERVICE.toBuildItem());
                return;
            }
            shutdown();
        }

        if (isAlreadyRunning()) {
            LOG.info("DJL Serving is already running on port " + DjlServingContainer.DJL_SERVING_PORT + ". Skipping Dev Service startup.");
            return;
        }

        CAPTURED_CONFIG = devServicesConfig;
        var logCompressor = new StartupLogCompressor(
                (launchMode.isTest() ? "(test) " : "") + "DJL Serving Dev Service Starting:",
                consoleInstalledBuildItem,
                loggingSetupBuildItem);

        try {
            startContainer(dockerStatusBuildItem, devServicesConfig, launchMode.isTest())
                    .ifPresentOrElse(
                            devService -> {
                                DEV_SERVICE = devService;
                                logCompressor.close();
                            },
                            logCompressor::closeAndDumpCaptured);
        } catch (Throwable t) {
            logCompressor.closeAndDumpCaptured();
            throw new RuntimeException(t);
        }

        if (DEV_SERVICE != null) {
            LOG.info("Dev services for DJL Serving started");
            devServicesResultProducer.produce(DEV_SERVICE.toBuildItem());

            if (FIRST) {
                FIRST = false;
                shutdownBuildItem.addCloseTask(() -> {
                    shutdown();
                    FIRST = true;
                    DEV_SERVICE = null;
                    CAPTURED_CONFIG = null;
                }, true);
            }
        }
    }

    private Optional<RunningDevService> startContainer(
            DockerStatusBuildItem dockerStatusBuildItem,
            DjlServingBuildTimeConfig.DevServicesConfig config,
            boolean isTest) {

        if (!config.enabled()) {
            return Optional.empty();
        }

        if (!dockerStatusBuildItem.isContainerRuntimeAvailable()) {
            LOG.warn("Docker is not available, cannot start DJL Serving Dev Service");
            return Optional.empty();
        }

        String variant = resolveVariant(config.variant(), isTest);
        String imageName = config.imageName();

        // If the user didn't override the image name, apply the variant-aware name
        if (DjlServingContainer.DEFAULT_IMAGE.equals(imageName)) {
            imageName = DjlServingContainer.resolveImageName(variant);
        }

        LOG.info("Starting DJL Serving Dev Service with variant: " + variant + " (Image: " + imageName + ")");

        var container = new DjlServingContainer(imageName);
        if (config.shared()) {
            container.withLabel("quarkus-dev-service-djl-serving", "shared");
        }
        
        // Request GPU if we are using the cuda variant
        if ("cuda".equals(variant)) {
            container.withCreateContainerCmdModifier(cmd -> {
                var hostConfig = cmd.getHostConfig();
                if (hostConfig != null) {
                    hostConfig.withDeviceRequests(List.of(
                        new DeviceRequest()
                            .withDriver("nvidia")
                            .withCapabilities(List.of(List.of("gpu")))
                    ));
                }
            });
        }

        container.start();

        String modelName = config.modelName();
        String modelUri = config.modelUri();
        container.registerModel(modelName, modelUri);

        String url = container.getUrl();
        Map<String, String> exposedConfig = new HashMap<>();
        exposedConfig.put("pipestream.djl-serving.url", url);
        exposedConfig.put("pipestream.djl-serving.model-name", modelName);
        exposedConfig.put("embedder.djl-serving.url", url);
        exposedConfig.put("embedder.djl-serving.model-name", modelName);
        exposedConfig.put("quarkus.rest-client.djl-serving.url", url);

        return Optional.of(new RunningDevService(PROVIDER, container.getContainerId(), container::close, exposedConfig));
    }

    private String resolveVariant(Optional<String> configured, boolean isTest) {
        if (configured.isPresent()) {
            return configured.get();
        }
        if (isTest) {
            return "cpu";
        }
        // Auto-detect for dev mode
        String arch = System.getProperty("os.arch", "");
        if (arch.equals("aarch64")) {
            return "aarch64";
        }
        if (new File("/dev/nvidia0").exists()) {
            return "cuda";
        }
        return "cpu";
    }

    private void shutdown() {
        if (DEV_SERVICE != null) {
            try {
                DEV_SERVICE.close();
            } catch (Throwable t) {
                LOG.error("Failed to shut down DJL Serving Dev Service", t);
            }
        }
    }

    private boolean isAlreadyRunning() {
        try (var s = new Socket("localhost", DjlServingContainer.DJL_SERVING_PORT)) {
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
