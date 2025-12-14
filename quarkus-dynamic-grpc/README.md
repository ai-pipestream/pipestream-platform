# Quarkus Dynamic gRPC Extension

A Quarkus extension that enables creating gRPC clients with service names known only at **runtime**. This solves the problem where standard Quarkus gRPC requires service names at **compile time**.

## The Problem

All major frameworks (Quarkus, Spring, Micronaut) require gRPC service names to be known at compile time:

```java
// ❌ Traditional approach - MUST hardcode service name
@Inject @GrpcClient("hardcoded-service")
MyServiceStub myService;
```

This breaks down when you have:
- Multiple services implementing the same interface with different service names
- Service names determined at runtime (from requests, database, configuration)
- Dynamic module architectures where you don't know all services upfront

## The Solution

This extension provides a `GrpcClientFactory` that enables dynamic service name resolution:

```java
// ✅ Service name from runtime - request, DB, config, etc.
@Inject
GrpcClientFactory factory;

// Later, at runtime...
String serviceName = request.getTargetModule();  // "module-chunker", "module-parser"
Uni<ModuleStub> stub = factory.getClient(
    serviceName,
    MutinyModuleGrpc::newMutinyStub
);
```

## Features

- **Dynamic Service Discovery**: Create gRPC clients with service names known only at runtime
- **Flexible Discovery Backends**: Supports Consul, static lists, Kubernetes, and any Stork-compatible provider
- **Large Message Support**: Configurable message size limits up to 2GB (default)
- **Channel Caching**: Efficient channel reuse with configurable TTL and max size
- **Graceful Shutdown**: Proper cleanup of channels on eviction or application shutdown
- **TLS/mTLS Support**: Full TLS configuration with certificate management
- **Auth Token Propagation**: Automatic authentication header injection
- **Mutiny Support**: Full Mutiny stub support for reactive programming
- **Zero Reflection**: Type-safe stub creation using method references
- **Metrics Integration**: Micrometer metrics for monitoring channel usage

## Installation

Add the dependency to your `build.gradle`:

```gradle
dependencies {
    implementation 'ai.pipestream:quarkus-dynamic-grpc:1.0.0-SNAPSHOT'
}
```

Or in Maven:

```xml
<dependency>
    <groupId>ai.pipestream</groupId>
    <artifactId>quarkus-dynamic-grpc</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

## Configuration

Add these properties to your `application.properties`:

### Channel Configuration

```properties
# Channel pool settings
quarkus.dynamic-grpc.channel.idle-ttl-minutes=15
quarkus.dynamic-grpc.channel.max-size=1000
quarkus.dynamic-grpc.channel.shutdown-timeout-seconds=2

# Message size limits (default: 2GB for large payload support)
quarkus.dynamic-grpc.channel.max-inbound-message-size=2147483647
quarkus.dynamic-grpc.channel.max-outbound-message-size=2147483647
```

### Service Discovery

The extension uses SmallRye Stork for service discovery. It automatically checks for Stork configuration before falling back to Consul.

#### Option 1: Consul Discovery (Production Default)

```properties
# Consul discovery (used when no Stork config exists for the service)
quarkus.dynamic-grpc.consul.host=localhost
quarkus.dynamic-grpc.consul.port=8500
quarkus.dynamic-grpc.consul.refresh-period=10s
quarkus.dynamic-grpc.consul.use-health-checks=false
```

#### Option 2: Static Discovery (Testing/Development)

For testing without Consul, configure static service discovery via standard Stork properties:

```properties
# Static discovery for a specific service
stork.my-service.service-discovery.type=static
stork.my-service.service-discovery.address-list=localhost:50051,localhost:50052
```

The `ServiceDiscoveryManager` automatically detects these Stork properties and creates the appropriate service definition. If no Stork config is found, it falls back to Consul-based discovery.

#### Option 3: Kubernetes Discovery

```properties
# Kubernetes service discovery
stork.my-service.service-discovery.type=kubernetes
stork.my-service.service-discovery.k8s-namespace=default
```

### TLS Configuration

```properties
# Enable TLS for all dynamic gRPC clients
quarkus.dynamic-grpc.tls.enabled=true
quarkus.dynamic-grpc.tls.verify-hostname=true

# Trust certificates (PEM format)
quarkus.dynamic-grpc.tls.trust-certificate-pem.certs=/path/to/ca.pem

# Client certificates for mTLS
quarkus.dynamic-grpc.tls.key-certificate-pem.keys=/path/to/client-key.pem
quarkus.dynamic-grpc.tls.key-certificate-pem.certs=/path/to/client-cert.pem

# Development only: trust all certificates (INSECURE!)
quarkus.dynamic-grpc.tls.trust-all=true
```

### Authentication

```properties
# Enable auth token propagation
quarkus.dynamic-grpc.auth.enabled=true
quarkus.dynamic-grpc.auth.header-name=Authorization
quarkus.dynamic-grpc.auth.scheme-prefix=Bearer
```

## Usage

### Inject the Factory

```java
@Inject
GrpcClientFactory factory;
```

### Create a Dynamic Client

```java
// Get a Mutiny stub with a runtime-determined service name
String serviceName = determineServiceFromRequest(request);

Uni<MutinyGreeterGrpc.MutinyGreeterStub> stubUni = 
    factory.getClient(serviceName, MutinyGreeterGrpc::newMutinyStub);

// Use the stub
stubUni.flatMap(stub -> 
    stub.sayHello(HelloRequest.newBuilder().setName("World").build())
).subscribe().with(
    reply -> System.out.println("Received: " + reply.getMessage()),
    error -> System.err.println("Error: " + error.getMessage())
);
```

### Monitoring and Management

```java
// Get number of active service connections
int count = factory.getActiveServiceCount();

// Get cache statistics
String stats = factory.getCacheStats();

// Force eviction of a cached channel
factory.evictChannel("some-service");
```

## Testing

For unit and integration tests, use static discovery to avoid requiring Consul:

```java
// In your test resource or @BeforeAll
@Override
public Map<String, String> start() {
    return Map.of(
        "stork.repo-service.service-discovery.type", "static",
        "stork.repo-service.service-discovery.address-list", "localhost:" + mockServerPort
    );
}
```

This approach:
- Eliminates test dependency on Consul
- Allows pointing to WireMock, Testcontainers, or local mock servers
- Works with Quarkus `@QuarkusTestResource`

## Architecture

### Service Discovery Flow

1. **Check Stork Config**: `ServiceDiscoveryManager` first checks if `stork.<service>.service-discovery.type` exists in MicroProfile Config
2. **Use Config if Present**: If found, creates a Stork service definition from the config properties
3. **Fallback to Consul**: If no Stork config exists, creates a Consul-based discovery definition
4. **Channel Creation**: `ChannelManager` creates gRPC channels with proper message size limits and TLS settings

### Channel Lifecycle

- Channels are cached with configurable TTL (default 15 minutes idle)
- Evicted channels are gracefully shut down
- On application shutdown, all channels are cleaned up within the configured timeout

## Requirements

- Java 21+
- Quarkus 3.30+
- For production: Consul, Kubernetes, or another Stork-compatible service registry
- For testing: No external dependencies (use static discovery)

## License

MIT License - see [LICENSE](LICENSE) for details.

