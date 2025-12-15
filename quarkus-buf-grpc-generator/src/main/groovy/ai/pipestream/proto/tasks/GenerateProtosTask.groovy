package ai.pipestream.proto.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.SkipWhenEmpty
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.gradle.process.ExecResult

import javax.inject.Inject

/**
 * Generates Java code from proto files using buf generate.
 *
 * This task iterates over all exported module directories and runs
 * buf generate with the prepared buf.gen.yaml configuration.
 */
abstract class GenerateProtosTask extends DefaultTask {

    /**
     * Directory containing exported proto files (from fetchProtos).
     * Each subdirectory is a module.
     */
    @InputDirectory
    @SkipWhenEmpty
    abstract DirectoryProperty getExportDir()

    /**
     * The buf.gen.yaml configuration file (from prepareGenerators).
     */
    @InputFile
    abstract RegularFileProperty getBufGenYaml()

    /**
     * Output directory for generated Java sources.
     */
    @OutputDirectory
    abstract DirectoryProperty getOutputDir()

    /**
     * Extra arguments to pass to buf generate command.
     * Example: ['--exclude-path', 'google/']
     */
    @Input
    @org.gradle.api.tasks.Optional
    abstract ListProperty<String> getBufGenerateArgs()

    @Inject
    protected abstract ExecOperations getExecOperations()

    @TaskAction
    void generate() {
        def exportDir = getExportDir().get().asFile
        def bufGenYaml = getBufGenYaml().get().asFile
        def outputDir = getOutputDir().get().asFile
        def extraArgs = getBufGenerateArgs().getOrElse([])

        // Ensure output directory exists
        outputDir.mkdirs()

        if (!exportDir.exists() || exportDir.listFiles()?.length == 0) {
            logger.warn("No exported protos found in ${exportDir}, skipping generation.")
            return
        }

        // Count modules for reporting
        def moduleCount = 0
        def failedModules = []

        exportDir.eachDir { moduleDir ->
            moduleCount++
            logger.lifecycle("Generating code for module: ${moduleDir.name}")

            try {
                this.generateModule(moduleDir, bufGenYaml, extraArgs)
            } catch (Exception e) {
                logger.error("Failed to generate code for module: ${moduleDir.name}", e)
                failedModules << moduleDir.name
            }
        }

        if (failedModules) {
            throw new GradleException(
                "Code generation failed for ${failedModules.size()} module(s): ${failedModules.join(', ')}"
            )
        }

        logger.lifecycle("Generated code for ${moduleCount} module(s) to ${outputDir}")
    }

    protected void generateModule(File moduleDir, File bufGenYaml, List<String> extraArgs) {
        // Build the command with extra arguments
        def command = "buf generate ${moduleDir.absolutePath} --template ${bufGenYaml.absolutePath}"
        if (extraArgs && !extraArgs.isEmpty()) {
            command += " " + extraArgs.join(" ")
        }

        ExecResult result = getExecOperations().exec { spec ->
            // Use bash -lc to ensure buf is in PATH
            spec.commandLine 'bash', '-lc', command
            spec.ignoreExitValue = true
        }

        if (result.exitValue != 0) {
            throw new GradleException(
                "buf generate failed for module '${moduleDir.name}'\n" +
                "Exit code: ${result.exitValue}\n" +
                "Check that proto files are valid and all dependencies are exported."
            )
        }
    }
}
