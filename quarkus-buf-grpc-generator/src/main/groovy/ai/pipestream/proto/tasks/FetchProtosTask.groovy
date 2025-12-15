package ai.pipestream.proto.tasks

import ai.pipestream.proto.ProtoModule
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.gradle.process.ExecResult

import javax.inject.Inject

/**
 * Fetches proto definitions from BSR or Git using buf export.
 *
 * This task cleans and re-exports all configured modules to ensure
 * a consistent state. Each module is exported to its own subdirectory.
 */
abstract class FetchProtosTask extends DefaultTask {

    /**
     * Source mode: 'bsr' or 'git'.
     */
    @Input
    abstract Property<String> getSourceMode()

    /**
     * Output directory for exported protos.
     */
    @OutputDirectory
    abstract DirectoryProperty getExportDir()

    /**
     * The modules to fetch. Marked @Internal because the container
     * itself isn't cacheable, but its contents are read at execution time.
     */
    @Internal
    Iterable<ProtoModule> modules

    @Inject
    protected abstract ExecOperations getExecOperations()

    @TaskAction
    void fetch() {
        def exportDir = getExportDir().get().asFile
        def mode = getSourceMode().get()

        // Clean export directory for consistent state
        if (exportDir.exists()) {
            logger.lifecycle("Cleaning export directory: ${exportDir}")
            exportDir.deleteDir()
        }
        exportDir.mkdirs()

        if (!modules || !modules.iterator().hasNext()) {
            logger.warn("No modules configured, nothing to fetch.")
            return
        }

        modules.each { module ->
            def moduleDir = new File(exportDir, module.name)
            moduleDir.mkdirs()

            if (mode == 'bsr') {
                this.fetchFromBsr(module, moduleDir)
            } else if (mode == 'git') {
                this.fetchFromGit(module, moduleDir)
            } else {
                throw new GradleException("Unknown sourceMode: ${mode}. Use 'bsr' or 'git'.")
            }
        }

        logger.lifecycle("Fetched ${modules.size()} module(s) to ${exportDir}")
    }

    protected void fetchFromBsr(ProtoModule module, File outputDir) {
        def bsr = module.bsr.getOrNull()
        if (!bsr) {
            throw new GradleException("Module '${module.name}' has no BSR path configured. " +
                "Set bsr = 'buf.build/org/module' or use -PprotoSource=git")
        }

        logger.lifecycle("Exporting ${module.name} from BSR: ${bsr}")

        ExecResult result = getExecOperations().exec { spec ->
            // Use bash -lc to ensure buf is in PATH (sdkman, homebrew, etc.)
            spec.commandLine 'bash', '-lc', "buf export ${bsr} --output ${outputDir.absolutePath}"
            spec.ignoreExitValue = true
        }

        if (result.exitValue != 0) {
            throw new GradleException(
                "Failed to export '${module.name}' from BSR: ${bsr}\n" +
                "Exit code: ${result.exitValue}\n" +
                "Ensure 'buf' CLI is installed and you're authenticated to BSR."
            )
        }
    }

    protected void fetchFromGit(ProtoModule module, File outputDir) {
        def gitRepo = module.gitRepo.getOrNull()
        if (!gitRepo) {
            throw new GradleException("Module '${module.name}' has no Git repository configured. " +
                "Set gitRepo = 'https://github.com/org/repo.git' or use -PprotoSource=bsr")
        }

        def gitRef = module.gitRef.get()
        def gitSubdir = module.gitSubdir.get()

        // buf export can handle git URLs directly with ref and subdir
        // Format: <repo>#ref=<ref>,subdir=<subdir>
        def bufGitUrl = "${gitRepo}#ref=${gitRef}"
        if (gitSubdir && gitSubdir != '.') {
            bufGitUrl += ",subdir=${gitSubdir}"
        }

        logger.lifecycle("Exporting ${module.name} from Git: ${gitRepo} (ref=${gitRef}, subdir=${gitSubdir})")

        ExecResult result = getExecOperations().exec { spec ->
            spec.commandLine 'bash', '-lc', "buf export ${bufGitUrl} --output ${outputDir.absolutePath}"
            spec.ignoreExitValue = true
        }

        if (result.exitValue != 0) {
            throw new GradleException(
                "Failed to export '${module.name}' from Git: ${gitRepo}\n" +
                "Exit code: ${result.exitValue}\n" +
                "Ensure 'buf' CLI is installed and you have access to the repository."
            )
        }
    }
}
