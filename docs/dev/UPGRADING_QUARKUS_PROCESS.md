# Upgrading Quarkus

This document outlines the process for upgrading the Quarkus version.

## Overview

The Quarkus version is centrally managed in the `pipestream-platform` repository. All other services in the Pipestream ecosystem consume this version via the `pipestream-bom` (Bill of Materials) and the `pipestream-bom-catalog` (Version Catalog).

## Upgrade Steps

### 1. Update `pipestream-platform`

The Quarkus version must be updated in two places within the `pipestream-platform` repository to maintain consistency:

1.  **`gradle.properties`**: Update both `quarkusVersion` and `quarkusPluginVersion`.
    ```properties
    quarkusPluginVersion = "3.34.3"
    quarkusVersion = "3.34.3"
    ```
    *   `quarkusPluginVersion` is used in `settings.gradle` for the `io.quarkus` Gradle plugin.
    *   `quarkusVersion` is a reference for humans and potential property-based lookups.

2.  **`gradle/libs.versions.toml`**: Update `quarkusVersion` in the `[versions]` section.
    ```toml
    [versions]
    quarkusVersion = "3.34.3"
    ```
    *   This is the source of truth for the `quarkus-bom` and other Quarkus-related dependencies managed by the Pipestream BOM.

### 2. Verify and Publish

1.  **Local Verification**: Run a full build and tests in `pipestream-platform` to ensure compatibility with the new version.
    ```bash
    ./gradlew clean build
    ```
2.  **Release**: Once verified, release a new version of the platform extensions.
    ```bash
    ./gradlew release
    ```
    This will publish a new version of:
    *   `ai.pipestream:pipestream-bom`
    *   `ai.pipestream:pipestream-bom-catalog`
    *   All extensions (e.g., `ai.pipestream:pipestream-server`)

### 3. Update Downstream Services

Downstream services (e.g., `platform-registration-service`, `pipestream-engine`) need to be updated to point to the new `pipestreamBomVersion`.

1.  Update `gradle.properties` in the downstream service:
    ```properties
    pipestreamBomVersion = "0.7.22" # Example new version
    ```
2.  The service's `settings.gradle` will automatically fetch the new version catalog:
    ```gradle
    versionCatalogs {
        libs {
            from("ai.pipestream:pipestream-bom-catalog:${pipestreamBomVersion}")
        }
    }
    ```

## Affected Projects

The following projects depend on the Quarkus version defined in `pipestream-platform` via the `pipestream-bom`:

- `account-service`
- `connector-admin`
- `connector-intake-service`
- `jdbc-connector`
- `pipestream-engine`
- `pipestream-engine-kafka-sidecar`
- `pipestream-integration-tests`
- `pipestream-opensearch`
- `platform-registration-service`
- `repository-service`
- `s3-connector`
- `quarkus-vanilla-smoke`

### Exceptions
- **`pipestream-wiremock-server`**: This project deliberately does NOT consume `pipestream-bom` to remain independent. It must be updated manually in its own `build.gradle` and `libs.versions.toml` when upgrading the platform.
