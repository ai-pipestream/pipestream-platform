# Pipestream Test Support

Shared test fixtures for Pipestream services.

## What this provides
- `BaseWireMockTestResource`: common container lifecycle logic for the WireMock gRPC server.
- `WireMockTestResource`: default registration-service mock (direct gRPC port).
- `ConnectorAdminWireMockTestResource`: account-manager/stork config for connector-admin.
- `ConnectorIntakeWireMockTestResource`: intake-specific stork overrides.
- `RepositoryWireMockTestResource`: repository-service stork + account mocks.
- `OpensearchWireMockTestResource`: registration mock for opensearch-manager.
- `EngineWireMockTestResource`: module service stork mappings for engine tests.
- `SidecarWireMockTestResource`: engine/repo mock for sidecar tests.

## How to use
Add the test fixture dependency:

```gradle
testImplementation(testFixtures("ai.pipestream:pipestream-test-support"))
```

Then use your local `WireMockTestResource` class (if you have service-specific overrides) or
reference the shared one directly:

```java
@QuarkusTestResource(ai.pipestream.test.support.WireMockTestResource.class)
```

## Image override
You can override the WireMock container image at runtime:

- Java system property: `pipestream.wiremock.image`
- Environment variable: `PIPESTREAM_WIREMOCK_IMAGE`
- Environment variable (compat): `WIREMOCK_IMAGE`

Default: `docker.io/pipestreamai/pipestream-wiremock-server:0.1.38`
