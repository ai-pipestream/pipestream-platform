package ai.pipestream.quarkus.devservices.runtime;

import io.quarkus.runtime.annotations.Recorder;

@Recorder
public class PipelineDevServicesRecorder {

    public void setConfig(String composeFiles, String projectName, boolean enabled, boolean startServices, boolean stopServices, boolean reuseProjectForTests) {
        System.setProperty("pipeline.devservices.files", composeFiles);
        System.setProperty("pipeline.devservices.project-name", projectName);
        System.setProperty("pipeline.devservices.enabled", String.valueOf(enabled));
        System.setProperty("pipeline.devservices.start-services", String.valueOf(startServices));
        System.setProperty("pipeline.devservices.stop-services", String.valueOf(stopServices));
        System.setProperty("pipeline.devservices.reuse-project-for-tests", String.valueOf(reuseProjectForTests));
    }
}
