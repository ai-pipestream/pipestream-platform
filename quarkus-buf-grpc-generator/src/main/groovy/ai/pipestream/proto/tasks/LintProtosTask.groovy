package ai.pipestream.proto.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.gradle.process.ExecResult

import javax.inject.Inject

/**
 * Runs buf lint on exported proto files to check for style and correctness issues.
 *
 * This task runs after fetchProtos and validates the proto files against
 * buf's lint rules. It can be configured with additional lint arguments.
 */
abstract class LintProtosTask extends DefaultTask {

    /**
     * Directory containing exported proto files.
     */
    @InputDirectory
    abstract DirectoryProperty getProtoDir()

    /**
     * The buf executable (resolved from Maven Central).
     */
    @InputFiles
    abstract ConfigurableFileCollection getBufExecutable()

    /**
     * Additional arguments to pass to buf lint.
     * Example: ['--config', 'buf.yaml']
     */
    @Input
    abstract ListProperty<String> getLintArgs()

    @Inject
    protected abstract ExecOperations getExecOperations()

    LintProtosTask() {
        description = 'Runs buf lint on exported proto files'
        group = 'protobuf'
        getLintArgs().convention([])
    }

    @TaskAction
    void lint() {
        def protoDir = getProtoDir().get().asFile

        if (!protoDir.exists() || protoDir.listFiles()?.length == 0) {
            logger.warn("No proto files found in ${protoDir}. Run fetchProtos first.")
            return
        }

        def bufBinary = resolveBufBinary()
        logger.lifecycle("Running buf lint on: ${protoDir}")

        // Build command: buf lint <dir> [args...]
        def command = [bufBinary.absolutePath, 'lint', protoDir.absolutePath]
        command.addAll(getLintArgs().get())

        def stdout = new ByteArrayOutputStream()
        def stderr = new ByteArrayOutputStream()

        ExecResult result = getExecOperations().exec { spec ->
            spec.commandLine command
            spec.standardOutput = stdout
            spec.errorOutput = stderr
            spec.ignoreExitValue = true
        }

        def output = stdout.toString() + stderr.toString()

        if (result.exitValue != 0) {
            logger.error("buf lint found issues:\n${output}")
            throw new GradleException("buf lint failed with exit code ${result.exitValue}. See output above for details.")
        }

        if (output.trim()) {
            logger.lifecycle(output)
        }
        logger.lifecycle("buf lint passed - no issues found")
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
