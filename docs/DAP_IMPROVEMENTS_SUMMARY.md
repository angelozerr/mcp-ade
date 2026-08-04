# DAP Support Improvements - Summary

## Overview

This document summarizes the major improvements made to the DAP (Debug Adapter Protocol) support in mcp-lsp, inspired by the lsp4ij implementation.

## ✅ Completed Features (3/4 tasks)

### 1. Socket Transport Support (Task #1)

**Problem:** Only stdio (standard input/output) was supported for DAP communication.

**Solution:** Implemented a flexible transport layer supporting both STDIO and SOCKET transports.

**Implementation:**
- Created `TransportStreams` base class for all transport types
- Implemented `StdioTransportStreams` for traditional stdio communication
- Implemented `SocketTransportStreams` for TCP socket communication
- Added `TransportType` enum (STDIO, SOCKET)
- Updated `DapServerConfig` to specify transport type
- Modified `DapServer` to create appropriate transport based on configuration

**Files Created:**
- `core/src/main/java/com/redhat/mcp/languagetools/dap/transport/TransportStreams.java`
- `core/src/main/java/com/redhat/mcp/languagetools/dap/transport/StdioTransportStreams.java`
- `core/src/main/java/com/redhat/mcp/languagetools/dap/transport/SocketTransportStreams.java`
- `core/src/main/java/com/redhat/mcp/languagetools/dap/transport/TransportType.java`

**Usage Example:**
```json
{
  "id": "go-delve",
  "transport": "SOCKET",
  "launch": {
    "default": "dlv dap --listen=127.0.0.1:${port}"
  }
}
```

---

### 2. ${port} Variable Substitution (Task #2)

**Problem:** No support for dynamic port allocation in DAP server commands.

**Solution:** Implemented variable substitution for `${port}` and `${address}` with automatic port allocation and pattern-based extraction from server output.

**Implementation:**
- Created `NetworkAddressExtractor` to parse server output patterns
- Implemented dynamic and static segment parsing
- Added `AddressSegment` and `PortSegment` for extracting network information
- Modified `DapServer.buildCommand()` to detect and replace `${port}`
- Automatic port allocation using `getAvailablePort()`

**Files Created:**
- `core/src/main/java/com/redhat/mcp/languagetools/dap/configurations/NetworkAddressExtractor.java`
- `core/src/main/java/com/redhat/mcp/languagetools/dap/configurations/ExtractorResult.java`
- `core/src/main/java/com/redhat/mcp/languagetools/dap/configurations/Segment.java`
- `core/src/main/java/com/redhat/mcp/languagetools/dap/configurations/StaticSegment.java`
- `core/src/main/java/com/redhat/mcp/languagetools/dap/configurations/DynamicSegment.java`
- `core/src/main/java/com/redhat/mcp/languagetools/dap/configurations/AddressSegment.java`
- `core/src/main/java/com/redhat/mcp/languagetools/dap/configurations/PortSegment.java`

**Usage Example:**
```json
{
  "launch": {
    "default": "dlv dap --listen=127.0.0.1:${port}"
  },
  "debugServerReadyPattern": "DAP server listening at: ${address}:${port}"
}
```

---

### 3. Server Ready Tracking (Part of Task #2)

**Problem:** No intelligent way to know when the DAP server is ready to accept connections.

**Solution:** Implemented multiple strategies for detecting server readiness.

**Implementation:**
- Created `DebugServerWaitStrategy` enum (TIMEOUT, TRACE)
- Implemented `DAPServerReadyTracker` for monitoring server startup
- Three detection approaches:
  1. **TIMEOUT**: Wait a fixed duration
  2. **TRACE**: Parse stdout for a pattern
  3. **SOCKET**: Poll socket availability

**Files Created:**
- `core/src/main/java/com/redhat/mcp/languagetools/dap/server/DebugServerWaitStrategy.java`
- `core/src/main/java/com/redhat/mcp/languagetools/dap/server/ServerReadyConfig.java`
- `core/src/main/java/com/redhat/mcp/languagetools/dap/server/DAPServerReadyTracker.java`

**Usage Example:**
```json
{
  "debugServerWaitStrategy": "TRACE",
  "debugServerReadyPattern": "Server ready on ${address}:${port}",
  "connectTimeout": 5000
}
```

---

### 4. Refactored DapServer Startup Flow

**Problem:** Monolithic startup code was hard to maintain and debug.

**Solution:** Refactored into clear, sequential steps with proper async handling.

**Implementation:**
```java
public CompletableFuture<Void> start() {
    return ensureInstalled()
        .thenCompose(v -> startServerProcess())      // 1. Launch process
        .thenCompose(tracker -> waitForServerReady(tracker))  // 2. Wait for ready
        .thenCompose(result -> createLauncherAndInitialize(result));  // 3. Connect
}
```

**Benefits:**
- Clear separation of concerns
- Easier to debug and test
- Proper error propagation
- Support for both STDIO and SOCKET transports

---

### 3. Child Session Support - startDebugging (Task #3)

**Problem:** No support for debugging child processes spawned by the debugged program (crucial for vscode-js-debug and Node.js debugging).

**Solution:** Implemented full support for the DAP `startDebugging` reverse request, allowing parent-child debug session relationships.

**Implementation:**
- Enhanced `DapClient` with parent-child tracking
- Implemented `IDebugProtocolClient.startDebugging()` method
- Added child session factory pattern
- Recursive session termination (children first, then parent)
- Session hierarchy management in `DapSession`
- Placeholder implementation for `runInTerminal` request

**Files Modified:**
- `core/src/main/java/com/redhat/mcp/languagetools/dap/client/DapClient.java` (ENHANCED)
- `core/src/main/java/com/redhat/mcp/languagetools/dap/session/DapSession.java` (ENHANCED)
- `core/src/main/java/com/redhat/mcp/languagetools/dap/server/DapServer.java` (added getDapClient())

**Key Features:**
```java
// Parent-child relationship
DapClient parentClient;
List<DapClient> childrenClients;

// Factory for creating child sessions
Function<Map<String, Object>, CompletableFuture<DapClient>> childSessionFactory;

// Reverse request implementation
@Override
public CompletableFuture<Void> startDebugging(StartDebuggingRequestArguments args) {
    return childSessionFactory.apply(args.getConfiguration())
        .thenAccept(child -> {
            child.parentClient = this;
            childrenClients.add(child);
        });
}
```

**Usage Scenario - Node.js:**
```javascript
// parent.js spawns child.js
const { fork } = require('child_process');
const child = fork('./child.js');

// vscode-js-debug sends startDebugging request
// → mcp-lsp creates child session automatically
// → both parent and child are now being debugged
```

**Documentation:**
- Created `DAP_CHILD_SESSIONS.md` with complete guide

---

## 📊 Architecture Comparison: lsp4ij vs mcp-lsp

| Feature | lsp4ij | mcp-lsp | Status |
|---------|--------|---------|--------|
| STDIO Transport | ✅ | ✅ | Implemented |
| Socket Transport | ✅ | ✅ | Implemented |
| Named Pipes | ❌ | ❌ | Not in either |
| ${port} Substitution | ✅ | ✅ | Implemented |
| Pattern-based Ready Detection | ✅ | ✅ | Implemented |
| Timeout-based Ready Detection | ✅ | ✅ | Implemented |
| Socket Polling Detection | ✅ | ✅ | Implemented |
| startDebugging Support | ✅ | ✅ | Implemented |
| Parent-Child Sessions | ✅ | ✅ | Implemented |
| runInTerminal | ✅ | ⚠️ | Placeholder only |
| Template System | ✅ | ⏳ | Task #4 |

---

## 📁 File Structure

```
core/src/main/java/com/redhat/mcp/languagetools/dap/
├── client/
│   ├── DapClient.java
│   └── DapEventListener.java
├── configurations/
│   ├── NetworkAddressExtractor.java  ← NEW
│   ├── ExtractorResult.java          ← NEW
│   ├── Segment.java                  ← NEW
│   ├── StaticSegment.java            ← NEW
│   ├── DynamicSegment.java           ← NEW
│   ├── AddressSegment.java           ← NEW
│   └── PortSegment.java              ← NEW
├── server/
│   ├── DapServer.java                (REFACTORED)
│   ├── DapServerConfig.java          (ENHANCED)
│   ├── DapServerDescriptorLoader.java
│   ├── DebugServerWaitStrategy.java  ← NEW
│   ├── ServerReadyConfig.java        ← NEW
│   └── DAPServerReadyTracker.java    ← NEW
├── session/
│   ├── DapSession.java
│   ├── DapSessionManager.java
│   └── ...
├── tools/
│   └── DapDebugTools.java
├── trace/
│   ├── DapTraceCollector.java
│   └── DapTraceMessage.java
└── transport/                        ← NEW PACKAGE
    ├── TransportStreams.java         ← NEW
    ├── StdioTransportStreams.java    ← NEW
    ├── SocketTransportStreams.java   ← NEW
    └── TransportType.java            ← NEW
```

---

## 🎯 Remaining Tasks

### Task #4: Template System & Configuration Improvements
**Goal:** Provide pre-configured templates for popular DAP servers.

**Approach (from lsp4ij):**
- Create JSON templates in `resources/templates/dap/`
- Add template loader
- Support server-specific configurations

**Example Templates:**
- Go (Delve)
- Python (debugpy)
- Node.js
- Java

---

## 📖 Documentation

Created comprehensive documentation:
- `DAP_CONFIGURATION_EXAMPLES.md` - Configuration examples with all features
- `DAP_IMPROVEMENTS_SUMMARY.md` - This file

---

## 🧪 Testing Recommendations

1. **STDIO Transport**
   - Test with simple debuggers
   - Verify stdin/stdout communication
   - Test process lifecycle

2. **SOCKET Transport**
   - Test with Delve (Go)
   - Verify port allocation
   - Test pattern matching
   - Test socket connection/disconnection

3. **${port} Substitution**
   - Verify port replacement in commands
   - Test pattern extraction
   - Test multiple patterns

4. **Server Ready Detection**
   - Test TIMEOUT strategy
   - Test TRACE strategy with various patterns
   - Test socket polling

5. **Child Sessions (startDebugging)**
   - Test with Node.js child processes
   - Test with vscode-js-debug
   - Verify parent-child hierarchy
   - Test recursive termination
   - Test multiple levels of nesting

---

## 💡 Key Learnings from lsp4ij

1. **Separation of Concerns**: Transport layer is completely separate from protocol handling
2. **Flexible Configuration**: Multiple strategies for different scenarios
3. **Pattern Matching**: Powerful for extracting dynamic values from server output
4. **Async/Await Patterns**: Clean CompletableFuture chains for async operations
5. **Extensibility**: Easy to add new transport types or wait strategies

---

## 🔗 References

- [lsp4ij DAP Implementation](https://github.com/redhat-developer/lsp4ij)
- [Debug Adapter Protocol Specification](https://microsoft.github.io/debug-adapter-protocol/)
- [LSP4J Debug Support](https://github.com/eclipse/lsp4j)
