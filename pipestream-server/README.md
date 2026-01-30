# Pipestream Server Extension

Centralized server defaults for Pipestream services. This extension provides
consistent, cross-service behavior for Quarkus server settings (gRPC/HTTP) and
platform conventions.

## Current Capabilities

- Applies shared gRPC/HTTP server defaults (for example, HTTP/2 window tuning).
- Classifies services with `pipestream.server.class` and `pipestream.server.capabilities`
  to drive defaults for large gRPC payloads.

### Server class and capabilities (implemented)

- Config keys:
  - `pipestream.server.class` (default: `core`)
  - `pipestream.server.capabilities` (comma-delimited)
  - `pipestream.server.http2.connection-window-size` (optional override)
- Known classes: `core`, `module`, `connector`, `engine`. Unknown classes log a warning and fall back to `core` behavior.
- Behavior: if the class is `module`, `connector`, or `engine`, or if capabilities include `large-grpc` or `pipedoc`, the HTTP/2 connection window size is set to 104,857,600 (100 MiB) unless explicitly overridden by `pipestream.server.http2.connection-window-size`.

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
  linux -> `172.17.0.1`). In `prod`, prefer hostname when available. In non-prod, if `quarkus.application.name` is set, that name is used as the derived advertised/internal host before falling back to the OS default.
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
