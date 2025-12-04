# Pipestream Quarkus Extensions

Monorepo for Pipestream Quarkus extensions - custom Quarkus extensions built for the Pipestream AI platform.

## Extensions

This monorepo contains 4 Quarkus extensions:

1. **pipestream-quarkus-devservices** - Dev services for local development
2. **quarkus-apicurio-registry-protobuf** - Apicurio Registry integration with Protobuf
3. **quarkus-dynamic-grpc-extension** - Dynamic gRPC client with service discovery
4. **pipestream-service-registration-extension** - Service registration client

## Structure

\`\`\`
pipestream-quarkus-extensions/
├── bom/                                          # Extensions BOM
├── pipestream-quarkus-devservices/              # Extension 1
├── quarkus-apicurio-registry-protobuf/          # Extension 2
├── quarkus-dynamic-grpc-extension/              # Extension 3
└── pipestream-service-registration-extension/   # Extension 4
\`\`\`

## Versioning Strategy

### Independent Versioning with Axion-Release

Each extension has **independent versioning** using [axion-release](https://github.com/allegro/axion-release-plugin):

- **devservices**: \`devservices-v0.2.0\`
- **apicurio**: \`apicurio-v0.2.0\`
- **dynamic-grpc**: \`dynamic-grpc-v0.2.0\`
- **service-registration**: \`service-registration-v0.2.0\`
- **bom**: \`bom-v0.2.0\`

### How It Works

Axion-release automatically determines versions based on git tags:

- **Tagged**: \`git tag devservices-v0.2.0\` → version \`0.2.0\`
- **Untagged**: No tag → version \`0.2.0-SNAPSHOT\`

**SNAPSHOT versions indicate no changes** since the last tag, so CI/CD skips publishing them.

## Building

### Build All Extensions

\`\`\`bash
./gradlew build
\`\`\`

### Build Individual Extension

\`\`\`bash
cd pipestream-quarkus-devservices
../gradlew build
\`\`\`

## Git History

This monorepo was created using **git subtree merge** to preserve the full commit history of all individual extensions. You can view the history of each extension:

\`\`\`bash
git log pipestream-quarkus-devservices/
git log quarkus-apicurio-registry-protobuf/
\`\`\`

## License

MIT License - See [LICENSE](LICENSE) file for details.
