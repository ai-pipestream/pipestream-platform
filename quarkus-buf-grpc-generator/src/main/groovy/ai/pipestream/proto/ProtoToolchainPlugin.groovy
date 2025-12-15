package ai.pipestream.proto

import ai.pipestream.proto.tasks.BuildDescriptorsTask
import ai.pipestream.proto.tasks.FetchProtosTask
import ai.pipestream.proto.tasks.GenerateProtosTask
import ai.pipestream.proto.tasks.PrepareGeneratorsTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.tasks.SourceSetContainer

/**
 * Pipestream Proto Toolchain Plugin.
 *
 * This plugin provides a streamlined workflow for fetching proto definitions
 * from BSR or Git and generating Java/gRPC/Mutiny code.
 *
 * Usage:
 * <pre>
 * plugins {
 *     id 'ai.pipestream.proto-toolchain'
 *     id 'java'
 * }
 *
 * pipestreamProtos {
 *     modules {
 *         register("intake") {
 *             bsr = "buf.build/pipestreamai/intake"
 *         }
 *     }
 *
 *     // Optional: extra buf plugins
 *     extraPlugins {
 *         register("doc") {
 *             plugin = "buf.build/community/pseudomuto-protoc-gen-doc"
 *             out = "docs"
 *             opt = ["markdown,docs.md"]
 *         }
 *     }
 *
 *     // Optional: extra buf generate arguments
 *     bufGenerateArgs = ['--exclude-path', 'google/']
 * }
 * </pre>
 */
class ProtoToolchainPlugin implements Plugin<Project> {

    @Override
    void apply(Project project) {
        // Create extension
        def extension = project.extensions.create("pipestreamProtos", ProtoExtension, project)

        // Define shared directories
        def buildDir = project.layout.buildDirectory
        def exportDir = extension.exportDir
        def outputDir = extension.outputDir
        def pluginDir = buildDir.dir("tmp/protoc-plugins")
        def bufGenYaml = buildDir.file("buf.gen.yaml")
        def descriptorPath = extension.descriptorPath

        // Register fetchProtos task
        def fetchTask = project.tasks.register("fetchProtos", FetchProtosTask) { task ->
            task.group = "protobuf"
            task.description = "Fetches proto definitions from BSR or Git"

            task.sourceMode.set(extension.sourceMode)
            task.exportDir.set(exportDir)
            task.modules = extension.modules
        }

        // Register prepareGenerators task
        def prepareTask = project.tasks.register("prepareGenerators", PrepareGeneratorsTask) { task ->
            task.group = "protobuf"
            task.description = "Prepares code generator plugins and buf.gen.yaml"
            task.dependsOn(fetchTask)

            task.quarkusGrpcVersion.set(extension.quarkusGrpcVersion)
            task.generateMutiny.set(extension.generateMutiny)
            task.generateGrpc.set(extension.generateGrpc)
            task.outputDir.set(outputDir)
            task.pluginDir.set(pluginDir)
            task.bufGenYaml.set(bufGenYaml)
            task.extraPlugins = extension.extraPlugins
        }

        // Register generateProtos task
        def generateTask = project.tasks.register("generateProtos", GenerateProtosTask) { task ->
            task.group = "protobuf"
            task.description = "Generates Java and gRPC code using Buf"
            task.dependsOn(prepareTask)

            task.exportDir.set(exportDir)
            task.bufGenYaml.set(bufGenYaml)
            task.outputDir.set(outputDir)
            task.bufGenerateArgs.set(extension.bufGenerateArgs)
        }

        // Register buildDescriptors task
        def buildDescriptorsTask = project.tasks.register("buildDescriptors", BuildDescriptorsTask) { task ->
            task.group = "protobuf"
            task.description = "Builds protobuf descriptor files"
            task.dependsOn(fetchTask)

            task.exportDir.set(exportDir)
            task.descriptorPath.set(descriptorPath)

            // Only run if generateDescriptors is enabled
            task.onlyIf {
                extension.generateDescriptors.get()
            }
        }

        // Register cleanProtos task
        project.tasks.register("cleanProtos") { task ->
            task.group = "protobuf"
            task.description = "Cleans all generated proto artifacts"
            task.doLast {
                project.delete(exportDir)
                project.delete(outputDir)
                project.delete(pluginDir)
                project.delete(bufGenYaml)
                project.delete(descriptorPath)
                project.logger.lifecycle("Cleaned proto artifacts")
            }
        }

        // Wire into Java compilation if Java plugin is applied
        project.plugins.withType(JavaPlugin) {
            // Add generated sources to main source set
            project.extensions.getByType(SourceSetContainer).named("main") { sourceSet ->
                sourceSet.java.srcDir(outputDir)
            }

            // Ensure generateProtos runs before compileJava
            project.tasks.named("compileJava").configure { task ->
                task.dependsOn(generateTask)
                // Also build descriptors if enabled
                task.dependsOn(buildDescriptorsTask)
            }

            // Also wire to sourcesJar if it exists
            project.afterEvaluate {
                project.tasks.findByName("sourcesJar")?.dependsOn(generateTask)
            }
        }
    }
}
