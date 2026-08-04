# DAP Initialization Sequence - Critical Fixes

## Problem

vscode-js-debug was rejecting configurations with error:
```
Error: Unknown config: {...}
```

Root cause: **Incomplete DAP initialization handshake**

## DAP Protocol Requirements

The Debug Adapter Protocol requires this exact sequence:

```
1. Client → initialize request
2. Server → initialize response (Capabilities)
3. Server → initialized event             ← WAS MISSING
4. Client → setBreakpoints (optional)
5. Client → configurationDone request     ← WAS MISSING
6. Client → launch or attach request
7. Debugging begins
```

## Fixes Applied

### 1. Added InitializeRequestArguments Capabilities

**File**: `DapServer.java`

**Before**:
```java
InitializeRequestArguments initArgs = new InitializeRequestArguments();
initArgs.setClientID("mcp-languagetools");
initArgs.setClientName("MCP Language Tools");
initArgs.setAdapterID(getConfig().getServerId());
initArgs.setPathFormat("path");
initArgs.setLinesStartAt1(true);
initArgs.setColumnsStartAt1(true);
```

**After**:
```java
InitializeRequestArguments initArgs = new InitializeRequestArguments();
initArgs.setClientID("mcp-languagetools");
initArgs.setClientName("MCP Language Tools");
initArgs.setAdapterID(getConfig().getServerId());
initArgs.setPathFormat("path");
initArgs.setLinesStartAt1(true);
initArgs.setColumnsStartAt1(true);

// Declare support for reverse requests (CRITICAL for vscode-js-debug)
initArgs.setSupportsRunInTerminalRequest(true);
initArgs.setSupportsStartDebuggingRequest(true);
initArgs.setSupportsVariableType(true);
initArgs.setSupportsVariablePaging(false);
```

**Why**: vscode-js-debug **requires** the client to declare support for `startDebuggingRequest` and `runInTerminalRequest` to enable child process debugging.

---

### 2. Added initialized() Event Handler

**Files**: `DapEventListener.java`, `DapClient.java`, `DapSession.java`

**DapEventListener.java**:
```java
public interface DapEventListener {
    /**
     * Called when the debug adapter is initialized and ready to receive configuration.
     */
    void onInitialized();  // ← NEW
    
    void onStopped(StoppedEventArguments event);
    // ... other events
}
```

**DapClient.java**:
```java
@Override
public void initialized() {
    LOG.info("Initialized event received");
    if (eventListener != null) {
        eventListener.onInitialized();
    }
}
```

**DapSession.java**:
```java
@Override
public void onInitialized() {
    LOG.infof("Session %s initialized event received", sessionId);
    // Server is now ready to receive configuration (breakpoints, etc.)
}
```

**Why**: The DAP server sends an `initialized` event after processing the `initialize` request. The client **must** handle this event to know when the server is ready for configuration.

---

### 3. Added configurationDone Request

**File**: `DapSession.java` - `launch()` method

**Before**:
```java
return server.launch(launchConfig)
    .thenApply(result -> {
        state = SessionState.RUNNING;
        return Map.of("success", true);
    });
```

**After**:
```java
// Send configurationDone before launch (DAP protocol requirement)
LOG.infof("Sending configurationDone before launch");
ConfigurationDoneArguments configArgs = new ConfigurationDoneArguments();

return server.configurationDone(configArgs)
    .thenCompose(configResult -> {
        LOG.infof("configurationDone completed, now sending launch request");
        return server.launch(launchConfig);
    })
    .thenApply(result -> {
        state = SessionState.RUNNING;
        return Map.of("success", true);
    });
```

**Why**: The `configurationDone` request signals to the server that the client has finished sending configuration (breakpoints, exception filters, etc.) and is ready to start debugging. The server may **wait** for this before accepting `launch` or `attach`.

---

## Correct Initialization Flow

### Before (BROKEN)
```
Client               Server
  |                    |
  |-- initialize ----->|
  |<- capabilities ----|
  |                    |
  |-- launch --------->|  ← TOO EARLY!
  |                    |
  ERROR: Server not ready
```

### After (CORRECT)
```
Client               Server
  |                    |
  |-- initialize ----->|
  |<- capabilities ----|
  |                    |
  |<- initialized -----|  ← NEW: Wait for this event
  |                    |
  |-- configurationDone->| ← NEW: Signal client is ready
  |                    |
  |-- launch --------->|  ← NOW it works!
  |                    |
  Debugging starts
```

---

## Comparison with lsp4ij

### lsp4ij Implementation (DAPClient.java:162-253)

```java
debugProtocolServer.initialize(arguments)
    .thenAccept(capabilities -> {
        capabilitiesFuture.complete(capabilities);
    })
    .thenCompose(unused -> {
        // Launch or attach
        return debugProtocolServer.launch/attach(dapParameters);
    });

// Wait for BOTH initialized event AND capabilities
CompletableFuture.allOf(initialized, capabilitiesFuture)
    // Set breakpoints
    .thenCompose(v -> breakpointHandler.initialize())
    // Set exception breakpoint filters  
    .thenCompose(v -> sendExceptionBreakpointFilters())
    // Send configurationDone
    .thenCompose(v -> {
        if (capabilities.getSupportsConfigurationDoneRequest()) {
            return debugProtocolServer.configurationDone(new ConfigurationDoneArguments());
        }
    });
```

### mcp-lsp Implementation (Now Fixed)

```java
// DapServer.start() handles:
// 1. initialize request → response

// DapClient.initialized() handles:
// 2. initialized event

// DapSession.launch() handles:
// 3. configurationDone request
// 4. launch request
```

We don't yet implement breakpoint initialization before `configurationDone`, but that can be added later. The critical sequence is now correct.

---

## Testing

### Expected Trace Output

```
Starting VSCode JS Debug...
Command: node .../dapDebugServer.js 62381 127.0.0.1
DAP server process started (PID: 60576)
DAP server ready (address=127.0.0.1, port=62381)

[Trace] Sending request 'initialize - (1)'.
Params: {
  "clientID": "mcp-languagetools",
  "adapterID": "vscode-js-debug",
  "supportsRunInTerminalRequest": true,      ← NEW
  "supportsStartDebuggingRequest": true      ← NEW
}

[Trace] Received response 'initialize - (1)' in 4ms.
Result: { "supportsConfigurationDoneRequest": true, ... }

[Trace] Received notification 'initialized'   ← NEW: Handler now exists
Session xyz initialized event received        ← NEW: Logged

[Trace] Sending request 'configurationDone - (2)'. ← NEW
Params: {}

[Trace] Received response 'configurationDone - (2)' in 1ms.

[Trace] Sending request 'launch - (3)'.       ← Now sent AFTER configurationDone
Params: { "name": "Launch Program", ... }

[Trace] Session started successfully          ← Works!
```

---

## Remaining Improvements

While the initialization sequence is now correct, lsp4ij does additional steps we could add:

1. **Breakpoint initialization** - Set breakpoints between `initialized` event and `configurationDone`
2. **Exception breakpoint filters** - Configure which exceptions to break on
3. **Capabilities checking** - Only send `configurationDone` if `supportsConfigurationDoneRequest` is true
4. **State tracking** - Use `CompletableFuture` to track initialization state more robustly

These are **nice-to-have** improvements. The critical fixes for vscode-js-debug are now in place.

---

## References

- [DAP Specification - Initialization](https://microsoft.github.io/debug-adapter-protocol/overview#initialization)
- [DAP Specification - configurationDone](https://microsoft.github.io/debug-adapter-protocol/specification#Requests_ConfigurationDone)
- [vscode-js-debug](https://github.com/microsoft/vscode-js-debug)
- [lsp4ij DAP Implementation](https://github.com/redhat-developer/lsp4ij)
