# tika4-shaded

A JDK21 Gradle build that takes the latest version of Apache Tika 4 nightly snapshots and shades them into a versioned SNAPSHOT JAR for release.

## Features

This build includes the following Tika 4 components:
- `tika-core` - Core Tika functionality
- `tika-parsers-standard-package` - Standard parsers for common document formats (includes OCR module)
- `tika-parser-scientific-package` - Extended parsers for scientific document formats
- `tika-parser-ocr-module` - OCR support for images and scanned documents (requires tesseract)

Note: The OCR module is included in the standard parsers package but is also added explicitly to ensure availability.

## Requirements

- JDK 21 or later
- Gradle 9.2+ (included via wrapper)
- Network access to https://repository.apache.org/snapshots/ for Tika 4 SNAPSHOT dependencies

## Building

To build the shaded JAR:

```bash
./gradlew shadowJar
```

The shaded JAR will be created in `build/libs/tika4-shaded-4.0.0-SNAPSHOT.jar`

## Automated Builds

This project includes a GitHub Actions workflow that:
- Builds the shaded JAR automatically on push to main/develop branches
- Runs nightly to capture the latest Tika 4 snapshots
- Uploads the built artifact for easy download
- Publishes to GitHub Packages on the main branch

You can trigger a manual build from the Actions tab in GitHub.

## Publishing

To publish to a local Maven repository:

```bash
./gradlew publishToMavenLocal
```

To publish to GitHub Packages (requires GITHUB_TOKEN):

```bash
export GITHUB_ACTOR=your-username
export GITHUB_TOKEN=your-token
./gradlew publish
```

## About Shading

This build uses the Shadow plugin to relocate all Tika packages from `org.apache.tika` to `ai.pipestream.shaded.tika` to avoid conflicts with other versions of Tika that may be present in the classpath.

See [USAGE.md](USAGE.md) for detailed usage examples.

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

Note: This is a shaded packaging project. Apache Tika itself is licensed under the Apache License 2.0.

