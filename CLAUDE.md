# CLAUDE.md

## CRITICAL: Running Claude CLI Subprocesses from Claude Code

Claude Code 2.1.39+ blocks nested `claude` invocations via process tree detection. Any command that spawns `claude` CLI as a subprocess will fail silently with empty output.

**Use `~/scripts/claude-run.sh`** to run builds that invoke Claude CLI:
```bash
~/scripts/claude-run.sh ./mvnw test -pl some-module -Dtest="SomeIT"
```
The script uses `systemd-run` to escape the process tree. Works for Maven/Gradle builds where claude is invoked indirectly via the SDK. See `~/scripts/claude-run.sh` for details.

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build and Development Commands

### Basic Commands
- `./mvnw clean compile` - Compile all modules
- `./mvnw clean test` - Run unit tests
- `./mvnw clean verify` - Run full build including tests
- `./mvnw clean install` - Install artifacts to local repository

### Code Quality
- Code formatting is enforced via `spring-javaformat-maven-plugin`
- Formatting validation runs during the `validate` phase
- Use Spring's Java code formatting conventions

### MANDATORY: Java Formatting Before Commits
- **ALWAYS run `./mvnw spring-javaformat:apply` before any commit**
- CI will fail if formatting violations are found

### Git Commit Guidelines
- **NEVER add Claude Code attribution** in commit messages
- Keep commit messages clean and professional

## Architecture Overview

### Multi-Module Maven Project Structure

```
spring-ai-sandbox/
├── spring-ai-sandbox-core/     # Core Sandbox API (zt-exec)
├── spring-ai-sandbox-docker/   # Docker sandbox (testcontainers) - optional
└── spring-ai-sandbox-bom/      # BOM for dependency management
```

### Key Design Patterns

**Core Abstraction:**
- `Sandbox` - Interface for isolated command execution
- `ExecSpec` - Command specification with timeout and environment
- `ExecResult` - Execution result with exit code and output

**Implementations:**
- `LocalSandbox` - Local process execution using zt-exec
- `DockerSandbox` - Container-based execution using testcontainers

**File Operations (SandboxFiles accessor):**
- `sandbox.files().create()` - Create files in workspace
- `sandbox.files().read()` - Read file content
- `sandbox.files().exists()` - Check file existence
- `sandbox.files().and()` - Return to Sandbox for chaining

### Package Structure
- `org.springaicommunity.sandbox` - Core sandbox abstractions

## Process Management

### Use zt-exec for Process Execution
```java
import org.zeroturnaround.exec.ProcessExecutor;

ProcessResult result = new ProcessExecutor()
    .command("mvn", "test")
    .timeout(5, TimeUnit.MINUTES)
    .readOutput(true)
    .execute();
```

## Testing

- Unit tests for all core abstractions
- TCK tests ensure implementation compliance
- Docker tests require: `-Dsandbox.infrastructure.test=true`
