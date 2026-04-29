# Patched gRPC Stork name resolver

`pipestream-server/runtime/src/main/java/ai/pipestream/server/grpc/stork/PatchedGrpcStorkServiceDiscovery.java`

A drop-in replacement for Quarkus 3.34's
`io.quarkus.grpc.runtime.stork.GrpcStorkServiceDiscovery`, registered as a
gRPC `NameResolverProvider` via `META-INF/services/io.grpc.NameResolverProvider`.

## What broke (symptoms)

Transport tests intermittently stalled for 30s / 60s / 120s and finished with
partial document counts (e.g. 199/1000, 599/1000). Many runs had docs simply
disappear with no error logged. Across several iterations the failure modes
mixed:

- "Fast fails" — caller hits its gRPC deadline (intake's
  `pipestream.intake.engine.deadline-ms = 30000`) and gives up, having received
  none of its expected responses.
- "Slow stalls" — process sat idle for tens of seconds at a time during a run
  that otherwise made progress.

Engine thread dumps during the stalls showed no engine work in flight: the
hang was happening before the engine ever got the request. The signal lined up
exactly with gRPC channel resolution against Stork+Consul.

## Two upstream bugs combined to wedge channels

**1. Quarkus' single-arg `subscribe().with(Consumer)` drop in
`GrpcStorkServiceDiscovery.resolve()`**

```java
// upstream
serviceInstances.subscribe().with(this::informListener);
```

If the underlying Stork `Uni` emits a `Throwable`, the failure is routed to
`Infrastructure::handleDroppedException` (logged once at WARN and then lost).
`informListener` never runs, so the resolver never resets `resolving = false`
and never calls `listener.onError(...)`. Every later `NameResolver.refresh()`
short-circuits at `if (resolving || shutdown) return;` — the channel is wedged
until the JVM restarts.

**2. Stork's Consul service-discovery has no HTTP timeout**

`stork-service-discovery-consul` constructs the underlying Vert.x
`ConsulClient` without setting a request timeout. A momentarily unresponsive
Consul agent yields a `Uni` that emits neither item nor failure — every
subscriber within Stork's `refresh-period` window blocks indefinitely on the
same memoized in-flight `Uni`.

Combined: a single hung Consul fetch silently dropped its eventual failure into
the Mutiny dropped-exceptions sink, leaving every channel pointing at that
Stork service permanently unable to refresh.

## The fix

Two changes inside `resolve()`:

```java
Uni<List<ServiceInstance>> serviceInstances = serviceDiscovery.getServiceInstances()
        .ifNoItem().after(Duration.ofMillis(fetchTimeoutMs))
        .failWith(() -> new TimeoutException(
                "Stork resolution for '" + serviceName
                        + "' exceeded " + fetchTimeoutMs + "ms"));

serviceInstances.subscribe().with(
        this::informListener,
        failure -> {
            try {
                log.warnf(failure,
                        "Stork resolution failed for service '%s' (timeout=%dms)",
                        serviceName, fetchTimeoutMs);
                listener.onError(Status.UNAVAILABLE
                        .withCause(failure)
                        .withDescription("Stork resolution failed for '"
                                + serviceName + "': " + failure.getMessage()));
            } finally {
                resolving = false;
            }
        });
```

1. **Bound the wait.** `ifNoItem().after(Duration).failWith(TimeoutException)`
   converts a hung Stork fetch into a real Mutiny failure inside the
   configured ceiling.
2. **Two-arg `subscribe().with(onItem, onFailure)`.** Failures (whether from
   our timeout or a real Stork error) reset `resolving` and call
   `Listener2.onError(Status.UNAVAILABLE)`, letting gRPC's load balancer
   transition the channel state and retry instead of hanging.

The same `ifNoItem().after().failWith(TimeoutException::new)` wrapper was
applied to `quarkus-dynamic-grpc/runtime`'s
`ServiceDiscoveryManager.getServiceInstances()` so the dynamic-grpc path is
bounded too.

## Configuration

```
pipestream.stork.fetch-timeout-ms = 5000   # default, milliseconds
```

Must be substantially less than the calling code's gRPC deadline so the caller
has time to retry on a fresh stream.

## Provider priority — why 4

`NameResolverProvider#priority()` returns `4`, matching Quarkus stock.

Initial attempt set priority to `6` (one above DNS at `5`). That broke
`GrpcServiceHealthCheck`: it builds a channel with
`Grpc.newChannelBuilder("localhost:18108", credentials).build()` — no scheme.
gRPC's `NameResolverRegistry` uses the highest-priority resolver as the
default for scheme-less targets, so it prepended `stork://`, called our
`newNameResolver`, which returned a non-null resolver, and channel
construction failed with `NullPointerException: authority` because we were
pretending those targets were Stork service refs.

At priority `4` we tie with stock for `stork://` matches and the tie is
broken by classpath insertion order. Our `pipestream-server` jar loads before
`quarkus-grpc` by alphabetical convention in every build tool we use, so our
entry wins deterministically. DNS at `5` retains its role as the implicit
default resolver for any scheme-less target.

## The attribute-key gotcha (don't repeat this)

First version of the patched class declared its own attribute key:

```java
// WRONG
public static final Attributes.Key<ServiceInstance> SERVICE_INSTANCE =
        Attributes.Key.create("service-instance");
```

`io.grpc.Attributes.Key` uses **object identity**, not the string name, for
equality. Even with the exact same name, a freshly-created key is a different
key. Quarkus' `GrpcLoadBalancerProvider.handleResolvedAddresses` reads back
the `ServiceInstance` from each `EquivalentAddressGroup` using
`GrpcStorkServiceDiscovery.SERVICE_INSTANCE`. With our local key it got
`null` for every address, then NPE'd in `TreeMap.put`'s
`Comparator.comparingLong(instance -> instance.getId())`:

```
SEVERE  Uncaught exception in the SynchronizationContext. Panic!:
  java.lang.NullPointerException
    at java.util.Comparator.lambda$comparingLong$6043328a$1
    at java.util.TreeMap.compare
    at java.util.TreeMap.put
    at io.quarkus.grpc.runtime.stork.GrpcLoadBalancerProvider$1
        .handleResolvedAddresses(GrpcLoadBalancerProvider.java:143)
    at ai.pipestream.server.grpc.stork.PatchedGrpcStorkServiceDiscovery$1
        .informListener(PatchedGrpcStorkServiceDiscovery.java:280)
```

Fixed by aliasing upstream's key directly:

```java
public static final Attributes.Key<ServiceInstance> SERVICE_INSTANCE =
        GrpcStorkServiceDiscovery.SERVICE_INSTANCE;
```

If you ever swap out the resolver again, store under upstream's key — the
load balancer's lookup is identity-based.

## Upstream PRs to file

- `quarkus-grpc`: switch `GrpcStorkServiceDiscovery.resolve()` to two-arg
  `subscribe().with(onItem, onFailure)` so transient resolution failures
  surface to gRPC instead of being dropped.
- `smallrye-stork-service-discovery-consul`: add an HTTP request timeout
  setting on `ConsulClientOptions` so a stuck Consul agent can't pin the
  shared in-flight `Uni` indefinitely.
