# Architecture Comparison: lsp4ij vs mcp-lsp

## lsp4ij Architecture

### Phase 1: Server Process Launch
```java
// DAPDebugProcess constructor
serverReadyFuture = DAPServerReadyTracker.getServerReadyTracker(processHandler);
serverReadyFuture.track();  // Wait for server ready
```

### Phase 2: Transport Setup
```java
streamsSupplier = () -> {
    return getTransportStreams(executionResult, 
                              serverReadyFuture.getAddress(), 
                              serverReadyFuture.getPort());
};

// getTransportStreams logic:
if (port != null) {
    return new SocketTransportStreams(address, port);
} else {
    return new DefaultTransportStreams(stdin, stdout);
}
```

### Phase 3: Client Connection & Full Initialization
```java
parentClient = serverDescriptor.createClient(...);
connectToServerFuture = parentClient.connectToServer(indicator);

// Inside DAPClient.connectToServer():
Launcher launcher = createLauncher(wrapper, streams.in, streams.out, threadPool);
debugProtocolFuture = launcher.startListening();
debugProtocolServer = launcher.getRemoteProxy();

return initialize(dapParameters, indicator);

// Inside DAPClient.initialize():
// 1. Send initialize request
debugProtocolServer.initialize(arguments)
    .thenAccept(capabilities -> {
        capabilitiesFuture.complete(capabilities);
    })
    // 2. Send launch/attach
    .thenCompose(unused -> {
        return debugProtocolServer.launch(dapParameters);
        // OR
        return debugProtocolServer.attach(dapParameters);
    })
    .handle((q, t) -> {
        if (t != null) {
            initialized.completeExceptionally(t);
        }
        return q;
    });

// 3. Wait for initialized event + capabilities
CompletableFuture.allOf(initialized, capabilitiesFuture)
    // 4. Set breakpoints
    .thenCompose(v -> breakpointHandler.initialize())
    // 5. Set exception breakpoints
    .thenCompose(v -> sendExceptionBreakpointFilters())
    // 6. Send configurationDone
    .thenCompose(v -> {
        if (capabilities.getSupportsConfigurationDoneRequest()) {
            return debugProtocolServer.configurationDone(new ConfigurationDoneArguments());
        }
    });

return CompletableFuture.allOf(launchAttachFuture, configurationDoneFuture);
```

### Key Points:
- **initialized** is a `CompletableFuture<Void>` that completes when `initialized()` event is received
- **capabilitiesFuture** stores the capabilities from initialize response
- **Order**: initialize → launch → (wait initialized event) → breakpoints → configurationDone
- **Two parallel futures**: launchAttachFuture AND configurationDoneFuture, both must complete

---

## mcp-lsp Current Architecture (BROKEN)

### Phase 1: DapServer.start()
```java
// Does EVERYTHING in one method:
ensureInstalled()
    .thenCompose(v -> startServerProcess())      // Launch process
    .thenCompose(tracker -> waitForServerReady()) // Wait ready
    .thenCompose(result -> createLauncherAndInitialize()) // Create launcher + initialize

// Inside createLauncherAndInitialize():
Launcher launcher = DSPLauncher.createClientLauncher(...);
launcher.startListening();
debugServer.initialize(args).thenApply(capabilities -> {
    setStatus(RUNNING);
    return null;
});
```

### Phase 2: DapSession.launch()
```java
// Called AFTER DapServer.start() completes
server.launch(launchConfig)
    .thenCompose(result -> server.configurationDone())
```

### Problems:
1. ❌ `initialize` is sent in `DapServer.start()` but doesn't wait for response properly
2. ❌ `launch` is sent LATER in `DapSession.launch()`
3. ❌ No proper wait for `initialized` event between initialize response and launch
4. ❌ `configurationDone` sent AFTER launch (should be after breakpoints)
5. ❌ Two separate calls instead of one atomic sequence

---

## Required Changes

### Solution: Refactor to match lsp4ij

```java
// DapServer should ONLY:
// 1. Start process
// 2. Wait for ready
// 3. Create launcher + start listening
// 4. Return ready to connect

public CompletableFuture<Void> start() {
    return ensureInstalled()
        .thenCompose(v -> startServerProcess())
        .thenCompose(tracker -> waitForServerReady(tracker))
        .thenCompose(result -> createLauncher(result));  // Just create, don't initialize
}

private CompletableFuture<Void> createLauncher(ServerReadyResult result) {
    // Create transport
    // Create DapClient
    // Create Launcher
    // Start listening
    // DON'T call initialize here!
    return CompletableFuture.completedFuture(null);
}
```

```java
// DapClient should have:
public CompletableFuture<Void> connectAndInitialize(
    Map<String, Object> dapParameters, 
    boolean isDebug,
    IDebugProtocolServer debugServer) {
    
    // This is the lsp4ij initialize() method
    CompletableFuture<?> launchAttachFuture = debugServer.initialize(arguments)
        .thenAccept(capabilities -> {
            this.capabilities = capabilities;
        })
        .thenCompose(unused -> {
            // Send launch or attach
            return debugServer.launch(dapParameters);
        });
    
    // Wait for initialized event
    CompletableFuture<Void> configurationDoneFuture = 
        CompletableFuture.allOf(initializedFuture, capabilitiesFuture)
            .thenCompose(v -> {
                // TODO: Set breakpoints here
                return CompletableFuture.completedFuture(null);
            })
            .thenCompose(v -> {
                // Send configurationDone
                return debugServer.configurationDone(new ConfigurationDoneArguments());
            });
    
    return CompletableFuture.allOf(launchAttachFuture, configurationDoneFuture);
}
```

```java
// DapSession.launch() becomes simple:
public CompletableFuture<Map<String, Object>> launch(Map<String, Object> launchConfig) {
    return initialize()  // Start server if needed
        .thenCompose(v -> {
            // Call the new connectAndInitialize
            return dapServer.getDapClient().connectAndInitialize(
                launchConfig, 
                true,  // isDebug
                dapServer.getDebugServer()
            );
        })
        .thenApply(v -> {
            state = SessionState.RUNNING;
            return Map.of("success", true);
        });
}
```

### Key Changes:
1. Add `initializedFuture` to DapClient (like lsp4ij)
2. Add `capabilitiesFuture` to DapClient (like lsp4ij)
3. Move ALL initialization logic to DapClient.connectAndInitialize()
4. Follow exact lsp4ij sequence
