package ai.pipestream.proto

import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional

/**
 * Configuration for a single proto module to fetch and generate.
 */
abstract class ProtoModule {
    private final String name

    ProtoModule(String name) {
        this.name = name
        // Set sensible defaults
        getGitRef().convention("main")
        getGitSubdir().convention(".")
    }

    /**
     * The module name (used as directory name for exports).
     */
    String getName() {
        return name
    }

    /**
     * BSR module path (e.g., "buf.build/pipestreamai/intake").
     */
    @Input
    @Optional
    abstract Property<String> getBsr()

    /**
     * Git repository URL for fallback (e.g., "https://github.com/ai-pipestream/pipestream-protos.git").
     */
    @Input
    @Optional
    abstract Property<String> getGitRepo()

    /**
     * Git ref to checkout (branch, tag, or commit). Defaults to "main".
     */
    @Input
    abstract Property<String> getGitRef()

    /**
     * Subdirectory within the git repo containing the proto files. Defaults to ".".
     */
    @Input
    abstract Property<String> getGitSubdir()

    // Convenience setters for DSL
    void bsr(String value) { getBsr().set(value) }
    void gitRepo(String value) { getGitRepo().set(value) }
    void gitRef(String value) { getGitRef().set(value) }
    void gitSubdir(String value) { getGitSubdir().set(value) }
}
