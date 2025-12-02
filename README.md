# Pipestream Service Registration Extension

A Quarkus extension for the Pipestream AI platform that provides automatic service registration with the `platform-registration-service`.

## Features

- ✅ **Zero-configuration auto-registration** - Just add the extension, services register automatically
- ✅ **Auto-discovers service metadata** - Name, version, host, port from Quarkus config
- ✅ **Continuous health reporting** - Integrates with SmallRye Health
- ✅ **Graceful deregistration** - Clean shutdown handling
- ✅ **Retry with exponential backoff** - Handles registration failures
- ✅ **gRPC-based communication** - Uses platform-registration-service gRPC API

## Quick Start

### 1. Add the dependency

```gradle
dependencies {
    implementation 'ai.pipestream:pipestream-service-registration-extension:1.0.0-SNAPSHOT'
}
```

### 2. That's it!

The extension automatically:
- Registers your service on startup
- Reports health status continuously
- Deregisters on shutdown

## Configuration

All settings have sensible defaults. Override as needed:

```properties
# Enable/disable registration (default: true)
pipestream.registration.enabled=true

# Service identity (auto-detected from quarkus.application.name/version)
pipestream.registration.service-name=my-service
pipestream.registration.version=1.0.0

# Service address (defaults to 0.0.0.0:8080)
pipestream.registration.host=0.0.0.0
pipestream.registration.port=8080

# Registration service connection
pipestream.registration.registration-service.host=localhost
pipestream.registration.registration-service.port=9090
pipestream.registration.registration-service.timeout=10s

# Health check settings
pipestream.registration.health-check.enabled=true
pipestream.registration.health-check.interval=30s

# Retry configuration
pipestream.registration.retry.max-attempts=5
pipestream.registration.retry.initial-delay=1s
pipestream.registration.retry.max-delay=30s
pipestream.registration.retry.multiplier=2.0
```

## Architecture

### Components

1. **ServiceRegistrationManager** - Lifecycle management, startup/shutdown hooks
2. **ServiceMetadataCollector** - Auto-discovers service information
3. **RegistrationClient** - gRPC client for platform-registration-service
4. **HealthReporter** - Periodic health status updates

### Proto Definition

The extension uses the following gRPC service:

```protobuf
service PlatformRegistrationService {
  rpc RegisterService(RegisterServiceRequest) returns (stream RegisterServiceResponse);
  rpc UnregisterService(UnregisterServiceRequest) returns (UnregisterServiceResponse);
  rpc UpdateHealth(HealthUpdateRequest) returns (HealthUpdateResponse);
}
```

## Building

```bash
./gradlew build
```

## Testing

```bash
./gradlew test
```

## License

See [LICENSE](LICENSE) file.
