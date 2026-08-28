# DAP Server Configuration Examples

This document provides examples of DAP server configurations with various features.

## Transport Types

DAP servers can communicate via two transport mechanisms:

### 1. STDIO (Standard Input/Output) - Default

The DAP client launches the server process and communicates via stdin/stdout:

```json
{
  "id": "simple-debugger",
  "name": "Simple Debugger",
  "transport": "STDIO",
  "launch": {
    "default": "my-debugger"
  }
}
```

### 2. SOCKET (TCP)

The DAP client connects to a server listening on a TCP socket:

```json
{
  "id": "socket-debugger",
  "name": "Socket-based Debugger",
  "transport": "SOCKET",
  "launch": {
    "default": "my-debugger --listen=${port}"
  },
  "debugServerWaitStrategy": "TRACE",
  "debugServerReadyPattern": "Listening on ${address}:${port}"
}
```

## Basic Configuration with ${port} Substitution

When your DAP server needs to listen on a dynamically allocated port, use the `${port}` variable:

```json
{
  "id": "go-delve",
  "name": "Go - Delve",
  "launch": {
    "default": "dlv dap --listen=127.0.0.1:${port}"
  },
  "debugServerWaitStrategy": "TRACE",
  "debugServerReadyPattern": "DAP server listening at: ${address}:${port}",
  "connectTimeout": 5000
}
```

### How it works:

1. `${port}` in the launch command is replaced with an auto-allocated port (e.g., `61537`)
2. The server is launched: `dlv dap --listen=127.0.0.1:61537`
3. The `debugServerReadyPattern` extracts the actual address/port from server output
4. The DAP client waits until it sees the pattern before connecting

## Wait Strategies

### 1. TIMEOUT Strategy

Wait for a fixed timeout before assuming server is ready:

```json
{
  "id": "simple-debugger",
  "name": "Simple Debugger",
  "launch": {
    "default": "my-debugger"
  },
  "debugServerWaitStrategy": "TIMEOUT",
  "connectTimeout": 500
}
```

### 2. TRACE Strategy

Parse server output to detect when it's ready:

```json
{
  "id": "node-debugger",
  "name": "Node.js Debugger",
  "launch": {
    "default": "node --inspect=${port} script.js"
  },
  "debugServerWaitStrategy": "TRACE",
  "debugServerReadyPattern": "Debugger listening on ws://${address}:${port}"
}
```

## Platform-Specific Launch Commands

Different commands for different operating systems:

```json
{
  "id": "cross-platform-debugger",
  "name": "Cross-Platform Debugger",
  "launch": {
    "windows": "debugger.exe --port=${port}",
    "mac": "debugger --port=${port}",
    "default": "debugger --port=${port}"
  },
  "debugServerWaitStrategy": "TRACE",
  "debugServerReadyPattern": "Server ready on ${address}:${port}"
}
```

## Environment Variables

Set environment variables for the DAP server:

```json
{
  "id": "python-debugpy",
  "name": "Python - debugpy",
  "launch": {
    "default": "python -m debugpy --listen ${port}"
  },
  "env": {
    "PYTHONPATH": "/custom/path",
    "DEBUG_MODE": "true"
  },
  "debugServerWaitStrategy": "TRACE",
  "debugServerReadyPattern": "debugpy listening on ${address}:${port}"
}
```

## Working Directory

Specify a custom working directory:

```json
{
  "id": "custom-wd-debugger",
  "name": "Custom WD Debugger",
  "launch": {
    "default": "my-debugger"
  },
  "workingDirectory": "/path/to/project",
  "debugServerWaitStrategy": "TIMEOUT",
  "connectTimeout": 1000
}
```

## Pattern Examples

### Extract both address and port:
```
"DAP server listening at: ${address}:${port}"
```
Matches: `DAP server listening at: 127.0.0.1:61537`

### Extract only port:
```
"Listening on port ${port}"
```
Matches: `Listening on port 8080`

### Complex pattern:
```
"Debug adapter started on ${address}:${port} (ready)"
```
Matches: `Debug adapter started on localhost:4711 (ready)`

## Full Example: Go Delve (Socket Transport)

```json
{
  "id": "go-delve",
  "name": "Go - Delve",
  "description": "Go debugger using Delve",
  "transport": "SOCKET",
  "launch": {
    "windows": "${serverHome}/dlv.exe dap --listen=127.0.0.1:${port}",
    "default": "${serverHome}/dlv dap --listen=127.0.0.1:${port}"
  },
  "debugServerWaitStrategy": "TRACE",
  "debugServerReadyPattern": "DAP server listening at: ${address}:${port}",
  "connectTimeout": 5000,
  "env": {
    "GOPATH": "${workspaceFolder}",
    "GO111MODULE": "on"
  },
  "installer": {
    "url": "https://github.com/go-delve/delve/releases/download/v1.21.0/delve-1.21.0-${os}-${arch}.zip",
    "installPath": "dlv"
  }
}
```

## Full Example: Node.js Debugger (STDIO Transport)

```json
{
  "id": "node-debugger",
  "name": "Node.js Debugger",
  "description": "Debug Node.js applications",
  "transport": "STDIO",
  "launch": {
    "default": "node --inspect-brk"
  },
  "debugServerWaitStrategy": "TIMEOUT",
  "connectTimeout": 1000
}
```

## Variables Available

- `${serverHome}` - Path to the server installation directory
- `${userHome}` - User's home directory
- `${mcpHome}` - Root directory of the MCP Language Tools installation
- `${workspaceFolder}` - Path to the workspace root
- `${port}` - Auto-allocated port number (DAP only)
- `${address}` - Extracted address from server output (DAP only)
- `${vscodeExtension:id}` - Path to a VS Code extension directory

See [Variables Reference](variables.md) for full documentation.

## Transport Type Selection Guide

**Use STDIO when:**
- The DAP server is designed to communicate via stdin/stdout
- You want simpler configuration (no port management)
- The server is always launched by the client
- Examples: Most language-specific debuggers (Python debugpy default mode, etc.)

**Use SOCKET when:**
- The DAP server needs to listen on a network port
- You want to attach to an already-running debug server
- Multiple clients might connect to the same server
- The server logs its readiness via console output
- Examples: Delve (Go), some Node.js debuggers, remote debugging scenarios

## Common Configuration Patterns

### Pattern 1: Socket with Dynamic Port
```json
{
  "transport": "SOCKET",
  "launch": {
    "default": "debugger --port=${port}"
  },
  "debugServerWaitStrategy": "TRACE",
  "debugServerReadyPattern": "Server ready on ${address}:${port}"
}
```

### Pattern 2: Socket with Fixed Port
```json
{
  "transport": "SOCKET",
  "launch": {
    "default": "debugger --port=4711"
  },
  "debugServerWaitStrategy": "TIMEOUT",
  "connectTimeout": 1000
}
```
Note: For fixed port, you still need to configure the ServerReadyConfig in code to specify the port.

### Pattern 3: STDIO with Timeout
```json
{
  "transport": "STDIO",
  "launch": {
    "default": "debugger"
  },
  "debugServerWaitStrategy": "TIMEOUT",
  "connectTimeout": 500
}
```
