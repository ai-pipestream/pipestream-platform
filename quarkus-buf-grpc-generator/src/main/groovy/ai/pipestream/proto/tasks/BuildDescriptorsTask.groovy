package ai.pipestream.proto.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.SkipWhenEmpty
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.gradle.process.ExecResult

import javax.inject.Inject

/**
 * Builds protobuf descriptor files using buf build.
 *
 * This task creates a FileDescriptorSet (.desc file) that can be used
 * for reflection, dynamic message handling, or documentation generation.
 */
abstract class BuildDescriptorsTask extends DefaultTask {

    /**
     * Directory containing exported proto files (from fetchProtos).
     * Each subdirectory is a module.
     */
    @InputDirectory
    @SkipWhenEmpty
    abstract DirectoryProperty getExportDir()

    /**
     * Output file for the descriptor set.
     */
    @OutputFile
    abstract RegularFileProperty getDescriptorPath()

    @Inject
    protected abstract ExecOperations getExecOperations()

    @TaskAction
    void build() {
        def exportDir = getExportDir().get().asFile
        def descriptorFile = getDescriptorPath().get().asFile

        // Ensure output directory exists
        descriptorFile.parentFile.mkdirs()

        if (!exportDir.exists() || exportDir.listFiles()?.length == 0) {
            logger.warn("No exported protos found in ${exportDir}, skipping descriptor build.")
            return
        }

        // Collect all module directories
        def moduleDirs = []
        exportDir.eachDir { moduleDir ->
            moduleDirs << moduleDir
        }

        if (moduleDirs.isEmpty()) {
            logger.warn("No module directories found in ${exportDir}, skipping descriptor build.")
            return
        }

        logger.lifecycle("Building descriptors for ${moduleDirs.size()} module(s)")

        // For each module, we build descriptors and merge them
        // If there's only one module, we can build directly
        if (moduleDirs.size() == 1) {
            this.buildDescriptor(moduleDirs[0], descriptorFile)
        } else {
            // Multiple modules - build each and merge
            def tempDescriptors = []
            moduleDirs.eachWithIndex { moduleDir, idx ->
                def tempFile = new File(descriptorFile.parentFile, "temp_${idx}.desc")
                this.buildDescriptor(moduleDir, tempFile)
                tempDescriptors << tempFile
            }

            // Merge all descriptors into one
            // For simplicity, we'll just use the last one for now
            // A proper implementation would merge FileDescriptorSets
            // For now, build from the first module that has protos
            if (tempDescriptors) {
                tempDescriptors[0].renameTo(descriptorFile)
                tempDescriptors.drop(1).each { it.delete() }
            }
        }

        logger.lifecycle("Built descriptor file: ${descriptorFile}")
    }

    protected void buildDescriptor(File moduleDir, File outputFile) {
        logger.lifecycle("Building descriptor for module: ${moduleDir.name}")

        ExecResult result = getExecOperations().exec { spec ->
            // Use bash -lc to ensure buf is in PATH
            // buf build outputs a Buf Image which is compatible with FileDescriptorSet
            spec.commandLine 'bash', '-lc',
                "buf build ${moduleDir.absolutePath} -o ${outputFile.absolutePath}"
            spec.ignoreExitValue = true
        }

        if (result.exitValue != 0) {
            throw new GradleException(
                "buf build failed for module '${moduleDir.name}'\n" +
                "Exit code: ${result.exitValue}\n" +
                "Check that proto files are valid."
            )
        }
    }
}
