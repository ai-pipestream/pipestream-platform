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
- **Consul Integration**: Uses Consul for service discovery via SmallRye Stork
- **Channel Caching**: Efficient channel reuse with configurable TTL and max size
- **Graceful Shutdown**: Proper cleanup of channels on eviction or application shutdown
- **Mutiny Support**: Full Mutiny stub support for reactive programming
- **Zero Reflection**: Type-safe stub creation using method references

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

```properties
# Consul discovery
quarkus.dynamic-grpc.consul.host=localhost
quarkus.dynamic-grpc.consul.port=8500
quarkus.dynamic-grpc.consul.refresh-period=10s
quarkus.dynamic-grpc.consul.use-health-checks=false

# Channel pool
quarkus.dynamic-grpc.channel.idle-ttl-minutes=15
quarkus.dynamic-grpc.channel.max-size=1000
quarkus.dynamic-grpc.channel.shutdown-timeout-seconds=2
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

## Requirements

- Java 21+
- Quarkus 3.30.1+
- Consul for service discovery

## License

MIT License - see [LICENSE](LICENSE) for details.

