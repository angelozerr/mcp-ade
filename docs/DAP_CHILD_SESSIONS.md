# DAP Child Sessions - startDebugging Support

## Overview

The DAP (Debug Adapter Protocol) `startDebugging` request allows a debug adapter to request that the client start a new debug session. This is essential for debugging scenarios where the debugged program spawns child processes that also need to be debugged.

## Use Cases

### 1. Node.js Child Processes

When debugging a Node.js application that spawns child processes using `child_process.fork()` or `cluster.fork()`, the vscode-js-debug adapter will send a `startDebugging` request to debug each child process.

**Example:**
```javascript
// parent.js
const { fork } = require('child_process');
const child = fork('./child.js'); // <-- Will trigger startDebugging
```

### 2. Multi-Process Applications

Applications that spawn multiple worker processes (e.g., Electron main/renderer, Python multiprocessing, Go goroutines with separate processes).

### 3. Test Frameworks

Test runners that spawn separate processes for each test file or suite.

## How It Works

```
┌─────────────────┐
│  Parent DAP     │
│  Session        │
│  (Node.js main) │
└────────┬────────┘
         │
         │ startDebugging request
         │ (when child process spawns)
         │
         ▼
┌─────────────────┐
│  Child DAP      │
│  Session        │
│  (Node.js child)│
└─────────────────┘
```

### Sequence Diagram

```
Client                  Parent DAP Server              Child DAP Server
  |                            |                              |
  |-- initialize ------------>|                              |
  |<- capabilities ------------|                              |
  |                            |                              |
  |-- launch ---------------->|                              |
  |<- initialized -------------|                              |
  |                            |                              |
  |                            |  (child process spawns)      |
  |                            |                              |
  |<- startDebugging request --|                              |
  |                            |                              |
  |-- initialize -------------------------------->|
  |<- capabilities --------------------------------|
  |                            |                              |
  |-- launch --------------------------------->|
  |<- initialized --------------------------------|
  |                            |                              |
  |  (parent and child both debugging)          |
```

## Implementation

### DapClient

The `DapClient` class implements the `IDebugProtocolClient.startDebugging()` method:

```java
@Override
public CompletableFuture<Void> startDebugging(StartDebuggingRequestArguments args) {
    // Extract configuration from the request
    Map<String, Object> configuration = args.getConfiguration();
    
    // Create a child session using the factory
    return childSessionFactory.apply(configuration)
        .thenAccept(childClient -> {
            childClient.parentClient = this;
            childrenClients.add(childClient);
        });
}
```

### Parent-Child Relationship

```java
public class DapClient {
    private DapClient parentClient;
    private final List<DapClient> childrenClients = new ArrayList<>();
    
    // Factory for creating child sessions
    private Function<Map<String, Object>, CompletableFuture<DapClient>> childSessionFactory;
}
```

### DapSession Integration

`DapSession` creates the child session factory during initialization:

```java
@Override
public CompletableFuture<Void> initialize() {
    return dapServer.start()
        .thenAccept(v -> {
            // Configure child session factory
            dapServer.getDapClient().setChildSessionFactory(this::createChildSession);
        });
}

private CompletableFuture<DapClient> createChildSession(Map<String, Object> config) {
    // Create a new DapSession for the child
    String childSessionId = sessionId + "-child-" + (childSessions.size() + 1);
    DapSession childSession = new DapSession(/*...*/);
    
    // Set parent-child relationship
    childSession.parentSession = this;
    this.childSessions.add(childSession);
    
    // Initialize and launch the child
    return childSession.initialize()
        .thenCompose(v -> childSession.launch(config))
        .thenApply(r -> childSession.getDapServer().getDapClient());
}
```

## Session Lifecycle

### Creating Child Sessions

1. Parent DAP server sends `startDebugging` request to client
2. Client extracts configuration from request
3. Client creates new `DapSession` as child
4. Child session is initialized and launched
5. Parent-child relationship is established

### Terminating Sessions

When terminating a session, all child sessions are terminated first:

```java
public CompletableFuture<Void> terminate() {
    // First, terminate all child sessions
    List<CompletableFuture<Void>> childTerminations = new ArrayList<>();
    for (DapSession child : childSessions) {
        childTerminations.add(child.terminate());
    }
    
    return CompletableFuture.allOf(childTerminations.toArray(new CompletableFuture[0]))
        .thenCompose(v -> {
            // Then terminate this session
            // ...
        });
}
```

## Configuration Example

### vscode-js-debug Configuration

```json
{
  "id": "vscode-js-debug",
  "name": "JavaScript Debug",
  "transport": "STDIO",
  "launch": {
    "default": "node ${serverHome}/js-debug/src/dapDebugServer.js"
  },
  "debugServerWaitStrategy": "TIMEOUT",
  "connectTimeout": 1000
}
```

### Launch Configuration

```json
{
  "type": "node",
  "request": "launch",
  "name": "Launch Program",
  "program": "${workspaceFolder}/app.js",
  "autoAttachChildProcesses": true  // Enable child process debugging
}
```

## API

### DapClient

```java
// Set the factory for creating child sessions
public void setChildSessionFactory(
    Function<Map<String, Object>, CompletableFuture<DapClient>> factory)

// Get parent/children
public DapClient getParentClient()
public List<DapClient> getChildrenClients()

// Terminate all child sessions
public CompletableFuture<Void> terminateChildSessions()
```

### DapSession

```java
// Get parent/children
public DapSession getParentSession()
public List<DapSession> getChildSessions()
public boolean hasChildSessions()
```

## Debugging Child Sessions

### Logging

Child sessions are logged with their relationship:

```
[INFO] Initializing DAP session: MyApp (session-1)
[INFO] DAP session initialized: session-1
[INFO] StartDebugging request received: launch
[INFO] Creating child debug session for parent session: session-1
[INFO] Initializing DAP session: MyApp (child) (session-1-child-1)
[INFO] Child debug session started successfully, total children: 1
```

### Session Hierarchy

You can query the session hierarchy:

```java
DapSession parentSession = /*...*/;

// Check if session has children
if (parentSession.hasChildSessions()) {
    System.out.println("Parent has " + parentSession.getChildSessions().size() + " children");
    
    // Iterate through children
    for (DapSession child : parentSession.getChildSessions()) {
        System.out.println("Child session: " + child.getSessionId());
    }
}
```

## Limitations

### RunInTerminal

The `runInTerminal` request is not fully implemented yet. It returns a placeholder response:

```java
@Override
public CompletableFuture<RunInTerminalResponse> runInTerminal(RunInTerminalRequestArguments args) {
    RunInTerminalResponse response = new RunInTerminalResponse();
    response.setProcessId(0);
    return CompletableFuture.completedFuture(response);
}
```

Full implementation would:
1. Parse the `args.getArgs()` command line
2. Launch a terminal (integrated or external)
3. Execute the command in the terminal
4. Return the actual process ID

## Testing

### Test with Node.js

1. Create a simple parent-child Node.js application:

**parent.js:**
```javascript
const { fork } = require('child_process');
console.log('Parent process started');
const child = fork('./child.js');
child.on('message', (msg) => console.log('Message from child:', msg));
```

**child.js:**
```javascript
console.log('Child process started');
process.send({ hello: 'from child' });
```

2. Launch with DAP configuration enabling child process debugging

3. Observe the `startDebugging` request and child session creation in logs

## References

- [DAP Specification - startDebugging](https://microsoft.github.io/debug-adapter-protocol/specification#Requests_StartDebugging)
- [DAP Specification - runInTerminal](https://microsoft.github.io/debug-adapter-protocol/specification#Requests_RunInTerminal)
- [vscode-js-debug](https://github.com/microsoft/vscode-js-debug)
