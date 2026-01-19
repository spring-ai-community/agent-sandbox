# E2B Connect Protocol Notes

## Architecture

E2B has two components:
1. **REST API** (api.e2b.dev) - Sandbox lifecycle (create, delete, timeout) - standard JSON REST
2. **envd daemon** - Runs inside sandbox on port 49983, uses **Connect-RPC protocol**

## Connect Streaming Format

For **server streaming** RPCs like `process.Process/Start`:

### Request
- Content-Type: `application/connect+json`
- Headers: `Connect-Protocol-Version: 1`, `Connect-Content-Encoding: identity`
- Body: Binary envelope format

### Envelope Format (Binary)
Each message has a 5-byte header:
```
| 1 byte  | 4 bytes (big-endian uint32) |
| flags   | data_length                 |
```

Then `data_length` bytes of JSON data.

### Python SDK Reference
```python
envelope_header_length = 5
envelope_header_pack = ">BI"  # Big-endian: unsigned byte + unsigned int

def encode_envelope(*, flags, data):
    return struct.pack(">BI", flags.value, len(data)) + data

def decode_envelope_header(header):
    flags, data_len = struct.unpack(">BI", header)
    return flags, data_len
```

### Flags
- `0x00` - Normal data
- `0x02` - End of stream (contains trailers)

## Process Execution Flow

1. Send `StartRequest` to `/process.Process/Start`:
   ```json
   {
     "process": {
       "cmd": "/bin/bash",
       "args": ["-l", "-c", "echo hello"],
       "envs": {},
       "cwd": "/home/user"
     },
     "stdin": false
   }
   ```

2. Receive streaming `ProcessEvent` messages:
   - `StartEvent`: `{"event": {"start": {"pid": 123}}}`
   - `DataEvent`: `{"event": {"data": {"stdout": "base64..."}}}` (stdout/stderr are base64)
   - `EndEvent`: `{"event": {"end": {"exit_code": 0, "exited": true}}}`

## File Operations (Unary)

File operations like `/filesystem.Filesystem/Write` are **unary** (not streaming):
- Content-Type: `application/json`
- Standard JSON request/response (no envelope)

## Java Library Options

1. **connect-rpc-java** (me.ivovk) - Server-focused, unclear client support
2. **connect-kotlin** (com.connectrpc) - Official, Kotlin-first with Java extension
3. **Custom implementation** - Parse binary envelopes manually

## Proto Specs

- `/spec/envd/process/process.proto`
- `/spec/envd/filesystem/filesystem.proto`
