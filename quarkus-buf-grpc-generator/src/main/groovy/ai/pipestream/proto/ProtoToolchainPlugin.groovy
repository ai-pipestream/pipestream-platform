package ai.pipestream.proto

import ai.pipestream.proto.tasks.BuildDescriptorsTask
import ai.pipestream.proto.tasks.CheckBreakingTask
import ai.pipestream.proto.tasks.FetchProtosTask
import ai.pipestream.proto.tasks.FormatProtosTask
import ai.pipestream.proto.tasks.GenerateProtosTask
import ai.pipestream.proto.tasks.LintProtosTask
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

        // Create binary tool configurations (downloads from Maven Central)
        BinaryResolver.createAllConfigurations(project)

        // Define shared directories
        def buildDir = project.layout.buildDirectory
        def exportDir = extension.exportDir
        def outputDir = extension.outputDir
        def pluginDir = buildDir.dir("tmp/protoc-plugins")
        def bufGenYaml = buildDir.file("buf.gen.yaml")
        def descriptorPath = extension.descriptorPath

        // Get configurations for tasks
        def bufConfig = project.configurations.getByName(BinaryResolver.BUF_BINARY_CONFIGURATION_NAME)
        def protocConfig = project.configurations.getByName(BinaryResolver.PROTOC_CONFIGURATION_NAME)
        def grpcJavaConfig = project.configurations.getByName(BinaryResolver.GRPC_JAVA_CONFIGURATION_NAME)

        // Register fetchProtos task
        def fetchTask = project.tasks.register("fetchProtos", FetchProtosTask) { task ->
            task.group = "protobuf"
            task.description = "Fetches proto definitions from BSR or Git"

            task.sourceMode.set(extension.sourceMode)
            task.exportDir.set(exportDir)
            task.modules = extension.modules
            task.bufExecutable.setFrom(bufConfig)
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
            // Pass custom paths if specified by user (takes precedence over Maven download)
            task.customProtocPath.set(extension.protocPath)
            task.customGrpcJavaPath.set(extension.grpcJavaPluginPath)
            // Pass protoc and grpc-java configurations for local generation (used if custom paths not set)
            task.protocExecutable.setFrom(protocConfig)
            task.grpcJavaExecutable.setFrom(grpcJavaConfig)
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
            task.bufExecutable.setFrom(bufConfig)
        }

        // Register buildDescriptors task
        def buildDescriptorsTask = project.tasks.register("buildDescriptors", BuildDescriptorsTask) { task ->
            task.group = "protobuf"
            task.description = "Builds protobuf descriptor files"
            task.dependsOn(fetchTask)

            task.exportDir.set(exportDir)
            task.descriptorPath.set(descriptorPath)
            task.bufExecutable.setFrom(bufConfig)

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

        // Register lintProtos task
        project.tasks.register("lintProtos", LintProtosTask) { task ->
            task.group = "protobuf"
            task.description = "Runs buf lint on exported proto files"
            task.dependsOn(fetchTask)

            task.protoDir.set(exportDir)
            task.bufExecutable.setFrom(bufConfig)
            task.lintArgs.set(extension.lintArgs)
        }

        // Register checkBreaking task
        project.tasks.register("checkBreaking", CheckBreakingTask) { task ->
            task.group = "protobuf"
            task.description = "Checks for breaking changes in proto files"
            task.dependsOn(fetchTask)

            task.protoDir.set(exportDir)
            task.bufExecutable.setFrom(bufConfig)
            task.againstRef.set(extension.breakingAgainstRef)
            task.breakingArgs.set(extension.breakingArgs)
        }

        // Register formatProtos task
        project.tasks.register("formatProtos", FormatProtosTask) { task ->
            task.group = "protobuf"
            task.description = "Formats proto files using buf format"
            task.dependsOn(fetchTask)

            task.protoDir.set(exportDir)
            task.bufExecutable.setFrom(bufConfig)
            task.checkOnly.set(false)
            task.showDiff.set(true)
            task.formatArgs.set(extension.formatArgs)
        }

        // Register checkFormatProtos task (format check only, doesn't modify files)
        project.tasks.register("checkFormatProtos", FormatProtosTask) { task ->
            task.group = "protobuf"
            task.description = "Checks proto file formatting without making changes"
            task.dependsOn(fetchTask)

            task.protoDir.set(exportDir)
            task.bufExecutable.setFrom(bufConfig)
            task.checkOnly.set(true)
            task.showDiff.set(true)
            task.formatArgs.set(extension.formatArgs)
        }

        // Configure binary dependencies after evaluation (when versions are known)
        project.afterEvaluate {
            // Always need buf CLI
            BinaryResolver.configureBufDependency(project, extension.bufVersion.get())

            // Only download protoc from Maven if user hasn't specified a custom path
            if (!extension.protocPath.isPresent()) {
                BinaryResolver.configureProtocDependency(project, extension.protocVersion.get())
            } else {
                project.logger.lifecycle("Using custom protoc path: ${extension.protocPath.get()}")
            }

            // Only download grpc-java from Maven if user hasn't specified a custom path
            if (!extension.grpcJavaPluginPath.isPresent()) {
                BinaryResolver.configureGrpcJavaDependency(project, extension.grpcJavaVersion.get())
            } else {
                project.logger.lifecycle("Using custom grpc-java plugin path: ${extension.grpcJavaPluginPath.get()}")
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
