# Sandbox SDK Analysis: Learnings from E2B, Daytona, Microsandbox, and Anthropic SRT

## Executive Summary

We analyzed four sandbox offerings to inform the spring-ai-sandbox API design:
1. **E2B** - Remote microVM sandbox (Firecracker)
2. **Daytona** - Container-first agent infrastructure
3. **Microsandbox** - Self-hosted microVM alternative
4. **Anthropic SRT** - Local OS-level sandboxing

Key finding: The core `Sandbox` interface should remain minimal and universal. Implementation-specific features (security policies, API credentials, resource limits) belong in builders, not the interface.

## SDK Repositories Analyzed

| SDK | Location | Primary Language |
|-----|----------|------------------|
| Anthropic SRT | `~/research/supporting_repos/sandbox-runtime/` | TypeScript |
| E2B | `~/research/supporting_repos/E2B/` | Python + TypeScript |
| E2B Code Interpreter | `~/research/supporting_repos/code-interpreter/` | Python + TypeScript |
| E2B Desktop | `~/research/supporting_repos/desktop/` | TypeScript |
| E2B Cookbook | `~/research/supporting_repos/e2b-cookbook/` | Examples |
| Daytona | `~/research/supporting_repos/daytona/` | Go (server) |
| Daytona SDK | `~/research/supporting_repos/daytona-sdk/` | Python + TypeScript |
| Microsandbox | `~/research/supporting_repos/microsandbox/` | Rust + Python/JS/Go SDKs |

## Cross-SDK API Comparison

### Universal Patterns (All SDKs)

| Capability | E2B | Daytona | Microsandbox | Recommendation |
|------------|-----|---------|--------------|----------------|
| Command execution | `commands.run()` | `process.exec()` | `command.run()` | Keep `exec()` |
| Separate stdout/stderr | Yes | Yes | Yes | **Add to ExecResult** |
| Exit code | `exit_code` | `exitCode` | `exit_code` | Already have |
| Timeout support | Per-command | Per-operation | Per-operation | Already have |
| Environment variables | Yes | Yes | Yes | Already have |

### Common Patterns (2+ SDKs)

| Capability | E2B | Daytona | Microsandbox | Recommendation |
|------------|-----|---------|--------------|----------------|
| Directory listing | `files.list(depth)` | `fs.find_files()` | No | **Add `list()`** |
| File deletion | `files.remove()` | `fs.delete_file()` | No | **Add `delete()`** |
| Async/background exec | `background=True` | Sessions | Async throughout | **Add `execAsync()`** |
| Sandbox reconnect | `connect(id)` | `get(id)` | No | Add for remote |

### Unique Features (Single SDK)

| Capability | SDK | Recommendation |
|------------|-----|----------------|
| Security policy | Anthropic SRT | Builder config, not interface |
| Git operations | Daytona | Skip (niche) |
| Volumes | Daytona | Skip (niche) |
| LSP integration | Daytona | Skip (niche) |
| File watch | E2B | P3 nice-to-have |
| Sessions (stateful REPL) | Daytona, Microsandbox | P3 nice-to-have |

## Key Architectural Insights

### 1. Anthropic SRT: Command Wrapper, Not Runtime

Anthropic's sandbox-runtime is fundamentally different from E2B/Daytona:
- It **wraps commands** with OS-level restrictions
- Returns a modified command string, doesn't spawn processes
- Uses `sandbox-exec` (macOS) or `bubblewrap` (Linux)
- Runs a proxy server for network filtering

**Implication**: `AnthropicSandbox` would:
1. Generate wrapped commands internally
2. Execute via standard `ProcessBuilder`
3. Security policy is **construction-time**, not runtime

### 2. Remote vs Local Execution Models

| Aspect | Local (Anthropic, Docker) | Remote (E2B, Daytona) |
|--------|---------------------------|------------------------|
| Lifecycle | Create → Execute → Close | Create → **Reconnect** → Execute → Close |
| Identity | None (ephemeral) | `sandboxId` (persistent) |
| Authentication | None | API key required |
| File operations | Direct filesystem | REST API calls |

**Implication**: `RemoteSandbox` extends `Sandbox` with:
- `sandboxId()`
- `static connect(sandboxId, config)`

### 3. Streaming Patterns

| SDK | Streaming Approach |
|-----|-------------------|
| E2B | Callbacks: `on_stdout`, `on_stderr` |
| Daytona | Async log polling with session IDs |
| Microsandbox | Not yet implemented |
| Anthropic SRT | N/A (caller handles process I/O) |

**Recommendation**: Simple callbacks, not Reactor:
```java
void onStdout(Consumer<String> callback);
void onStderr(Consumer<String> callback);
```

### 4. Configuration Patterns

All SDKs use builder/factory patterns with implementation-specific options:

```python
# E2B
sandbox = Sandbox.create(
    template="base",
    timeout=600,
    metadata={"key": "value"}
)

# Daytona
sandbox = daytona.create(
    image="python:3.11",
    resources={"cpus": 2, "memory_gb": 4},
    labels={"project": "ml"}
)

# Microsandbox
sandbox = PythonSandbox.create(
    name="test",
    memory=512,
    cpus=1
)
```

**Lesson**: Configuration is always at construction time, never via interface methods.

## OO Design Principles Applied

### Interface Segregation Principle (ISP)

**Problem**: If `SandboxPolicy` is in the core interface:
- E2B would have to implement `applyPolicy()` that does nothing
- Docker would throw `UnsupportedOperationException`

**Solution**: Keep core interface minimal. Policy goes in `AnthropicSandbox.Builder`.

### Liskov Substitution Principle (LSP)

**Requirement**: Any `Sandbox` implementation must be substitutable.

**Test**: This code should work with ANY implementation:
```java
void runInSandbox(Sandbox sandbox) {
    ExecResult result = sandbox.exec(ExecSpec.of("echo", "hello"));
    System.out.println(result.stdout());
}
```

### Open/Closed Principle (OCP)

**Requirement**: Add new implementations without modifying core interface.

**Achievement**: Adding `AnthropicSandbox` doesn't change `Sandbox` interface at all.

## Async Strategy Analysis

### What acp-java and mcp-java Do

Both use Project Reactor heavily:
- `Mono<T>` for single async results
- `Flux<T>` for streams
- `Sinks.Many` for backpressure-aware buffering
- Custom schedulers for thread management

### Why Sandbox Should Be Simpler

| Factor | acp-java/mcp-java | spring-ai-sandbox |
|--------|-------------------|-------------------|
| Protocol | Bidirectional JSON-RPC | Request-response |
| Concurrency | Many concurrent sessions | Usually single sandbox |
| Integration | Spring WebFlux | Standalone library |
| Streaming | SSE, WebSocket | stdout/stderr lines |

### Recommended: JDK 21 Virtual Threads + CompletableFuture

With JDK 21 as baseline, blocking code is elegant AND scalable:

```java
// Async execution
CompletableFuture<ExecResult> execAsync(ExecSpec spec);

// Streaming via callbacks
interface CommandHandle {
    void onStdout(Consumer<String> callback);
    void onStderr(Consumer<String> callback);
    CompletableFuture<ExecResult> result();
    void kill();
}
```

**Implementation uses virtual threads internally:**

```java
public CommandHandleImpl(Process process) {
    // Virtual threads - cheap, scalable, simple
    Thread.startVirtualThread(() -> pumpStream(process.getInputStream(), stdoutCallback));
    Thread.startVirtualThread(() -> pumpStream(process.getErrorStream(), stderrCallback));
    Thread.startVirtualThread(this::awaitCompletion);
}

private void pumpStream(InputStream is, Consumer<String> callback) {
    try (var reader = new BufferedReader(new InputStreamReader(is))) {
        String line;
        while ((line = reader.readLine()) != null) {
            callback.accept(line);  // Blocking is fine - virtual thread yields
        }
    }
}
```

**Benefits**:
- Zero dependencies (JDK only)
- Simple imperative code (no reactive operators)
- Scales to thousands of concurrent sandboxes
- Easy to wrap in `Mono` if Spring integration needed
- Familiar debugging with normal stack traces

## Streaming stdout/stderr: Implementation Options

### Option 1: Line-Based Callbacks (Recommended)

```java
CommandHandle handle = sandbox.execAsync(spec);
handle.onStdout(line -> System.out.println("OUT: " + line));
handle.onStderr(line -> System.err.println("ERR: " + line));
ExecResult result = handle.result().get();
```

**Pros**: Simple, familiar, no dependencies
**Cons**: Buffering delay until newline

### Option 2: Chunk-Based Callbacks

```java
handle.onStdoutChunk(bytes -> process(bytes));
```

**Pros**: Lower latency, binary support
**Cons**: More complex for text processing

### Option 3: InputStream Access

```java
InputStream stdout = handle.stdout();
InputStream stderr = handle.stderr();
```

**Pros**: Maximum flexibility
**Cons**: Caller must manage threading

### Option 4: Reactor Flux (If Needed Later)

```java
// In a separate spring-ai-sandbox-reactor module
Flux<String> stdoutFlux = ReactorAdapter.toFlux(handle::onStdout);
```

**Pros**: Integrates with reactive pipelines
**Cons**: Adds dependency

## File Operations Gap Analysis

### Current spring-ai-sandbox

```java
interface SandboxFiles {
    SandboxFiles create(String path, String content);
    String read(String path);
    boolean exists(String path);
    SandboxFiles and();  // Return to Sandbox
}
```

### E2B Capabilities

```java
// E2B has these additional operations
files.list(path, depth)           // Directory listing
files.remove(path, recursive)     // Delete
files.copy(src, dst)              // Copy
files.move(src, dst)              // Move
files.get_info(path)              // Metadata
files.watch(path, callback)       // Watch for changes
```

### Daytona Capabilities

```java
// Daytona has these
fs.find_files(rootDir, pattern)   // Search with regex
fs.download_file(remotePath)      // Download as bytes
fs.upload_file(bytes, path)       // Upload from bytes
```

### Recommended Extensions

```java
interface SandboxFiles {
    // Existing
    SandboxFiles create(String path, String content);
    String read(String path);
    boolean exists(String path);

    // P1: Add these
    List<FileEntry> list(String path);
    List<FileEntry> list(String path, int maxDepth);
    SandboxFiles delete(String path);
    SandboxFiles delete(String path, boolean recursive);

    // P3: Consider later
    FileInfo info(String path);
    byte[] readBytes(String path);
    SandboxFiles createBinary(String path, byte[] content);
}

record FileEntry(
    String name,
    FileType type,  // FILE, DIRECTORY
    String path,
    long size,
    Instant modifiedTime
) {}
```

## Summary of Recommendations

### Do Now (P0)

1. **Separate stdout/stderr in ExecResult**
   - Universal across all SDKs
   - Backwards compatible via `mergedLog()` method

### Do Soon (P1)

2. **Add `list()` and `delete()` to SandboxFiles**
   - Supported by E2B and Daytona
   - Essential for real-world file management

3. **Add `execAsync()` with callbacks**
   - Use `CompletableFuture` + `Consumer` callbacks
   - Avoid Reactor dependency

### Do When Needed (P2)

4. **Create `AnthropicSandbox` module**
   - Security policy in builder
   - Wrap existing `srt` CLI

5. **Create `E2BSandbox` module**
   - REST client implementation
   - Support reconnection

### Defer (P3)

6. File watch, git operations, sessions, resource metrics

---

## Docker + E2B Partnership (January 2025)

### Announcement Summary

Docker and E2B announced a partnership to "build the future of trusted AI" with native MCP (Model Context Protocol) support in E2B sandboxes.

**Source**: https://www.docker.com/blog/docker-e2b-building-the-future-of-trusted-ai/

### Key Technical Integration

1. **MCP Gateway in E2B Sandboxes**
   - E2B sandboxes now include optional MCP gateway
   - Access to 200+ tools from Docker MCP Catalog
   - Each MCP tool runs as isolated Docker container inside the sandbox

2. **Architecture**
   ```
   ┌─────────────────────────────────────────────────────────┐
   │  E2B Sandbox (Firecracker microVM)                      │
   │  ┌─────────────────────────────────────────────────────┐│
   │  │  Docker-in-Sandbox                                  ││
   │  │  ┌────────────┐ ┌────────────┐ ┌────────────┐      ││
   │  │  │ github-mcp │ │ notion-mcp │ │ slack-mcp  │ ...  ││
   │  │  └────────────┘ └────────────┘ └────────────┘      ││
   │  └─────────────────────────────────────────────────────┘│
   │  ┌─────────────────────────────────────────────────────┐│
   │  │  MCP Gateway (HTTP endpoint)                        ││
   │  │  → Exposes unified MCP interface                    ││
   │  │  → Returns mcpUrl + mcpToken for client access      ││
   │  └─────────────────────────────────────────────────────┘│
   │  ┌─────────────────────────────────────────────────────┐│
   │  │  User Code Execution                                ││
   │  └─────────────────────────────────────────────────────┘│
   └─────────────────────────────────────────────────────────┘
   ```

3. **SDK Changes (TypeScript)**
   ```typescript
   const sandbox = await Sandbox.create({
     // Existing config
     template: "base",

     // NEW: MCP configuration
     mcp: {
       github: {
         githubPersonalAccessToken: process.env.GITHUB_TOKEN,
       },
       notion: {
         internalIntegrationToken: process.env.NOTION_TOKEN,
       }
     }
   });

   // Get MCP gateway credentials
   const mcpUrl = sandbox.getMcpUrl();
   const mcpToken = sandbox.getMcpToken();
   ```

### Impact on spring-ai-sandbox Design

#### Validates Builder-Based Configuration

The E2B MCP integration uses the **same pattern we chose**: configuration at construction time, not runtime interface methods.

```java
// Our planned E2BSandbox follows same pattern
Sandbox e2b = E2BSandbox.builder()
    .apiKey(System.getenv("E2B_API_KEY"))
    .template("base")
    .timeout(Duration.ofMinutes(10))
    // MCP config is builder option, not interface method
    .mcp("github", Map.of("githubPersonalAccessToken", token))
    .mcp("notion", Map.of("internalIntegrationToken", notionToken))
    .build();
```

#### MCP Access Pattern

MCP gateway access is implementation-specific, so it belongs outside the core `Sandbox` interface:

```java
// Correct: Cast to access MCP features
E2BSandbox e2b = E2BSandbox.builder()
    .apiKey(key)
    .mcp("github", config)
    .build();

String mcpUrl = e2b.getMcpUrl();
String mcpToken = e2b.getMcpToken();

// Then use with mcp-java
McpClient client = McpClient.connect(mcpUrl, mcpToken);
```

**Rationale**: `getMcpUrl()` in core `Sandbox` interface would violate ISP - LocalSandbox and DockerSandbox don't have MCP gateways.

#### New E2BSandbox Methods

| Method | Type | Description |
|--------|------|-------------|
| `getMcpUrl()` | E2B-specific | URL of MCP gateway in sandbox |
| `getMcpToken()` | E2B-specific | Auth token for MCP gateway |
| `getMcpConfig()` | E2B-specific | Returns configured MCP tools |

### Implications for spring-ai Integration

This partnership creates a natural integration point:

```java
// spring-ai agent using E2B sandbox with MCP tools
Sandbox sandbox = E2BSandbox.builder()
    .apiKey(e2bKey)
    .mcp("github", Map.of("githubPersonalAccessToken", ghToken))
    .build();

// Get MCP client for the sandbox's gateway
McpClient mcp = McpClient.builder()
    .url(((E2BSandbox) sandbox).getMcpUrl())
    .token(((E2BSandbox) sandbox).getMcpToken())
    .build();

// Agent can now:
// 1. Execute code in sandbox via sandbox.exec()
// 2. Access GitHub/Notion/etc via MCP tools
// 3. Read/write files via sandbox.files()
```

### Summary

The Docker+E2B partnership:
1. **Validates** our builder-based configuration approach
2. **Confirms** implementation-specific features stay outside core interface
3. **Creates** natural mcp-java integration opportunity
4. **Adds** MCP support to E2BSandbox implementation (future work)
