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

## Registration Defaults (Current)

This extension already centralizes service registration defaults so individual
services only configure ports and optional overrides.

### Defaults (current)

- Service identity:
  - name: `quarkus.application.name`
  - version: `quarkus.application.version`
- Registration service discovery (all environments):
  - `pipestream.registration.registration-service.discovery-name=platform-registration`
  - Consul defaults: `localhost:8500` unless overridden
- Registration requirement:
  - `pipestream.registration.required=true` (dev/prod default)
  - `%test.pipestream.registration.required=false`
- HTTP registration:
  - enabled by default
  - base path: `quarkus.http.root-path`
  - health path: `/q/health/live` when registration is required, using `quarkus.http.non-application-root-path`
- gRPC registration:
  - shared server by default (`quarkus.grpc.server.use-separate-server=false`)
  - health and reflection services enabled
- Advertised and internal host selection:
  - environment variables win (`SERVICE_REGISTRATION_ADVERTISED_HOST`,
    `SERVICE_REGISTRATION_INTERNAL_HOST`)
  - then `pipestream.server.advertised-host` / `pipestream.server.internal-host`
  - then host-mode defaults (see below)

### Host Mode (current)

`pipestream.server.host-mode=auto|linux|mac|windows|custom`

- `auto` (default): use OS detection (mac/windows -> `host.docker.internal`,
  linux -> `172.17.0.1`). In `prod`, prefer the machine hostname when available.
- `custom`: require explicit `pipestream.server.advertised-host` and
  `pipestream.server.internal-host`.
- Explicit env overrides always win.

### Policy (dev/test)

- Do not set `pipestream.registration.advertised-host` or
  `pipestream.registration.internal-host` in dev/test.
- Rely on the `pipestream-server` defaults to resolve the correct host per OS.

### Related Settings (current)

- `pipestream.server.host-mode`
- `pipestream.server.advertised-host`
- `pipestream.server.internal-host`
- `pipestream.registration.advertised-host`
- `pipestream.registration.internal-host`

## OpenAPI Defaults (Current)

This extension enables OpenAPI and Swagger UI by default for services that
include `pipestream-server`.

- OpenAPI endpoint is enabled (`/q/openapi` by default).
- Swagger UI is always included (`/q/swagger-ui` by default).
- Default info fields (override via config or `@OpenAPIDefinition`):
  - `quarkus.smallrye-openapi.info-title` from `quarkus.application.name`
  - `quarkus.smallrye-openapi.info-version` from `quarkus.application.version`
  - `quarkus.smallrye-openapi.info-description` from `pipestream.registration.description`
