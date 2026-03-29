# Agent Sandbox

Unified API for isolated command execution across multiple backends. Same interface for local processes, Docker containers, and E2B cloud microVMs.

## Modules

| Module | Description |
|--------|-------------|
| `agent-sandbox-core` | Core Sandbox API and LocalSandbox |
| `agent-sandbox-docker` | Docker container sandbox via Testcontainers |
| `agent-sandbox-e2b` | E2B cloud microVM sandbox |

## Maven Central

```xml
<dependency>
    <groupId>org.springaicommunity</groupId>
    <artifactId>agent-sandbox-core</artifactId>
    <version>0.9.1</version>
</dependency>
```

## Quick Example

```java
try (Sandbox sandbox = LocalSandbox.builder()
        .tempDirectory("test-")
        .build()) {

    ExecResult result = sandbox.exec(ExecSpec.of("mvn", "test"));

    if (result.success()) {
        System.out.println(result.stdout());
    }
}
```

## Documentation

Full API reference, all backends, file operations, and customizers:
https://springaicommunity.mintlify.app/projects/incubating/agent-sandbox

## License

Apache License 2.0
