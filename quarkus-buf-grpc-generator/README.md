# Pipestream Proto Toolchain

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Gradle Plugin](https://img.shields.io/badge/Gradle-Plugin-blue.svg)](https://plugins.gradle.org/)
[![Java 17+](https://img.shields.io/badge/Java-17%2B-orange.svg)](https://openjdk.org/)
[![Build](https://github.com/ai-pipestream/pipestream-platform/actions/workflows/test.yml/badge.svg)](https://github.com/ai-pipestream/pipestream-platform/actions/workflows/test.yml)

A Gradle plugin for streamlined Protocol Buffer code generation with **100% local execution**. Fetch protos from BSR or Git, generate Java/gRPC/Mutiny stubs—all without uploading data to external servers.

**[Website](https://pipestream.ai)** | **[GitHub](https://github.com/ai-pipestream)** | **[Documentation](#documentation)**

---

## Features

- **Dual-source resolution** - Seamlessly switch between BSR and Git
- **100% local generation** - No proto files uploaded to external servers
- **Hermetic builds** - All binaries downloaded from Maven Central
- **Quarkus Mutiny support** - Reactive gRPC stubs out of the box
- **Zero boilerplate** - Declarative DSL replaces manual task wiring

## Quick Start

Add to your `build.gradle`:

```groovy
plugins {
    id 'java'
    id 'ai.pipestream.proto-toolchain' version '1.0.0'
}

repositories {
    mavenCentral()
}

pipestreamProtos {
    modules {
        register("mymodule") {
            bsr = "buf.build/yourorg/yourmodule"
        }
    }
}
```

Build:

```bash
./gradlew build
```

Generated sources appear in `build/generated/source/proto/main/java/`.

## Documentation

| Document | Description |
|----------|-------------|
| **[GUIDE.md](GUIDE.md)** | Complete tutorial with examples and configuration reference |
| **[DESIGN_IDEA.md](DESIGN_IDEA.md)** | Technical architecture and implementation details |
| **[BEFORE_AFTER_EXAMPLE.md](BEFORE_AFTER_EXAMPLE.md)** | Migration guide showing before/after comparison |

## Building from Source

### Prerequisites

- Java 17 or higher
- Gradle 8.x (wrapper included)

### Build

```bash
# Clone the repository
git clone https://github.com/ai-pipestream/pipestream-platform.git
cd pipestream-platform/quarkus-buf-grpc-generator

# Build the plugin
./gradlew build

# Run tests
./gradlew test

# Install to local Maven repository
./gradlew publishToMavenLocal
```

### Using Local Build

After `publishToMavenLocal`, use in your project:

```groovy
buildscript {
    repositories {
        mavenLocal()
        mavenCentral()
    }
}

plugins {
    id 'ai.pipestream.proto-toolchain' version '1.0.0-SNAPSHOT'
}
```

## Project Structure

```
quarkus-buf-grpc-generator/
├── src/main/groovy/ai/pipestream/proto/
│   ├── ProtoToolchainPlugin.groovy    # Main plugin class
│   ├── ProtoExtension.groovy          # DSL extension
│   ├── ProtoModule.groovy             # Module configuration
│   ├── BinaryResolver.groovy          # Maven binary downloads
│   └── tasks/
│       ├── FetchProtosTask.groovy     # BSR/Git export
│       ├── PrepareGeneratorsTask.groovy # Binary setup
│       ├── GenerateProtosTask.groovy  # Code generation
│       └── BuildDescriptorsTask.groovy # Descriptor generation
├── src/test/groovy/                   # Test suite
├── GUIDE.md                           # User guide
├── DESIGN_IDEA.md                     # Architecture docs
└── BEFORE_AFTER_EXAMPLE.md            # Migration guide
```

## Contributing

Contributions are welcome! Please feel free to submit issues and pull requests.

1. Fork the repository
2. Create your feature branch (`git checkout -b feature/amazing-feature`)
3. Commit your changes (`git commit -m 'Add amazing feature'`)
4. Push to the branch (`git push origin feature/amazing-feature`)
5. Open a Pull Request

## License

This project is licensed under the MIT License - see the [LICENSE](../LICENSE) file for details.

## Links

- **Website**: [https://pipestream.ai](https://pipestream.ai)
- **GitHub Organization**: [https://github.com/ai-pipestream](https://github.com/ai-pipestream)
- **Bug Reports**: [GitHub Issues](https://github.com/ai-pipestream/pipestream-platform/issues)

---

<p align="center">
  Made with care by the <a href="https://pipestream.ai">Pipestream</a> team
</p>
