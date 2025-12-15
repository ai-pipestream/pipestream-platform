package ai.pipestream.proto

import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property

/**
 * Extension for configuring the Pipestream Proto Toolchain plugin.
 *
 * Example usage:
 * <pre>
 * pipestreamProtos {
 *     sourceMode = 'bsr'  // or 'git'
 *     quarkusGrpcVersion = '3.30.3'
 *     generateDescriptors = true
 *
 *     modules {
 *         register("intake") {
 *             bsr = "buf.build/pipestreamai/intake"
 *             gitRepo = "https://github.com/ai-pipestream/pipestream-protos.git"
 *             gitSubdir = "intake"
 *         }
 *     }
 *
 *     // Optional: extra arguments for buf generate
 *     bufGenerateArgs = ['--exclude-path', 'google/']
 *
 *     // Optional: additional buf plugins
 *     extraPlugins {
 *         register("doc") {
 *             plugin = "buf.build/community/pseudomuto-protoc-gen-doc"
 *             out = "docs"
 *             opt = ["markdown,docs.md"]
 *         }
 *     }
 * }
 * </pre>
 */
abstract class ProtoExtension {

    private final Project project
    private final NamedDomainObjectContainer<ProtoModule> modules
    private final NamedDomainObjectContainer<BufPlugin> extraPlugins

    ProtoExtension(Project project) {
        this.project = project
        this.modules = project.objects.domainObjectContainer(ProtoModule)
        this.extraPlugins = project.objects.domainObjectContainer(BufPlugin)

        // Set conventions (defaults)
        getSourceMode().convention(
            project.providers.gradleProperty('protoSource').orElse('bsr')
        )
        getQuarkusGrpcVersion().convention('3.30.3')
        getGenerateMutiny().convention(true)
        getGenerateGrpc().convention(true)
        getGenerateDescriptors().convention(true)
        getDescriptorPath().convention(
            project.layout.buildDirectory.file("descriptors/proto.desc")
        )
        getOutputDir().convention(
            project.layout.buildDirectory.dir("generated/source/proto/main/java")
        )
        getExportDir().convention(
            project.layout.buildDirectory.dir("protos/export")
        )
        getBufGenerateArgs().convention([])
    }

    /**
     * Source mode: 'bsr' (default) or 'git'.
     * Can be overridden via -PprotoSource=git
     */
    abstract Property<String> getSourceMode()

    /**
     * Quarkus gRPC version for the Mutiny generator plugin.
     */
    abstract Property<String> getQuarkusGrpcVersion()

    /**
     * Whether to generate Quarkus Mutiny stubs. Default: true.
     */
    abstract Property<Boolean> getGenerateMutiny()

    /**
     * Whether to generate standard gRPC stubs. Default: true.
     */
    abstract Property<Boolean> getGenerateGrpc()

    /**
     * Whether to generate protobuf descriptor files. Default: true.
     */
    abstract Property<Boolean> getGenerateDescriptors()

    /**
     * Path for the generated descriptor file.
     * Default: build/descriptors/proto.desc
     */
    abstract RegularFileProperty getDescriptorPath()

    /**
     * Output directory for generated Java sources.
     */
    abstract DirectoryProperty getOutputDir()

    /**
     * Directory where exported proto files are stored.
     */
    abstract DirectoryProperty getExportDir()

    /**
     * Extra arguments to pass to buf generate command.
     * Example: ['--exclude-path', 'google/']
     */
    abstract ListProperty<String> getBufGenerateArgs()

    /**
     * Container of proto modules to fetch and generate.
     */
    NamedDomainObjectContainer<ProtoModule> getModules() {
        return modules
    }

    /**
     * DSL support for modules { } block.
     */
    void modules(Closure closure) {
        modules.configure(closure)
    }

    /**
     * Container of extra buf plugins to include in generation.
     */
    NamedDomainObjectContainer<BufPlugin> getExtraPlugins() {
        return extraPlugins
    }

    /**
     * DSL support for extraPlugins { } block.
     */
    void extraPlugins(Closure closure) {
        extraPlugins.configure(closure)
    }
}
