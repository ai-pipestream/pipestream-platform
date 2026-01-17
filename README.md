# Pipestream Platform

[![Build](https://github.com/ai-pipestream/pipestream-platform/actions/workflows/test.yml/badge.svg)](https://github.com/ai-pipestream/pipestream-platform/actions/workflows/test.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

[![Maven Central](https://img.shields.io/maven-central/v/ai.pipestream/pipestream-bom?label=BOM)](https://central.sonatype.com/artifact/ai.pipestream/pipestream-bom)
[![Maven Central](https://img.shields.io/maven-central/v/ai.pipestream/pipestream-quarkus-devservices?label=devservices)](https://central.sonatype.com/artifact/ai.pipestream/pipestream-quarkus-devservices)
[![Maven Central](https://img.shields.io/maven-central/v/ai.pipestream/quarkus-apicurio-registry-protobuf?label=apicurio)](https://central.sonatype.com/artifact/ai.pipestream/quarkus-apicurio-registry-protobuf)
[![Maven Central](https://img.shields.io/maven-central/v/ai.pipestream/quarkus-dynamic-grpc?label=dynamic-grpc)](https://central.sonatype.com/artifact/ai.pipestream/quarkus-dynamic-grpc)
[![Maven Central](https://img.shields.io/maven-central/v/ai.pipestream/pipestream-service-registration?label=service-reg)](https://central.sonatype.com/artifact/ai.pipestream/pipestream-service-registration)
[![Gradle Plugin](https://img.shields.io/badge/Gradle-proto--toolchain-blue.svg)](quarkus-buf-grpc-generator/)

Monorepo for Pipestream platform libraries - Quarkus extensions, BOM, Gradle plugins, and utility libraries for the Pipestream AI platform.

## Contents

### Bill of Materials (BOM)

- **pipestream-bom** (`ai.pipestream:pipestream-bom`) - Platform-wide dependency management for all Pipestream projects

### Gradle Plugins

- **[quarkus-buf-grpc-generator](quarkus-buf-grpc-generator/)** (`ai.pipestream.proto-toolchain`) - Gradle plugin for Protocol Buffer 
  code generation with 100% local execution. Fetch protos from BSR or Git, generate Java/gRPC/Mutiny stubs without uploading data to 
  external servers.  This approach uses the more modern buf tool for code generation while still creating the quarkus reactive mutiny stubs.

### Quarkus Extensions

1. **pipestream-quarkus-devservices** - Dev services for local development.  Allows for developers to have 0-config setups when starting 
   services in quarkus dev mode.
2. **quarkus-apicurio-registry-protobuf** - Apicurio Registry integration with Protobuf.  Allows for protobuf configuration of Apicurio 
   Registry with kafka using quarkus with little configuration.
3. **quarkus-dynamic-grpc** - Dynamic gRPC client with service discovery.  Quarkus lacks the ability to have service beans allocated via 
   service discovery without the use of annotations.  With this plugin, a user still gets all the advantages of grpc service discovery 
   without the need for buildtime configuration or reflection.
4. **pipestream-service-registration** - Service registration client for registering pipestream services, modules, and connectors into 
   consul registration and apicruio registration for module and connector configuration schema registration.

## Structure

This project uses **Gradle composite builds** - each extension/library is its own root project, coordinated from a single repository.

```mermaid
graph TD
    A[pipestream-platform] --> B[bom]
    A --> C[quarkus-buf-grpc-generator]
    A --> D[pipestream-quarkus-devservices]
    A --> E[quarkus-apicurio-registry-protobuf]
    A --> F[quarkus-dynamic-grpc]
    A --> G[pipestream-service-registration]

    B:::bom
    C:::plugin
    D:::ext
    E:::ext
    F:::ext
    G:::ext

    classDef bom fill:#e1f5fe
    classDef plugin fill:#fff3e0
    classDef ext fill:#e8f5e9
```

## Versioning Strategy

### Single Versioning with Axion-Release

All components share a **single version** managed at the root level using [axion-release](https://github.com/allegro/axion-release-plugin):

- All extensions and the BOM use the same version (e.g., `0.7.16`)
- Version is determined from git tags with the `v` prefix (e.g., `v0.7.16`)
- **Tagged**: `git tag v0.7.16` → version `0.7.16`
- **Untagged**: No tag → version `0.7.16-SNAPSHOT`

### How It Works

Axion-release automatically determines the version based on git tags at the repository root. All subprojects inherit this version, ensuring consistent releases across all components.

**Note:** `tika4-shaded` is a hard versioned tika library that is built from tika snapshots.  It is managed as a separate independent 
project in its own repository.

## Building

### Build All Extensions

```bash
./gradlew build
```

### Test All Extensions

```bash
./gradlew testAll
```

### Build Individual Extension

```bash
./gradlew pipestream-quarkus-devservices:build
```

### Check Current Version

```bash
./gradlew currentVersion -q
```

This will show the current version for all components (they all share the same version).

## Releasing

Use the **Release Extensions** workflow in GitHub Actions:

1. Go to Actions → Release Extensions → Run workflow
2. Select bump type (patch/minor/major)
3. Optionally enable "Dry run" to preview the version
4. Click "Run workflow"

The workflow will:
- Build and verify all extensions
- Create a git tag for the release (e.g., `v0.7.16`)
- Push the tag (triggers automatic publishing of all components)

## CI/CD Workflows

- **test.yml** - Runs on push/PR to main, tests all extensions. On push to main, also publishes SNAPSHOTs to Maven Central.
- **release-extensions.yml** - Manual workflow to create release tags (single tag for all components)
- **publish-extensions.yml** - Triggered by tags, publishes releases to Maven Central + GitHub Packages

## Using the BOM

Import the BOM to manage all Pipestream dependencies:

```groovy
dependencies {
    implementation platform('ai.pipestream:pipestream-bom:0.7.16')

    // Now you can use dependencies without versions
    implementation 'ai.pipestream:pipestream-quarkus-devservices'
    // Note: tika4-shaded is available as a separate dependency from its own repository
    // implementation 'ai.pipestream:tika4-shaded:0.7.16'
}
```


## Using SNAPSHOT Versions

To use SNAPSHOT versions in your project, add the Maven Central snapshots repository:

```groovy
repositories {
    maven {
        url = uri('https://central.sonatype.com/repository/maven-snapshots/')
        mavenContent { snapshotsOnly() }
    }
}

dependencies {
    implementation platform('ai.pipestream:pipestream-bom:0.7.16-SNAPSHOT')
}
```

## Git History

This monorepo was created using **git subtree merge** to preserve the full commit history of all individual extensions. You can view the history of each extension:

```bash
git log pipestream-quarkus-devservices/
git log quarkus-apicurio-registry-protobuf/
```

## License

MIT License - See [LICENSE](LICENSE) file for details.
