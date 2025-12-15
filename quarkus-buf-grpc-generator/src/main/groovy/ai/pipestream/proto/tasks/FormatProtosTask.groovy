package ai.pipestream.proto.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.gradle.process.ExecResult

import javax.inject.Inject

/**
 * Formats proto files using buf format.
 *
 * This task can either format files in-place or write formatted output
 * to a separate directory. Use checkOnly mode to verify formatting without
 * making changes.
 */
abstract class FormatProtosTask extends DefaultTask {

    /**
     * Directory containing proto files to format.
     */
    @OutputDirectory
    abstract DirectoryProperty getProtoDir()

    /**
     * The buf executable (resolved from Maven Central).
     */
    @InputFiles
    abstract ConfigurableFileCollection getBufExecutable()

    /**
     * If true, only check formatting without making changes.
     * The task will fail if files are not formatted correctly.
     */
    @Input
    abstract Property<Boolean> getCheckOnly()

    /**
     * If true, show a diff of what would be changed.
     */
    @Input
    abstract Property<Boolean> getShowDiff()

    /**
     * Additional arguments to pass to buf format.
     */
    @Input
    abstract ListProperty<String> getFormatArgs()

    @Inject
    protected abstract ExecOperations getExecOperations()

    FormatProtosTask() {
        description = 'Formats proto files using buf format'
        group = 'protobuf'
        getCheckOnly().convention(false)
        getShowDiff().convention(false)
        getFormatArgs().convention([])
    }

    @TaskAction
    void format() {
        def protoDir = getProtoDir().get().asFile
        def checkOnly = getCheckOnly().get()
        def showDiff = getShowDiff().get()

        if (!protoDir.exists() || protoDir.listFiles()?.length == 0) {
            logger.warn("No proto files found in ${protoDir}. Run fetchProtos first.")
            return
        }

        def bufBinary = resolveBufBinary()

        if (checkOnly) {
            logger.lifecycle("Checking proto file formatting in: ${protoDir}")
        } else {
            logger.lifecycle("Formatting proto files in: ${protoDir}")
        }

        // Build command: buf format <dir> [--write] [--diff] [args...]
        def command = [bufBinary.absolutePath, 'format', protoDir.absolutePath]

        if (!checkOnly) {
            command.add('--write')
        }

        if (showDiff || checkOnly) {
            command.add('--diff')
        }

        command.addAll(getFormatArgs().get())

        def stdout = new ByteArrayOutputStream()
        def stderr = new ByteArrayOutputStream()

        ExecResult result = getExecOperations().exec { spec ->
            spec.commandLine command
            spec.standardOutput = stdout
            spec.errorOutput = stderr
            spec.ignoreExitValue = true
        }

        def output = stdout.toString()
        def errors = stderr.toString()

        if (result.exitValue != 0) {
            if (checkOnly && output.trim()) {
                logger.error("Proto files are not formatted correctly:\n${output}")
                throw new GradleException("buf format check failed. Run './gradlew formatProtos' to fix formatting.")
            }
            logger.error("buf format failed:\n${errors}")
            throw new GradleException("buf format failed with exit code ${result.exitValue}")
        }

        if (output.trim()) {
            if (checkOnly) {
                // If there's output in check mode, formatting issues exist
                logger.error("Proto files are not formatted correctly:\n${output}")
                throw new GradleException("buf format check failed. Run './gradlew formatProtos' to fix formatting.")
            } else if (showDiff) {
                logger.lifecycle("Changes made:\n${output}")
            }
        }

        if (checkOnly) {
            logger.lifecycle("All proto files are formatted correctly")
        } else {
            logger.lifecycle("Proto files formatted successfully")
        }
    }

    /**
     * Resolves the buf executable file, ensuring it's executable.
     */
    protected File resolveBufBinary() {
        def executable = getBufExecutable().singleFile
        if (!executable.canExecute()) {
            executable.setExecutable(true)
        }
        return executable
    }
}
