# **Design Document: Pipestream Proto Toolchain Plugin**

## **1. Executive Summary**

The **Pipestream Proto Toolchain** (ai.pipestream.proto-toolchain) is a bespoke Gradle plugin designed to modernize and unify the Protocol Buffer workflow across the Pipestream ecosystem.

It solves four critical problems:

1. **Dual-Source Resolution:** seamlessly switches between **Buf Schema Registry (BSR)** for internal development and **Git Repositories** for restricted client environments, without changing project code.
2. **100% Local Generation:** All code generation happens locally using Maven-downloaded binaries (protoc, grpc-java). **No proto files are ever uploaded to third-party servers.**
3. **Reactive Stubs:** Generates Quarkus Mutiny reactive stubs alongside standard gRPC stubs via a dynamically generated wrapper script.
4. **Hermetic Builds:** Removes the need for developers or CI to manually install buf, protoc, or shell dependencies - everything is downloaded from Maven Central.

## **2. Architectural Overview**

The plugin operates as a pipeline with three distinct phases: **Resolve**, **Configure**, and **Generate**.

```
flowchart TD
    subgraph "Phase 1: Resolution (fetchProtos)"
        direction TB
        Config[Plugin DSL] -->|Source Mode| Switch{Mode?}
        Switch -- "BSR (Default)" --> BufCLI1[Buf CLI]
        Switch -- "Git (Restricted)" --> BufCLI1

        BSR[(buf.build)] -.->|Pull Module| BufCLI1
        Git[(Git Repo)] -.->|Clone/Checkout| BufCLI1

        BufCLI1 -->|buf export| LocalCache[build/protos/export]
    end

    subgraph "Phase 2: Configuration (prepareGenerators)"
        Maven[(Maven Central)] -->|Download| Protoc[protoc binary]
        Maven -->|Download| GrpcJava[protoc-gen-grpc-java]
        Maven -->|Download| MutinyJar[quarkus-grpc-protoc-plugin.jar]

        Gradle[Gradle Plugin] -->|Generate| Wrapper[protoc-gen-mutiny Script]
        Gradle -->|Generate| BufGen[buf.gen.yaml v2]

        MutinyJar -.-> Wrapper
    end

    subgraph "Phase 3: Generation (generateProtos)"
        BufGen --> BufCLI2[Buf CLI]
        Protoc --> BufCLI2
        GrpcJava --> BufCLI2
        Wrapper --> BufCLI2

        BufCLI2 -->|protoc_builtin: java| JavaOut[Standard Java POJOs]
        BufCLI2 -->|local: grpc-java| GrpcOut[Standard gRPC Stubs]
        BufCLI2 -->|local: mutiny| MutinyOut[Quarkus Mutiny Stubs]
    end

    LocalCache --> BufCLI2
```

## **3. Plugin DSL Specification**

The plugin exposes a clean, declarative DSL. Consumers define *what* they need, not *how* to get it.

```groovy
plugins {
    id 'ai.pipestream.proto-toolchain' version '1.0.0'
    id 'java'
}

pipestreamProtos {
    // ===== Source Configuration =====

    // Global toggle. Can be overridden via CLI: -PprotoSource=git
    // Values: 'bsr', 'git'
    sourceMode = providers.gradleProperty('protoSource').orElse('bsr')

    // ===== Binary Version Configuration =====

    // Buf CLI version (default: 1.61.0)
    bufVersion = '1.61.0'

    // Protoc compiler version (default: 4.33.2)
    protocVersion = '4.33.2'

    // gRPC Java plugin version (default: 1.77.0)
    grpcJavaVersion = '1.77.0'

    // Quarkus gRPC version for Mutiny generation (default: 3.30.3)
    quarkusGrpcVersion = '3.30.3'

    // ===== Optional: Custom Binary Paths =====
    // Use these to skip Maven download and use your own binaries

    // protocPath = '/usr/local/bin/protoc'
    // grpcJavaPluginPath = '/opt/grpc/protoc-gen-grpc-java'

    // ===== Generation Toggles =====

    // Generate standard gRPC stubs (default: true)
    generateGrpc = true

    // Generate Quarkus Mutiny reactive stubs (default: true)
    generateMutiny = true

    // Generate protobuf descriptor files (default: true)
    generateDescriptors = true

    // ===== Output Configuration =====

    // Target directory for generated code (auto-added to sourceSets)
    outputDir = layout.buildDirectory.dir("generated/source/proto/main/java")

    // Descriptor file path
    descriptorPath = layout.buildDirectory.file("descriptors/proto.desc")

    // Extra arguments for buf generate command
    bufGenerateArgs = ['--exclude-path', 'google/']

    // ===== Module Definitions =====

    modules {
        register("intake") {
            // Primary BSR source
            bsr = "buf.build/pipestreamai/intake"

            // Git fallback configuration
            gitRepo = "https://github.com/ai-pipestream/pipestream-protos.git"
            gitRef = "v1.2.0"   // Tag, branch, or commit
            gitSubdir = "intake" // Support for monorepos
        }

        register("common") {
            bsr = "buf.build/pipestreamai/common"
            gitRepo = "https://github.com/ai-pipestream/pipestream-protos.git"
            gitRef = "main"
            gitSubdir = "common"
        }
    }

    // ===== Extra Buf Plugins (Optional) =====

    extraPlugins {
        register("doc") {
            plugin = "buf.build/community/pseudomuto-protoc-gen-doc"
            out = "docs"
            opt = ["markdown,docs.md"]
        }
    }
}
```

## **4. Implementation Details**

### **Phase 1: The Universal Resolver (buf export)**

We leverage buf export as the universal adapter. It normalizes any source into a flat directory of .proto files.

* **Task:** `fetchProtos`
* **Mechanism:**
    * Iterates over registered modules.
    * Executes `buf export <source> --output <buildDir>/protos/export/<module>`.
    * **Git Optimization:** buf handles git authentication (via local SSH keys/https) and sparse checkouts natively.
* **Outcome:** A pristine directory of `.proto` files that represents the "Local Source of Truth".

### **Phase 2: The "Bridge" (Connecting Quarkus to Buf)**

This phase downloads binaries and creates the configuration for local code generation.

* **Task:** `prepareGenerators`
* **Mechanism:**
    1. **Download binaries from Maven Central** (unless custom paths specified):
       - `com.google.protobuf:protoc:<version>:<os>-<arch>@exe`
       - `io.grpc:protoc-gen-grpc-java:<version>:<os>-<arch>@exe`
       - `build.buf:buf:<version>:<os>-<arch>@exe`
    2. Create a **Detached Configuration** in Gradle containing `io.quarkus:quarkus-grpc-protoc-plugin:${version}`.
    3. Resolve this configuration to get the JAR and its dependencies.
    4. Generate a script `build/tmp/protoc-plugins/protoc-gen-mutiny`:
       ```bash
       #!/bin/sh
       # Generated wrapper to invoke Java-based Mutiny Generator from Buf
       exec java -cp "/path/to/quarkus-grpc-protoc-plugin.jar:..."
           io.quarkus.grpc.protoc.plugin.MutinyGrpcGenerator "$@"
       ```
    5. Generate a **buf.gen.yaml v2** with LOCAL plugins (no uploads to BSR).

### **Phase 3: The Engine (buf generate)**

We execute a single buf generate command that acts as the orchestrator for all outputs.

* **Task:** `generateProtos`
* **Input:** The `build/protos/export` directory.
* **Configuration:** The generated buf.gen.yaml (v2 format with LOCAL plugins):

```yaml
# Generated by pipestream-proto-toolchain
# Uses 100% LOCAL generation - no proto files uploaded to BSR
version: v2
plugins:
  # 1. Standard Java POJOs (LOCAL - uses downloaded protoc)
  - protoc_builtin: java
    out: /absolute/path/to/build/generated/source/proto/main/java
    protoc_path: /path/to/protoc

  # 2. Standard gRPC stubs (LOCAL - uses downloaded plugin)
  - local: /path/to/protoc-gen-grpc-java
    out: /absolute/path/to/build/generated/source/proto/main/java

  # 3. Mutiny stubs (LOCAL - uses generated wrapper script)
  - local: /path/to/protoc-gen-mutiny
    out: /absolute/path/to/build/generated/source/proto/main/java
    opt:
      - quarkus.generate-code.grpc.scan-for-imports=none
```

### **Phase 4: Descriptor Generation (Optional)**

* **Task:** `buildDescriptors`
* **Input:** The `build/protos/export` directory.
* **Output:** A FileDescriptorSet file (`build/descriptors/proto.desc`)
* **Use Cases:** gRPC reflection, dynamic message handling, documentation

## **5. Gradle Tasks Provided**

### Core Tasks

| Task | Description |
|------|-------------|
| `fetchProtos` | Downloads protos from BSR or Git using buf export |
| `prepareGenerators` | Downloads binaries, creates wrapper scripts and buf.gen.yaml |
| `generateProtos` | Runs buf generate with all LOCAL plugins |
| `buildDescriptors` | Builds protobuf descriptor files (if enabled) |
| `cleanProtos` | Removes all generated proto artifacts |

### Quality & Validation Tasks

| Task | Description |
|------|-------------|
| `lintProtos` | Runs `buf lint` on exported proto files |
| `checkBreaking` | Checks for breaking changes against a reference |
| `formatProtos` | Formats proto files using `buf format` (modifies files) |
| `checkFormatProtos` | Checks formatting without making changes (CI-friendly) |

## **6. Maven Artifacts Used**

| Component | Maven Coordinate | Purpose |
|-----------|------------------|---------|
| Buf CLI | `build.buf:buf:<version>:<os>-<arch>@exe` | Export protos, run generation |
| Protoc | `com.google.protobuf:protoc:<version>:<os>-<arch>@exe` | Compile protos to Java |
| gRPC Java | `io.grpc:protoc-gen-grpc-java:<version>:<os>-<arch>@exe` | Generate gRPC stubs |
| Mutiny | `io.quarkus:quarkus-grpc-protoc-plugin:<version>` | Generate Mutiny stubs |

Platform classifiers: `linux-x86_64`, `linux-aarch_64`, `osx-x86_64`, `osx-aarch_64`, `windows-x86_64`

## **7. Migration & Integration Strategy**

### **For Standard Clients (Internal/CI)**

* **Config:** `sourceMode = "bsr"` (default)
* **Behavior:** Uses `buf.build` for proto export only. Code generation is 100% local.

### **For Restricted Clients**

* **Config:** `-PprotoSource=git`
* **Behavior:** Direct git clone via buf. No traffic to buf.build at all. Fully self-contained.

### **For Air-Gapped Environments**

* **Config:** Specify custom binary paths
* **Behavior:** Uses pre-installed binaries, no Maven downloads needed.

```groovy
pipestreamProtos {
    protocPath = '/opt/protobuf/bin/protoc'
    grpcJavaPluginPath = '/opt/grpc/protoc-gen-grpc-java'
    // buf still needs to be downloaded or use a local Maven mirror
}
```

### **Integration with Java Plugin**

1. The plugin automatically adds the output directory (`build/generated/source/proto/main/java`) to the main source set.
2. The `compileJava` task automatically depends on `generateProtos` and `buildDescriptors`.
3. The `sourcesJar` task (if present) automatically depends on `generateProtos`.

## **8. Security Considerations**

**Why Local Generation Matters:**

The original design used remote BSR plugins (`buf.build/protocolbuffers/java`), which **upload proto files to buf.build servers** for code generation. This is unacceptable for:
- Enterprise clients with sensitive proto definitions
- Regulated industries (finance, healthcare)
- Government contractors
- Any organization with strict data governance

**Our Solution:**
- All binaries downloaded from Maven Central (auditable, cacheable)
- All code generation happens locally
- No proto files ever leave the build machine
- Works fully offline after initial Maven download

## **9. Cross-Platform Support**

The plugin generates both Unix shell scripts and Windows batch files for the Mutiny generator wrapper, ensuring cross-platform compatibility:
- **Unix:** `build/tmp/protoc-plugins/protoc-gen-mutiny` (shell script)
- **Windows:** `build/tmp/protoc-plugins/protoc-gen-mutiny.bat` (batch file)

The appropriate script is automatically selected based on the current operating system.

## **10. Summary**

This plugin provides:
- **Simplicity**: Declare what you need, not how to get it
- **Security**: 100% local generation, no data exfiltration
- **Flexibility**: BSR or Git with a flag, custom binaries supported
- **Reliability**: Hermetic builds, no shell dependencies
- **Maintainability**: One place to update, all projects benefit
