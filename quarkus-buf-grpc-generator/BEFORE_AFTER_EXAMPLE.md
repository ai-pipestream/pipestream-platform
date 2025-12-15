# Before & After: Proto Toolchain Plugin

This document shows how the `ai.pipestream.proto-toolchain` plugin simplifies protobuf configuration using `connector-intake-service` as a real-world example.

---

## BEFORE: Current `build.gradle` (70+ lines of scaffolding)

```groovy
plugins {
    alias(libs.plugins.java)
    alias(libs.plugins.quarkus)
    // ... other plugins
}

def pipestreamBomVersion = findProperty('pipestreamBomVersion') ?: '0.7.4'

// ============================================================
// PROTOBUF SCAFFOLDING START (Lines 26-94 = 68 lines)
// ============================================================

// 1. Define the export path
def bufExportDir = layout.buildDirectory.dir("generated/buf-protos")

// 2. The Sync Tasks - ONE PER MODULE
tasks.register('syncIntakeProtos', Exec) {
    group = 'protobuf'
    description = 'Exports intake proto files from Buf Registry'
    // Use bash -lc so the user's PATH (sdkman, homebrew, etc.) is honored for the buf CLI
    commandLine 'bash', '-lc', "buf export buf.build/pipestreamai/intake --output ${bufExportDir.get().asFile.path}"
    outputs.dir(bufExportDir)
    doFirst {
        bufExportDir.get().asFile.mkdirs()
    }
}

tasks.register('syncAdminProtos', Exec) {
    group = 'protobuf'
    description = 'Exports admin proto files from Buf Registry'
    commandLine 'bash', '-lc', "buf export buf.build/pipestreamai/admin --output ${bufExportDir.get().asFile.path}"
    outputs.dir(bufExportDir)
    doFirst {
        bufExportDir.get().asFile.mkdirs()
    }
}

tasks.register('syncRepoProtos', Exec) {
    group = 'protobuf'
    description = 'Exports repo proto files from Buf Registry'
    commandLine 'bash', '-lc', "buf export buf.build/pipestreamai/repo --output ${bufExportDir.get().asFile.path}"
    outputs.dir(bufExportDir)
    doFirst {
        bufExportDir.get().asFile.mkdirs()
    }
}

tasks.register('syncProtos') {
    group = 'protobuf'
    description = 'Syncs all proto definitions from Buf Registry'
    dependsOn 'syncIntakeProtos', 'syncAdminProtos', 'syncRepoProtos'
}

// 3. Configure Quarkus to find the protos
quarkus {
    quarkusBuildProperties.put("quarkus.grpc.codegen.proto-directory", bufExportDir.get().asFile.path)
    quarkusBuildProperties.put("quarkus.grpc.codegen.enabled", "true")
}

// 4. IDE visibility for generated sources
sourceSets {
    main {
        java {
            srcDir layout.buildDirectory.dir("classes/java/quarkus-generated-sources/grpc")
        }
    }
}

// 5. Hook into the correct Quarkus tasks
tasks.named('quarkusGenerateCode').configure { dependsOn 'syncProtos' }
tasks.named('quarkusGenerateCodeDev').configure { dependsOn 'syncProtos' }
tasks.named('quarkusGenerateCodeTests').configure { dependsOn 'syncProtos' }
tasks.named('compileJava').configure { dependsOn 'quarkusGenerateCode' }

// `sourcesJar` task is only created once `java { withSourcesJar() }` is applied.
// Configure it safely after project evaluation.
afterEvaluate {
    def sourcesJarTask = tasks.findByName('sourcesJar')
    if (sourcesJarTask != null) {
        sourcesJarTask.dependsOn(tasks.named('quarkusGenerateCode'))
    }
}

// ============================================================
// PROTOBUF SCAFFOLDING END
// ============================================================

dependencies {
    // ... dependencies
}
```

### Problems with the Current Approach

1. **Boilerplate explosion**: Every new proto module requires a new `syncXxxProtos` task
2. **Fragile task wiring**: Missing a `dependsOn` breaks the build silently
3. **No Git fallback**: BSR-only; restricted environments can't build
4. **Shell dependency**: Assumes `buf` is in PATH (works locally, fails in some CI)
5. **Copy-paste errors**: Each project duplicates this pattern differently
6. **Quarkus coupling**: Relies on Quarkus codegen internals that may change

---

## AFTER: With `ai.pipestream.proto-toolchain` Plugin

```groovy
plugins {
    alias(libs.plugins.java)
    alias(libs.plugins.quarkus)
    id 'ai.pipestream.proto-toolchain' version '1.0.0'
    // ... other plugins
}

def pipestreamBomVersion = findProperty('pipestreamBomVersion') ?: '0.7.4'

// ============================================================
// PROTOBUF CONFIGURATION: 15 LINES TOTAL
// ============================================================

pipestreamProtos {
    // Switch between BSR (default) and Git via: -PprotoSource=git
    sourceMode = providers.gradleProperty('protoSource').orElse('bsr')

    modules {
        register("intake") {
            bsr = "buf.build/pipestreamai/intake"
            gitRepo = "https://github.com/ai-pipestream/pipestream-protos.git"
            gitRef = "main"
            gitSubdir = "intake"
        }
        register("admin") {
            bsr = "buf.build/pipestreamai/admin"
            gitRepo = "https://github.com/ai-pipestream/pipestream-protos.git"
            gitRef = "main"
            gitSubdir = "admin"
        }
        register("repo") {
            bsr = "buf.build/pipestreamai/repo"
            gitRepo = "https://github.com/ai-pipestream/pipestream-protos.git"
            gitRef = "main"
            gitSubdir = "repo"
        }
    }
}

// ============================================================
// THAT'S IT. NO TASK WIRING. NO SOURCESET CONFIG. JUST DECLARE.
// ============================================================

dependencies {
    // ... dependencies (no change)
}
```

---

## Side-by-Side Comparison

| Aspect | Before | After |
|--------|--------|-------|
| **Lines of config** | 68+ | 15 |
| **Add new module** | +10 lines (copy task) | +5 lines (register block) |
| **Git fallback** | Not supported | Built-in (`-PprotoSource=git`) |
| **Buf CLI handling** | Manual PATH setup | Hermetic (downloaded from Maven) |
| **Protoc handling** | Depends on Quarkus | Downloaded from Maven |
| **Task wiring** | Manual, error-prone | Automatic |
| **IDE support** | Manual sourceSet | Automatic |
| **Data privacy** | Quarkus may use remote | 100% local generation |

---

## What the Plugin Does Automatically

### Phase 1: Resolve
```
./gradlew fetchProtos
```
- Iterates over registered modules
- Downloads `buf` CLI from Maven Central (no PATH required)
- Runs `buf export <bsr-or-git> --output build/protos/export/<module>`
- Handles authentication (BSR tokens, Git SSH/HTTPS)

### Phase 2: Configure
```
./gradlew prepareGenerators
```
- Downloads `protoc` from Maven Central
- Downloads `protoc-gen-grpc-java` from Maven Central
- Resolves `quarkus-grpc-protoc-plugin.jar` from Maven
- Generates `protoc-gen-mutiny` wrapper script
- Generates `buf.gen.yaml` (v2 format) with **LOCAL plugins only**

### Phase 3: Generate
```
./gradlew generateProtos
```
- Runs `buf generate` with:
  - **Local plugin:** `protoc_builtin: java` (uses downloaded protoc)
  - **Local plugin:** `protoc-gen-grpc-java` (uses downloaded binary)
  - **Local plugin:** `protoc-gen-mutiny` (uses wrapper script)
- **No proto files are uploaded to any server**
- Adds output directory to main sourceSet
- Wires into `compileJava` automatically

### Phase 4: Descriptors (Optional)
```
./gradlew buildDescriptors
```
- Builds `proto.desc` FileDescriptorSet for reflection/dynamic use

---

## Migration Path

1. Add the plugin to `build.gradle`:
   ```groovy
   id 'ai.pipestream.proto-toolchain' version '1.0.0'
   ```

2. Add the `pipestreamProtos` block with your modules

3. **Delete** all of these:
   - `bufExportDir` variable
   - All `syncXxxProtos` tasks
   - `syncProtos` umbrella task
   - `quarkus { quarkusBuildProperties... }` proto config
   - `sourceSets { main { java { srcDir... }}}` for generated sources
   - All `tasks.named(...).configure { dependsOn... }` wiring
   - `afterEvaluate` block for sourcesJar

4. Run `./gradlew build` - everything just works

---

## Restricted Environment Support

For clients who cannot access `buf.build`:

```bash
# Build using Git source instead of BSR
./gradlew build -PprotoSource=git
```

The plugin will:
1. Clone the specified `gitRepo` at `gitRef`
2. Sparse checkout only `gitSubdir` if specified
3. Run `buf export` on the local clone
4. Continue with generation as normal

**Zero code changes required** - just a CLI flag.

---

## Air-Gapped Environment Support

For environments without internet access:

```groovy
pipestreamProtos {
    // Use pre-installed binaries instead of Maven download
    protocPath = '/opt/protobuf/bin/protoc'
    grpcJavaPluginPath = '/opt/grpc/protoc-gen-grpc-java'

    modules {
        // Use Git with local mirror
        register("intake") {
            gitRepo = "git@internal-git:pipestream/protos.git"
            gitRef = "main"
            gitSubdir = "intake"
        }
    }
}
```

---

## Gradle Tasks Provided

| Task | Description |
|------|-------------|
| `fetchProtos` | Downloads protos from BSR or Git |
| `prepareGenerators` | Downloads binaries, creates wrapper scripts and buf.gen.yaml |
| `generateProtos` | Runs buf generate with LOCAL plugins |
| `buildDescriptors` | Builds protobuf descriptor files (if enabled) |
| `cleanProtos` | Removes all generated proto artifacts |
| `lintProtos` | Runs buf lint on exported proto files |
| `checkBreaking` | Checks for breaking changes against a reference |
| `formatProtos` | Formats proto files using buf format |
| `checkFormatProtos` | Checks formatting without making changes (CI-friendly) |

---

## Configuration Options

| Property | Default | Description |
|----------|---------|-------------|
| `sourceMode` | `'bsr'` | Proto source: `'bsr'` or `'git'` |
| `bufVersion` | `'1.61.0'` | Buf CLI version |
| `protocVersion` | `'4.33.2'` | Protoc compiler version |
| `grpcJavaVersion` | `'1.77.0'` | gRPC Java plugin version |
| `quarkusGrpcVersion` | `'3.30.3'` | Quarkus Mutiny generator version |
| `protocPath` | `null` | Custom protoc path (skips Maven) |
| `grpcJavaPluginPath` | `null` | Custom grpc-java path (skips Maven) |
| `generateGrpc` | `true` | Generate gRPC stubs |
| `generateMutiny` | `true` | Generate Mutiny stubs |
| `generateDescriptors` | `true` | Generate proto.desc file |
| `outputDir` | `build/generated/source/proto/main/java` | Generated code location |
| `descriptorPath` | `build/descriptors/proto.desc` | Descriptor file location |
| `bufGenerateArgs` | `[]` | Extra buf generate arguments |

---

## Summary

**Before**: 68+ lines of fragile, copy-pasted scaffolding per project
**After**: 15 lines of declarative configuration

The plugin provides:
- **Simplicity**: Declare what you need, not how to get it
- **Security**: 100% local generation, no proto uploads
- **Flexibility**: BSR or Git with a flag, custom binaries supported
- **Reliability**: Hermetic builds, no shell dependencies
- **Maintainability**: One place to update, all projects benefit
