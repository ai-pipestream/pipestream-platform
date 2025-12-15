package ai.pipestream.proto

import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional

/**
 * Configuration for an extra buf plugin to include in code generation.
 *
 * Example usage:
 * <pre>
 * extraPlugins {
 *     register("doc") {
 *         plugin = "buf.build/community/pseudomuto-protoc-gen-doc"
 *         out = "docs"
 *         opt = ["markdown,docs.md"]
 *     }
 *     register("validate") {
 *         plugin = "buf.build/bufbuild/validate-go"
 *         out = "gen/validate"
 *     }
 * }
 * </pre>
 */
abstract class BufPlugin {

    private final String name

    BufPlugin(String name) {
        this.name = name
    }

    /**
     * The name of this plugin configuration (used as identifier).
     */
    String getName() {
        return name
    }

    /**
     * The buf plugin reference.
     * Can be a remote plugin (e.g., "buf.build/protocolbuffers/java")
     * or a local plugin path.
     */
    @Input
    abstract Property<String> getPlugin()

    /**
     * Output directory for this plugin's generated files.
     * Can be relative to project or absolute.
     */
    @Input
    abstract Property<String> getOut()

    /**
     * Options to pass to the plugin.
     */
    @Input
    @Optional
    abstract ListProperty<String> getOpt()

    // Convenience setters for DSL
    void plugin(String value) {
        getPlugin().set(value)
    }

    void out(String value) {
        getOut().set(value)
    }

    void opt(List<String> value) {
        getOpt().set(value)
    }
}
