# Pipestream Service Registration Extension

A Quarkus extension for the Pipestream AI platform that provides automatic service and module registration with the `platform-registration-service`. This extension enables zero-configuration service discovery by automatically registering your Quarkus services with the platform's centralized registry.

## Overview

The Pipestream Service Registration Extension simplifies service registration in the Pipestream AI platform by:

- **Automatically discovering** service metadata from Quarkus configuration
- **Registering services** with the platform-registration-service on startup
- **Handling graceful shutdown** with automatic deregistration
- **Providing retry logic** with exponential backoff for registration failures
- **Supporting both services and modules** through a unified API

The extension communicates with the `platform-registration-service` via gRPC, which then handles Consul registration, health check configuration, and event broadcasting to Kafka.

## Features

- ✅ **Zero-configuration auto-registration** - Just add the extension, services register automatically
- ✅ **Auto-discovers service metadata** - Name, version, host, port from Quarkus config
- ✅ **Supports services and modules** - Unified registration API for both service types
- ✅ **Streaming registration updates** - Real-time progress tracking via gRPC streams
- ✅ **Graceful deregistration** - Clean shutdown handling with automatic unregistration
- ✅ **Retry with exponential backoff** - Handles registration failures automatically
- ✅ **gRPC-based communication** - Uses platform-registration-service gRPC API
- ✅ **Reactive programming** - Built on Mutiny for non-blocking operations
- ✅ **Internal/external address support** - Handles Docker/K8s port mapping scenarios
- ✅ **TLS support** - Configurable TLS for secure gRPC connections

## Quick Start

### 1. Add the dependency

Add the extension to your Quarkus project's `build.gradle`:

```gradle
dependencies {
    implementation 'ai.pipestream:pipestream-service-registration-extension:0.1.0-SNAPSHOT'
}
```

Or for Maven:

```xml
<dependency>
    <groupId>ai.pipestream</groupId>
    <artifactId>pipestream-service-registration-extension</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

### 2. Configure the registration service endpoint

In your `application.properties`:

```properties
# Registration service connection (required)
pipestream.registration.registration-service.host=localhost
pipestream.registration.registration-service.port=9090
```

### 3. That's it!

The extension automatically:
- Registers your service on startup
- Streams registration status updates
- Deregisters on shutdown

## Configuration

All settings have sensible defaults. Override as needed in your `application.properties`:

### Basic Configuration

```properties
# Enable/disable registration (default: true)
pipestream.registration.enabled=true

# Service identity (auto-detected from quarkus.application.name/version)
pipestream.registration.service-name=my-service
pipestream.registration.version=1.0.0

# Service type: SERVICE or MODULE (default: SERVICE)
pipestream.registration.type=SERVICE
```

### Network Configuration

```properties
# Advertised address (client-facing address)
pipestream.registration.advertised-host=0.0.0.0
pipestream.registration.advertised-port=9000

# Internal address (actual bind address, optional)
# Used for Docker/K8s scenarios where service binds to different address
pipestream.registration.internal-host=0.0.0.0
pipestream.registration.internal-port=9000

# TLS configuration
pipestream.registration.tls-enabled=false
```

### Registration Service Connection

```properties
# Registration service endpoint
pipestream.registration.registration-service.host=localhost
pipestream.registration.registration-service.port=9090
pipestream.registration.registration-service.timeout=10s
```

### Retry Configuration

```properties
# Retry behavior for registration failures
pipestream.registration.retry.max-attempts=5
pipestream.registration.retry.initial-delay=1s
pipestream.registration.retry.max-delay=30s
pipestream.registration.retry.multiplier=2.0
```

### Advanced Configuration

```properties
# Tags for service discovery and filtering
pipestream.registration.tags=production,backend,grpc

# Capabilities advertised by this service (primarily for modules)
pipestream.registration.capabilities=parse-pdf,extract-text
```

### Auto-Discovery

The extension automatically discovers the following from Quarkus configuration:

- **Service name**: From `quarkus.application.name` (defaults to "unknown-service")
- **Version**: From `quarkus.application.version` (defaults to "1.0.0")
- **gRPC port**: From `quarkus.grpc.server.port` (defaults to 9000)
- **HTTP port**: From `quarkus.http.port` (defaults to 8080)

These values are included in the service metadata automatically.

## Architecture

### Components

The extension consists of the following key components:

1. **ServiceRegistrationManager** (`@ApplicationScoped`)
   - Lifecycle management with startup/shutdown hooks
   - Handles registration retry logic
   - Manages registration state transitions
   - Observes Quarkus `StartupEvent` and `ShutdownEvent`

2. **ServiceMetadataCollector** (`@ApplicationScoped`)
   - Auto-discovers service information from Quarkus config
   - Collects metadata (Java version, Quarkus version, ports)
   - Resolves service name, version, and network configuration

3. **RegistrationClient** (`@ApplicationScoped`)
   - gRPC client wrapper for platform-registration-service
   - Manages gRPC channel lifecycle
   - Provides reactive `Multi` and `Uni` APIs using Mutiny
   - Handles streaming registration responses

4. **RegistrationProcessor** (Build-time)
   - Quarkus deployment processor
   - Registers extension beans at build time
   - Declares the extension feature

### Registration Flow

1. **Startup**: `ServiceRegistrationManager` observes `StartupEvent`
2. **Metadata Collection**: `ServiceMetadataCollector` gathers service information
3. **Registration**: `RegistrationClient` sends gRPC `RegisterRequest` to platform-registration-service
4. **Streaming Updates**: Receives `RegisterResponse` stream with progress events
5. **State Management**: Updates registration state (REGISTERING → REGISTERED)
6. **Shutdown**: Observes `ShutdownEvent` and sends `UnregisterRequest`

### Registration States

The extension tracks registration state through the `RegistrationState` enum:

- `UNREGISTERED` - Initial state, not yet registered
- `REGISTERING` - Registration in progress
- `REGISTERED` - Successfully registered
- `FAILED` - Registration failed after retries
- `DEREGISTERING` - Deregistration in progress
- `DEREGISTERED` - Successfully deregistered

### Proto Definition

The extension uses the following gRPC service from `buf.build/pipestreamai/registration`:

```protobuf
service PlatformRegistrationService {
  // Register a service or module with streaming status updates
  rpc Register(RegisterRequest) returns (stream RegisterResponse);
  
  // Unregister a service or module
  rpc Unregister(UnregisterRequest) returns (UnregisterResponse);
}

message RegisterRequest {
  string name = 1;
  ServiceType type = 2;  // SERVICE_TYPE_SERVICE or SERVICE_TYPE_MODULE
  Connectivity connectivity = 3;
  string version = 4;
  map<string, string> metadata = 5;
  repeated string tags = 6;
  repeated string capabilities = 7;
}
```

## Project Structure

This is a multi-module Quarkus extension project:

```
pipestream-service-registration-extension/
├── runtime/              # Runtime implementation
│   └── src/main/java/
│       └── ai/pipestream/registration/
│           ├── ServiceRegistrationManager.java
│           ├── RegistrationClient.java
│           ├── ServiceMetadataCollector.java
│           ├── config/
│           │   └── RegistrationConfig.java
│           └── model/
│               ├── ServiceInfo.java
│               └── RegistrationState.java
├── deployment/           # Build-time deployment processor
│   └── src/main/java/
│       └── ai/pipestream/registration/deployment/
│           └── RegistrationProcessor.java
├── integration-tests/    # Integration tests
│   └── src/test/java/
│       └── ai/pipestream/registration/it/
│           └── RegistrationExtensionTest.java
└── build.gradle          # Root build configuration
```

## Building

### Prerequisites

- Java 21+
- Gradle 8.0+
- Access to `buf.build/pipestreamai/registration` proto definitions

### Build Commands

```bash
# Build the extension
./gradlew build

# Run tests
./gradlew test

# Build without tests
./gradlew build -x test

# Publish to local Maven repository
./gradlew publishToMavenLocal
```

### Proto Generation

The extension uses [Buf](https://buf.build) to fetch proto definitions and generates Java code using:

- `protoc` with gRPC Java plugin
- Quarkus gRPC Mutiny plugin for reactive stubs
- Custom `protoc-gen-mutiny` script for Mutiny code generation

Proto definitions are fetched from `buf.build/pipestreamai/registration` during the build process.

## Usage Examples

### Basic Service Registration

```java
@ApplicationScoped
public class MyService {
    @Inject
    ServiceRegistrationManager registrationManager;
    
    public void checkStatus() {
        RegistrationState state = registrationManager.getState();
        String serviceId = registrationManager.getServiceId();
        
        if (state == RegistrationState.REGISTERED) {
            System.out.println("Service registered with ID: " + serviceId);
        }
    }
}
```

### Module Registration

For registering a module instead of a service:

```properties
pipestream.registration.type=MODULE
pipestream.registration.capabilities=parse-pdf,extract-text,ocr
```

### Docker/Kubernetes Configuration

For services running in containers with port mapping:

```properties
# Advertised address (what clients use)
pipestream.registration.advertised-host=my-service.example.com
pipestream.registration.advertised-port=9000

# Internal address (actual bind address in container)
pipestream.registration.internal-host=0.0.0.0
pipestream.registration.internal-port=9000
```

## Development

### Adding the Extension to a Quarkus Project

1. Add the dependency to your project
2. Ensure the `platform-registration-service` is running and accessible
3. Configure the registration service endpoint
4. Start your service - registration happens automatically

### Debugging

Enable debug logging to see registration details:

```properties
quarkus.log.category."ai.pipestream.registration".level=DEBUG
```

### Testing

The extension includes unit tests in the `integration-tests` module. Run tests with:

```bash
./gradlew test
```

## Dependencies

### Runtime Dependencies

- Quarkus 3.30.2+
- Quarkus gRPC extension
- Quarkus SmallRye Health extension
- Mutiny (reactive programming)
- gRPC Java 1.77.0+
- Protobuf Java 4.33.1+

### Build Dependencies

- Buf CLI 1.61.0+ (for proto generation)
- Quarkus Extension Processor

## Publishing

The extension is published to:

- **Maven Central Snapshots**: `https://central.sonatype.com/repository/maven-snapshots/`
- **GitHub Packages**: `https://maven.pkg.github.com/ai-pipestream/pipestream-service-registration-extension`

Versioning is managed by the `axion-release` Gradle plugin based on git tags.

## Troubleshooting

### Registration Fails on Startup

1. **Check registration service connectivity**:
   ```bash
   # Verify the service is running
   curl http://localhost:9090/health
   ```

2. **Verify configuration**:
   ```properties
   pipestream.registration.registration-service.host=localhost
   pipestream.registration.registration-service.port=9090
   ```

3. **Check logs** for detailed error messages:
   ```bash
   # Enable debug logging
   quarkus.log.category."ai.pipestream.registration".level=DEBUG
   ```

### Service Not Appearing in Consul

The extension registers with `platform-registration-service`, which then registers with Consul. If your service isn't appearing:

1. Verify the platform-registration-service is running
2. Check platform-registration-service logs for Consul connection issues
3. Verify your service metadata is valid

### Port Configuration Issues

If you're having port-related issues:

1. Ensure `quarkus.grpc.server.port` matches your advertised port
2. For Docker/K8s, configure both advertised and internal addresses
3. Check that the port is not already in use

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

## Contributing

Contributions are welcome! Please ensure that:

1. All tests pass: `./gradlew test`
2. Code follows existing style conventions
3. Proto definitions are fetched from `buf.build/pipestreamai/registration`

## Related Projects

- [platform-registration-service](../platform/core-services/platform-registration-service) - The central registration service
- [pipestream-protos](../platform/pipestream-protos) - Protocol buffer definitions
- [quarkus-dynamic-grpc](../quarkus-dynamic-grpc) - Dynamic gRPC service registration

## Support

For issues, questions, or contributions, please open an issue in the repository.
