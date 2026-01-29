# Pipestream Server Extension

Centralized server defaults for Pipestream services. This extension provides
consistent, cross-service behavior for Quarkus server settings (gRPC/HTTP) and
platform conventions.

## Current Capabilities

- Applies shared gRPC/HTTP server defaults (for example, HTTP/2 window tuning).
- Classifies services with `pipestream.server.class` and `pipestream.server.capabilities`
  to drive defaults for large gRPC payloads.

## Registration Defaults (Design)

Planned behavior is to centralize service registration defaults here so
individual services only configure their port and optional overrides.

### Defaults (planned)

- Service name: `quarkus.application.name`
- HTTP registration:
  - enabled by default
  - base path: `quarkus.http.root-path`
  - health path: `/q/health`
- gRPC registration:
  - enabled by default
  - uses shared server port when `quarkus.grpc.server.use-separate-server=false`
- Advertised and internal host:
  - environment variables win (`SERVICE_REGISTRATION_ADVERTISED_HOST`,
    `SERVICE_REGISTRATION_INTERNAL_HOST`)
  - then `pipestream.server.*` overrides
  - then host-mode defaults (see below)

### Host Mode (planned)

`pipestream.server.host-mode=auto|linux|mac|windows|custom`

- `auto` (default): use OS detection (mac/windows -> `host.docker.internal`,
  linux -> `172.17.0.1`). In `prod`, prefer hostname when available.
- `custom`: require explicit `pipestream.server.advertised-host` and
  `pipestream.server.internal-host`.
- Explicit env overrides always win.

### Related Settings (planned)

- `pipestream.server.advertised-host`
- `pipestream.server.internal-host`
- `pipestream.server.registration.http.enabled`
- `pipestream.server.registration.grpc.enabled`
- `pipestream.server.registration.http.health-path`
- `pipestream.server.registration.http.health-url`

Once implemented, this extension will map these values into
`pipestream.registration.*` for the registration client.
