# Admin UI - User Guide

## Overview

The Admin UI is a web-based dashboard that provides real-time visibility into MCP ADE (Agent Development Environment) operations. It allows you to monitor language servers, debug adapters, MCP clients, and view traces of all communications.

**Access**: `http://localhost:7654/admin`

## Key Features

✅ **Real-time monitoring** of LSP servers and DAP servers  
✅ **Live LSP and MCP traces** for debugging  
✅ **Workspace management** with auto-discovery  
✅ **Server lifecycle control** (start, stop, restart)  
✅ **Contributions visualization** showing relationships between servers  
✅ **WebSocket-based** - no polling, instant updates  

---

## Interface Layout

```
┌─────────────────────────────────────────────────────────────┐
│  [Workspaces] [Servers] [Debuggers] [MCP]  ← Tabs          │
├───────────────┬─────────────┬───────────────────────────────┤
│               │             │                               │
│  Left Panel   │ Middle Panel│    Right Panel (Console)      │
│               │             │                               │
│  • Workspaces │  • Servers  │  • LSP Traces                 │
│  • LSP List   │  • Status   │  • MCP Traces                 │
│  • DAP List   │  • Actions  │  • Search (Ctrl+F)            │
│  • MCP Clients│             │                               │
│               │             │                               │
└───────────────┴─────────────┴───────────────────────────────┘
```

---

## Tab 1: Workspaces

### Purpose
Monitor and manage opened project workspaces with their associated language servers and debug adapters.

### Left Panel: Workspace List
- **Workspace entries**: One per opened project folder
- **Click a workspace** to view its servers
- **Auto-updates** when projects open/close

### Middle Panel: Servers
Shows LSP servers for the selected workspace:

**Server Card displays**:
- 🟢 **Status indicator**: Running / Stopped / Starting / Error
- **Server name** (e.g., "JDT.LS", "Qute", "MicroProfile")
- **Description**
- **PID** (Process ID when running)

**Actions**:
- 🔄 **Restart**: Stop and start the server
- 🛑 **Stop**: Gracefully shutdown the server
- ▶️ **Start**: Launch a stopped server

**Additional Info**:
- **Contributions**: Shows which extensions contribute to this server
  - Example: "MicroProfile contributes bundles to JDT.LS"
- **Click "Show"** to view contribution diagram

### Right Panel: Console
Shows **LSP traces** for the selected server:

**Trace Format**:
```
→ 10:23:45 [jdtls] textDocument/didOpen
  Request: { uri: "file:///project/Main.java", ... }

← 10:23:46 [jdtls] textDocument/publishDiagnostics
  Response: { diagnostics: [...] }
```

**Legend**:
- `→` = Client → Server (request/notification)
- `←` = Server → Client (response/notification)
- Timestamp, server ID, method name, and JSON payload

**Search**: Press `Ctrl+F` to search traces

---

## Tab 2: Servers

### Purpose
Global view of all LSP servers across all workspaces.

### Left Panel: All LSP Servers
Lists **every LSP server** from all workspaces:
- Groups servers by workspace URI
- Shows server status for each
- Useful for overview of entire system

### Console
Same as Workspaces tab - shows LSP traces for selected server.

---

## Tab 3: Debuggers

### Purpose
Monitor Debug Adapter Protocol (DAP) servers for debugging support.

### Left Panel: DAP Servers
Lists all registered debug adapters:
- **vscode-js-debug**: JavaScript/TypeScript debugger
- Future: Python, Go, Rust debuggers...

**Info shown**:
- Server ID
- Display name
- Supported languages (via documentSelector)
- Installation status

### Console
Reserved for future DAP trace display (debug protocol messages).

---

## Tab 4: MCP

### Purpose
Monitor MCP (Model Context Protocol) client connections from AI assistants like Claude Code.

### Left Panel: AI Clients
Lists connected MCP clients:
- **Client name**: "claude-code 2.1.183"
- **Connection ID**: Unique identifier
- **Connected at**: Timestamp

### Right Panel: MCP Traces
Shows **MCP protocol traces**:

```
→ 10:45:12 [claude-code] tools/call
  Tool: workspace_symbol
  Args: { query: "UserService" }

← 10:45:13 [claude-code] tools/result
  Result: [ { name: "UserService", location: ... } ]
```

**Use case**: Debug tool calls from AI assistants, verify parameters, inspect results.

---

## Real-Time Updates (WebSocket)

The Admin UI uses a **single WebSocket connection** for all real-time updates:

**What updates automatically**:
- ✅ New workspace opened → appears in list
- ✅ Server started/stopped → status changes instantly
- ✅ LSP trace received → appears in console immediately
- ✅ MCP client connected → shows in AI Clients list
- ✅ Diagnostic published → trace visible in real-time

**No polling** = No 30-second delays!

**Connection indicator**:
- If WebSocket disconnects, auto-reconnects after 3 seconds
- Console shows "WebSocket connected" in browser DevTools

---

## Visualizing Server Relationships with Contributions Diagram

### Purpose

The **Contributions tab** provides an interactive diagram that visualizes how language servers collaborate and depend on each other. This is crucial for understanding the extension system.

### Access the Diagram

**Method 1**: Via Server Card
1. Go to **Workspaces** tab
2. Select a workspace
3. Find a server with "Contributes to: ..." badge
4. Click **"Show"** button

**Method 2**: Direct Navigation (future)
- Dedicated **Contributions** tab showing all relationships

### Example 1: MicroProfile Ecosystem

**When you click "Show" on MicroProfile server**:

```
┌─────────────────────────────────────────────────────────────┐
│              Server Contribution Diagram                    │
│                                                             │
│                   ┌───────────────┐                         │
│                   │    JDT.LS     │                         │
│                   │  Java LSP     │                         │
│                   │               │                         │
│                   │  • Java AST   │                         │
│                   │  • Classpath  │                         │
│                   │  • Workspace  │                         │
│                   └───────▲───────┘                         │
│                           │                                 │
│            ┌──────────────┼──────────────┐                  │
│            │              │              │                  │
│   ┌────────┴────────┐     │     ┌────────┴────────┐        │
│   │  MicroProfile   │     │     │     Qute        │        │
│   │   Language      │     │     │   Language      │        │
│   │    Server       │     │     │    Server       │        │
│   │                 │     │     │                 │        │
│   │ Contributes:    │     │     │ Contributes:    │        │
│   │ ✓ bundles/*.jar │     │     │ ✓ bundles/*.jar │        │
│   │ ✓ bindRequest   │     │     │ ✓ bindRequest   │        │
│   │   - mp/java/    │     │     │   - qute/java/  │        │
│   │     projectInfo │     │     │     codeLens    │        │
│   │   - mp/java/    │     │     │   - qute/       │        │
│   │     fileInfo    │     │     │     template/*  │        │
│   │ ✓ bindNotif.    │     │     │ ✓ bindNotif.    │        │
│   │   - mp/props    │     │     │   - qute/data   │        │
│   │     Changed     │     │     │     ModelChanged│        │
│   └─────────────────┘     │     └─────────────────┘        │
│                           │                                 │
│                  ┌────────┴────────┐                        │
│                  │   Liberty LS    │                        │
│                  │                 │                        │
│                  │ Contributes:    │                        │
│                  │ ✓ bundles/*.jar │                        │
│                  │ ✓ bindRequest   │                        │
│                  │   - liberty/*   │                        │
│                  └─────────────────┘                        │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

**What this shows**:

✅ **Hub Architecture**: JDT.LS is the central Java analysis engine  
✅ **Multiple Contributors**: MicroProfile, Qute, Liberty all extend JDT.LS  
✅ **Contribution Types**: Visual distinction between bundles, bindRequest, bindNotification  
✅ **Method Names**: See exactly which custom methods are delegated  
✅ **Dependency Chain**: Understand why stopping JDT.LS affects all others  

### Example 2: XML Ecosystem (LemMinX)

**When you click "Show" on Liberty LS LemMinX extension**:

```
┌─────────────────────────────────────────────────────────────┐
│              Server Contribution Diagram                    │
│                                                             │
│                   ┌───────────────┐                         │
│                   │   LemMinX     │                         │
│                   │   XML LSP     │                         │
│                   │               │                         │
│                   │  • XML Schema │                         │
│                   │  • XSD/DTD    │                         │
│                   │  • Validation │                         │
│                   └───────▲───────┘                         │
│                           │                                 │
│                           │                                 │
│                  ┌────────┴────────┐                        │
│                  │  Liberty LS     │                        │
│                  │    LemMinX      │                        │
│                  │                 │                        │
│                  │ Contributes:    │                        │
│                  │ ✓ liberty-ls    │                        │
│                  │   -lemminx.jar  │                        │
│                  │                 │                        │
│                  │ Provides:       │                        │
│                  │ • server.xml    │                        │
│                  │   validation    │                        │
│                  │ • Liberty       │                        │
│                  │   features      │                        │
│                  │ • Custom XSD    │                        │
│                  └─────────────────┘                        │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

**What this shows**:

✅ **Classpath Contribution**: Liberty LS adds its JAR to LemMinX classpath  
✅ **Domain Extension**: LemMinX (generic XML) + Liberty (Liberty-specific)  
✅ **No bindRequest**: Pure JAR contribution (no custom protocol methods)  
✅ **Simpler Relationship**: Single contributor, single target  

### Example 3: Complete Project View

**When you open a full MicroProfile + Qute project**:

```
┌─────────────────────────────────────────────────────────────┐
│           Complete Workspace Dependencies                   │
│                                                             │
│                   ┌───────────────┐                         │
│                   │    JDT.LS     │ ◄─── Core Java engine   │
│                   └───────▲───────┘                         │
│                           │                                 │
│         ┌─────────────────┼─────────────────┐               │
│         │                 │                 │               │
│    ┌────┴────┐       ┌────┴────┐      ┌────┴────┐          │
│    │MicroPro │       │  Qute   │      │ Liberty │          │
│    │ file LS │       │   LS    │      │   LS    │          │
│    └─────────┘       └────┬────┘      └─────────┘          │
│                           │                                 │
│                      ┌────┴────┐                            │
│                      │ LemMinX │ ◄─── XML engine            │
│                      │XML LSP  │                            │
│                      └────▲────┘                            │
│                           │                                 │
│                      ┌────┴────┐                            │
│                      │Liberty  │                            │
│                      │LemMinX  │                            │
│                      └─────────┘                            │
│                                                             │
│  Legend:                                                    │
│  ▲ = contributes to (bundles + bindRequest)                │
│  ◄─── = role description                                   │
└─────────────────────────────────────────────────────────────┘
```

**What this shows**:

✅ **Full Architecture**: All servers and their relationships  
✅ **Two Engines**: JDT.LS (Java) and LemMinX (XML)  
✅ **Multiple Layers**: Extensions on extensions (Liberty LS → LemMinX)  
✅ **Startup Order**: Must start JDT.LS before MicroProfile/Qute/Liberty  

### Interpreting the Diagram

#### Visual Elements

| Element | Meaning |
|---------|---------|
| `┌─────┐`<br>`│ Box │`<br>`└─────┘` | Language Server |
| `▲` or `│` | Contribution arrow (points to target) |
| `✓ bundles/*.jar` | JAR files added to target's classpath |
| `✓ bindRequest` | Custom requests delegated to target |
| `✓ bindNotification` | Custom notifications sent to target |
| Multiple arrows to same box | Multiple servers contribute to one target |

#### Color Coding (in actual UI)

- 🟦 **Blue boxes**: Core engines (JDT.LS, LemMinX)
- 🟩 **Green boxes**: Contributing extensions
- 🔵 **Blue arrows**: Bundles contribution
- 🟢 **Green arrows**: bindRequest/bindNotification

### Practical Use Cases

#### Use Case 1: Debugging Missing Features

**Problem**: MicroProfile diagnostics not working

**Solution via Diagram**:
1. Open diagram for MicroProfile
2. See it contributes to JDT.LS
3. Check if JDT.LS is running (🟢)
4. Check if bundles loaded
5. Check LSP traces for bindRequest calls

#### Use Case 2: Understanding Startup Failures

**Problem**: Qute server fails to start

**Solution via Diagram**:
1. Open diagram for Qute
2. See dependency on JDT.LS
3. Verify JDT.LS started first
4. Check JDT.LS accepts Qute's bundle contributions
5. Review startup order in server configs

#### Use Case 3: Planning New Extensions

**Problem**: Want to add Quarkus support

**Solution via Diagram**:
1. Study MicroProfile diagram (similar to Quarkus)
2. Identify needed contributions:
   - Bundles for Quarkus annotations
   - bindRequest for Quarkus-specific Java analysis
   - bindNotification for config changes
3. Create `server.json` with same pattern

### Interactive Features (Future)

🔮 **Click on arrows** to see contribution details  
🔮 **Hover over boxes** to see server status and PID  
🔮 **Filter by contribution type** (bundles only, bindRequest only)  
🔮 **Export diagram** as PNG or SVG  
🔮 **Zoom and pan** for complex projects  

### Why This Matters

**Before the diagram**:
- ❌ Server relationships were invisible
- ❌ Had to read JSON configs manually
- ❌ Debugging contribution issues was guesswork

**With the diagram**:
- ✅ **Visual understanding** of architecture at a glance
- ✅ **Debug faster** by seeing the dependency chain
- ✅ **Plan extensions** by copying proven patterns
- ✅ **Onboard developers** with visual documentation
- ✅ **No reading code** to understand server collaboration

### Real-World Insight

This diagram shows something **unique to MCP ADE (Agent Development Environment)**:

Most LSP platforms (VS Code, Eclipse, IntelliJ) treat language servers as **isolated silos**. Each server operates independently.

**MCP ADE (Agent Development Environment)** enables **server collaboration**:
- MicroProfile doesn't re-implement Java parsing
- Qute reuses JDT.LS's Java analysis
- Liberty LS extends LemMinX without forking it

**The diagram makes this collaboration visible** - that's the innovation! 🚀

---

## Complete Example: Validating MicroProfile Annotations

### Scenario
You have a Java file in a MicroProfile project and ask Claude to validate MicroProfile annotations.

### Claude Interaction

**You ask Claude:**
```
Can you check for MicroProfile validation errors in UserService.java?
```

**Claude responds:**
```
I found 1 validation issue in UserService.java:

❌ Line 4: @ConfigProperty is missing 'defaultValue' attribute
   The @ConfigProperty annotation requires either a defaultValue or 
   the property must be present in microprofile-config.properties.

Suggested fix:
@ConfigProperty(name = "app.user.limit", defaultValue = "100")
int userLimit;
```

### Behind the Scenes (Admin UI View)

**File**: `src/main/java/com/example/UserService.java`
```java
@ApplicationScoped
public class UserService {
    
    @ConfigProperty(name = "app.user.limit")  // Missing defaultValue
    int userLimit;
    
    @Inject
    UserRepository repository;
}
```

### What Happens Behind the Scenes

When you open this file or ask Claude to validate it:

#### Step 1: MicroProfile LS Receives Request
**In Admin UI → Workspaces → Select workspace → MicroProfile**

Console shows:
```
→ 10:30:15 [microprofile] textDocument/didOpen
  Request: {
    uri: "file:///project/src/main/java/com/example/UserService.java",
    languageId: "java",
    text: "..."
  }
```

#### Step 2: MicroProfile Delegates to JDT.LS (bindRequest)
**In Admin UI → Same console**

```
→ 10:30:15 [microprofile] microprofile/java/projectInfo
  Routing to: jdtls (mode: executeCommand)
  Request: {
    uri: "file:///project/src/main/java/com/example/UserService.java"
  }

← 10:30:16 [jdtls] microprofile/java/projectInfo  
  Response: {
    projectName: "my-microprofile-app",
    dependencies: ["microprofile-config-api-3.0", ...],
    javaVersion: "17"
  }
```

💡 **This is the bind mechanism in action!** MicroProfile's `server.json` declares:
```json
{
  "contributes": {
    "jdtls": {
      "bindRequest": ["microprofile/java/projectInfo"]
    }
  }
}
```

#### Step 3: MicroProfile Analyzes Annotations
**In Admin UI → Console**

```
→ 10:30:16 [microprofile] microprofile/java/fileInfo
  Routing to: jdtls (mode: executeCommand)
  Request: {
    uri: "file:///...",
    documentFormat: "Markdown"
  }

← 10:30:17 [jdtls] microprofile/java/fileInfo
  Response: {
    fields: [
      {
        name: "userLimit",
        annotations: ["ConfigProperty"],
        type: "int"
      }
    ]
  }
```

#### Step 4: MicroProfile Publishes Diagnostics
**In Admin UI → Console**

```
← 10:30:17 [microprofile] textDocument/publishDiagnostics
  Notification: {
    uri: "file:///project/src/main/java/com/example/UserService.java",
    diagnostics: [
      {
        range: { line: 4, character: 4 },
        severity: 1,
        message: "@ConfigProperty is missing 'defaultValue' attribute",
        source: "microprofile-config"
      }
    ]
  }
```

#### Step 5: Claude Receives Results (MCP Trace)
**In Admin UI → MCP tab → Select claude-code**

```
→ 10:30:18 [claude-code] tools/call
  Tool: diagnostics
  Args: {
    uri: "file:///project/src/main/java/com/example/UserService.java"
  }

← 10:30:18 [claude-code] tools/result
  Result: {
    diagnostics: [
      {
        message: "@ConfigProperty is missing 'defaultValue' attribute",
        severity: "error",
        line: 4
      }
    ]
  }
```

### What You See in Admin UI

**Workspaces Tab**:
- 🟢 MicroProfile server running
- 🟢 JDT.LS server running
- "MicroProfile contributes to jdtls" badge

**Console (MicroProfile selected)**:
- Full request chain visible
- See bindRequest routing to JDT.LS
- See diagnostic generation

**MCP Tab**:
- Claude's tool call
- Diagnostic results returned to AI

**Contributions Diagram** (click "Show"):
```
┌─────────────┐
│   JDT.LS    │ ← Provides Java analysis
└──────▲──────┘
       │
       │ bindRequest: microprofile/java/projectInfo
       │              microprofile/java/fileInfo
       │
┌──────┴──────┐
│ MicroProfile│ ← Validates MicroProfile annotations
└─────────────┘
```

### Visual Walkthrough in Admin UI

#### View 1: Servers Tab - All Servers Running

**Navigate to**: Admin UI → **Servers** tab

**What you see**:
```
┌─────────────────────────────────────────────────────────────┐
│  [Workspaces] [Servers] [Debuggers] [MCP]                   │
├───────────────┬─────────────────────────────────────────────┤
│ All LSP       │                                             │
│ Servers       │         LSP Server Traces                   │
├───────────────┤                                             │
│ Workspace:    │  → 10:30:15 [microprofile] textDocument/... │
│ file:///my-   │  → 10:30:15 [microprofile] microprofile/... │
│ microprofile- │  ← 10:30:16 [jdtls] microprofile/java/...   │
│ app           │  ← 10:30:17 [microprofile] textDocument/... │
│               │                                             │
│ 🟢 JDT.LS     │  [Clear Console]          Search: [______] │
│   PID: 12345  │                                             │
│   [Restart]   │                                             │
│               │                                             │
│ 🟢 MicroProfile│                                            │
│   PID: 12346  │                                             │
│   [Restart]   │                                             │
│               │                                             │
│ 🟢 Qute       │                                             │
│   PID: 12347  │                                             │
│   [Restart]   │                                             │
└───────────────┴─────────────────────────────────────────────┘
```

**Key observations**:
- ✅ All 3 language servers are **running** (green indicators)
- ✅ Each has a **PID** (process is alive)
- ✅ **Restart buttons** available for each

#### View 2: LSP Traces - Request Flow

**Click on**: MicroProfile server

**Console shows complete LSP communication**:
```
→ 10:30:15.234 [microprofile] textDocument/didOpen
  {
    "uri": "file:///project/src/main/java/com/example/UserService.java",
    "languageId": "java",
    "version": 1,
    "text": "@ApplicationScoped\npublic class UserService {...}"
  }

→ 10:30:15.456 [microprofile] microprofile/java/projectInfo
  Routing to: jdtls (mode: executeCommand)
  {
    "uri": "file:///project/src/main/java/com/example/UserService.java"
  }

← 10:30:16.123 [jdtls] microprofile/java/projectInfo
  {
    "projectName": "my-microprofile-app",
    "dependencies": [
      "microprofile-config-api-3.0",
      "jakarta.enterprise.cdi-api-3.0"
    ],
    "javaVersion": "17"
  }

→ 10:30:16.234 [microprofile] microprofile/java/fileInfo
  Routing to: jdtls (mode: executeCommand)
  {
    "uri": "file:///project/src/main/java/com/example/UserService.java",
    "documentFormat": "Markdown"
  }

← 10:30:17.001 [jdtls] microprofile/java/fileInfo
  {
    "fields": [
      {
        "name": "userLimit",
        "annotations": ["ConfigProperty"],
        "type": "int"
      }
    ]
  }

← 10:30:17.234 [microprofile] textDocument/publishDiagnostics
  {
    "uri": "file:///project/src/main/java/com/example/UserService.java",
    "diagnostics": [
      {
        "range": { "start": { "line": 4, "character": 4 }, ... },
        "severity": 1,
        "message": "@ConfigProperty is missing 'defaultValue' attribute",
        "source": "microprofile-config"
      }
    ]
  }
```

**What this shows**:
- 🔍 **Complete request/response chain**
- 🔗 **Bind mechanism in action** (microprofile → jdtls routing)
- ⏱️ **Timestamps** for performance analysis
- 📋 **Full JSON payloads** for debugging

#### View 3: MCP Tab - Claude's Tool Calls

**Navigate to**: Admin UI → **MCP** tab → Select **claude-code**

**MCP Traces console shows**:
```
┌─────────────────────────────────────────────────────────────┐
│  [Workspaces] [Servers] [Debuggers] [MCP]                   │
├───────────────┬─────────────────────────────────────────────┤
│ AI Clients    │                                             │
│               │         MCP Traces                          │
│ 🤖 claude-code│                                             │
│   2.1.183     │  → 10:30:18.100 tools/call                  │
│   Connected:  │    Tool: diagnostics                        │
│   10:30:10    │    Args: {                                  │
│               │      "uri": "file:///.../UserService.java"  │
│               │    }                                         │
│               │                                             │
│               │  ← 10:30:18.456 tools/result                │
│               │    Result: {                                │
│               │      "diagnostics": [                       │
│               │        {                                    │
│               │          "message": "@ConfigProperty is...",│
│               │          "severity": "error",               │
│               │          "line": 4,                         │
│               │          "source": "microprofile-config"    │
│               │        }                                    │
│               │      ]                                      │
│               │    }                                        │
└───────────────┴─────────────────────────────────────────────┘
```

**What this shows**:
- 🤖 **Claude Code** is connected
- 🔧 **Tool called**: `diagnostics` (MCP tool)
- 📥 **Arguments**: The file URI
- 📤 **Results**: The diagnostic from MicroProfile
- ⚡ **Real-time**: See exactly what Claude sees

#### View 4: Contributions Diagram

**Navigate to**: Workspaces → Select workspace → MicroProfile → Click **"Show"** next to "Contributes to: jdtls"

**Diagram appears**:
```
┌─────────────────────────────────────────────────┐
│          Server Contribution Diagram            │
├─────────────────────────────────────────────────┤
│                                                 │
│              ┌─────────────┐                    │
│              │   JDT.LS    │                    │
│              │  (Java)     │                    │
│              └──────▲──────┘                    │
│                     │                           │
│                     │                           │
│        ┌────────────┴────────────┐              │
│        │                         │              │
│        │  Contributes:           │              │
│        │  • bundles/*.jar        │              │
│        │  • bindRequest          │              │
│        │    - microprofile/java/ │              │
│        │      projectInfo        │              │
│        │    - microprofile/java/ │              │
│        │      fileInfo           │              │
│        │                         │              │
│ ┌──────┴──────┐                  │              │
│ │ MicroProfile│                  │              │
│ │  (Config)   │                  │              │
│ └─────────────┘                  │              │
│                                                 │
└─────────────────────────────────────────────────┘
```

**What this shows**:
- 🔗 **Relationship**: MicroProfile depends on JDT.LS
- 📦 **Bundles**: JAR files contributed to JDT.LS classpath
- 🔀 **bindRequest**: Custom methods routed from MicroProfile to JDT.LS

### Key Insights from Admin UI

✅ **Server Collaboration**: MicroProfile doesn't analyze Java itself - it delegates to JDT.LS  
✅ **Bind Mechanism**: Custom requests routed via `bindRequest` configuration  
✅ **Real-time Visibility**: See every step of the diagnostic process  
✅ **MCP Integration**: Watch Claude consume the diagnostics via MCP tools  
✅ **No Black Box**: Complete transparency into language server interactions  
✅ **Three Views**: Servers running → LSP traces → MCP traces → Complete story  

### The Complete Flow Visualized

```
┌──────────────┐
│    You       │  "Check UserService.java for errors"
└──────┬───────┘
       │
       ▼
┌──────────────┐
│   Claude     │  Calls MCP tool: diagnostics()
│   Code       │  (visible in MCP tab)
└──────┬───────┘
       │
       ▼
┌──────────────┐
│ MCP Language │  Calls LSP: textDocument/didOpen
│    Tools     │  (visible in Servers tab)
└──────┬───────┘
       │
       ▼
┌──────────────┐
│ MicroProfile │  Delegates: microprofile/java/projectInfo
│     LS       │  (bindRequest → JDT.LS)
└──────┬───────┘
       │
       ▼
┌──────────────┐
│   JDT.LS     │  Returns: Java project info
│              │  (visible in LSP traces)
└──────┬───────┘
       │
       ▼
┌──────────────┐
│ MicroProfile │  Analyzes & publishes diagnostics
│     LS       │  (textDocument/publishDiagnostics)
└──────┬───────┘
       │
       ▼
┌──────────────┐
│   Claude     │  Formats response for you
│   Code       │  "@ConfigProperty missing defaultValue"
└──────────────┘
```

**Every step is visible in the Admin UI** - that's the power of transparency! 🔍

---

## Common Workflows

### 1. Debug LSP Communication Issue

1. Go to **Workspaces** tab
2. Select the workspace
3. Select the problematic server (e.g., "JDT.LS")
4. Watch **LSP Traces** in console
5. Trigger the operation in your IDE
6. See the request/response in real-time
7. Use **Ctrl+F** to search for specific methods

### 2. Check Server Status

1. Go to **Servers** tab (global view)
2. Scan all servers across workspaces
3. Look for 🔴 red status indicators
4. Click server to see traces and errors

### 3. Monitor AI Tool Calls

1. Go to **MCP** tab
2. Select your AI client (e.g., "claude-code")
3. Watch **MCP Traces** to see:
   - Which tools Claude is calling
   - What parameters it's passing
   - What results it receives

### 4. Restart Problematic Server

1. Go to **Workspaces** tab
2. Select the workspace
3. Find the stuck server
4. Click **🔄 Restart**
5. Watch it stop and start in real-time

### 6. Auto-Install New Language Server

**Scenario**: First time opening a Qute template file

**What happens**:
1. MCP ADE (Agent Development Environment) detects `.qute.html` file
2. Checks if Qute server is installed
3. **Automatically downloads** Qute server if missing
4. **Admin UI shows progress**:
   ```
   🔵 Qute - Installing...
      Downloading com.redhat.qute.ls-uber.jar
      Progress: 45% (2.3 MB / 5.1 MB)
   ```
5. **Installation completes**:
   ```
   🟢 Qute - Running
      PID: 12348
   ```
6. Server is **ready to use** - no manual setup!

**Check installation status**:
- Go to **Servers** or **Workspaces** tab
- Look for server status:
  - 🔵 **Installing**: Download in progress
  - 🟢 **Running**: Installed and started
  - 🔴 **Error**: Installation failed
- Click on server to see installation logs

### 7. Manual Server Control

**Start a stopped server**:
1. Find server with 🔴 **Stopped** status
2. Click **▶️ Start** button
3. Watch status change: 🔴 → 🔵 Starting → 🟢 Running

**Stop a running server**:
1. Find server with 🟢 **Running** status
2. Click **🛑 Stop** button
3. Watch status change: 🟢 → 🔴 Stopped
4. PID disappears

**Restart a server** (equivalent to Stop → Start):
1. Click **🔄 Restart** button
2. Watch full cycle: 🟢 → 🔴 Stopped → 🔵 Starting → 🟢 Running
3. New PID assigned

**Use cases**:
- 💡 **Free memory**: Stop unused servers
- 💡 **Apply updates**: Restart after updating server JAR
- 💡 **Clear state**: Restart to clear caches
- 💡 **Debug issues**: Stop/start to reproduce bugs

### 5. View Server Contributions

1. Select a contributing server (e.g., MicroProfile)
2. Look for "Contributes to: jdtls"
3. Click **Show** to see diagram
4. Understand bundles, bindRequest, bindNotification relationships

---

## Keyboard Shortcuts

| Shortcut | Action |
|----------|--------|
| `Ctrl+F` / `Cmd+F` | Search in console traces |
| `Esc` | Close search |
| `↑` / `↓` | Navigate search results |

---

## Technical Details

### WebSocket Endpoint
```
ws://localhost:7654/api/admin/ws
```

### Message Types
- `lsp-trace`: LSP request/response/notification
- `mcp-trace`: MCP tool call trace
- `workspaces-update`: Workspace list changed
- `mcp-clients-update`: MCP client connected/disconnected
- `server-status-changed`: Server started/stopped

### Browser Support
- ✅ Chrome/Edge (recommended)
- ✅ Firefox
- ✅ Safari
- Requires modern browser with WebSocket support

---

## Troubleshooting

### Admin UI doesn't load
- Check server is running: `http://localhost:7654/admin`
- Verify port 7654 is not blocked by firewall
- Check console for errors (F12 → Console tab)

### Traces not appearing
- Check WebSocket connection in Network tab (F12)
- Look for "WebSocket connected" in console
- Refresh the page to reconnect

### Old data showing
- WebSocket automatically refreshes data
- If stale, refresh browser (F5)
- Check for WebSocket reconnection

### Multiple tabs open
- Each tab creates its own WebSocket
- All tabs receive updates independently
- No connection limit issues (WebSocket doesn't count toward HTTP/1.1 limit)

---

## Tips & Best Practices

💡 **Keep Admin UI open** while developing for instant feedback  
💡 **Use search (Ctrl+F)** to filter noisy traces  
💡 **Open multiple browser tabs** to compare different servers  
💡 **Watch contributions** to understand server dependencies  
💡 **Check MCP traces** to see what AI assistants are doing  
💡 **Restart servers** instead of restarting entire application  

---

---

## Experimental Features

### Multiple Language Clients (In Progress)

**Vision**: Connect MCP ADE (Agent Development Environment) to language servers already running in VS Code or other IDEs.

#### Current Architecture
```
┌──────────────┐
│  Claude Code │ (MCP client)
└──────┬───────┘
       │
       ▼
┌──────────────┐
│MCP Language  │
│    Tools     │
└──────┬───────┘
       │ starts & manages
       ▼
┌──────────────┐
│   JDT.LS     │ (LSP server - spawned by MCP)
└──────────────┘
```

#### Experimental: Multi-Client Architecture
```
┌──────────────┐          ┌──────────────┐
│  VS Code     │          │  Claude Code │
└──────┬───────┘          └──────┬───────┘
       │                         │
       │ LSP connection 1        │ LSP connection 2
       │                         │
       ▼                         ▼
┌─────────────────────────────────────────┐
│            JDT.LS                       │
│   (Single instance, multiple clients)   │
└─────────────────────────────────────────┘
```

**Benefits**:
- ✅ **No duplicate processes**: Reuse VS Code's already-running JDT.LS
- ✅ **Shared state**: Same AST, same classpath, same cache
- ✅ **Memory efficient**: One JDT.LS instead of two
- ✅ **IDE integration**: Claude sees same view as VS Code

**Admin UI Impact**:
- 🔍 **Connection count**: Shows "2 clients connected" on server card
- 🔍 **Client list**: Displays "VS Code" + "Claude Code"
- 🔍 **Trace separation**: Filter traces by client
- 🔍 **Status**: Shows "Managed by external IDE" badge

**Current Status**: 🚧 Experimental - Testing with VS Code integration

---

### DAP (Debug Adapter Protocol) Support

**Vision**: Enable AI-assisted debugging by exposing debug adapters via MCP tools.

#### Debuggers Tab (Already Visible)

The **Debuggers** tab already shows registered debug adapters:
- 🐛 **vscode-js-debug**: JavaScript/TypeScript debugging
- 🔜 **Python debugpy**: Python debugging (planned)
- 🔜 **Go Delve**: Go debugging (planned)

#### Architecture

```
┌──────────────┐
│  Claude Code │  "Set breakpoint at line 42"
└──────┬───────┘
       │ MCP tool: create_debug_session()
       ▼
┌──────────────┐
│MCP Language  │
│    Tools     │  DapSessionManager
└──────┬───────┘
       │ DAP protocol
       ▼
┌──────────────┐
│vscode-js-    │  Debug adapter for JS/TS
│   debug      │
└──────┬───────┘
       │ controls
       ▼
┌──────────────┐
│  Node.js     │  Your running program
│  Process     │
└──────────────┘
```

#### Available MCP Tools (20 total)

**Session Management**:
- `create_debug_session(language, sessionName)`
- `list_debug_sessions()`
- `close_debug_session(sessionId)`
- `list_supported_languages()`

**Breakpoints**:
- `set_breakpoint(sessionId, file, line, condition)`
- `remove_breakpoint(sessionId, breakpointId)`
- `list_all_breakpoints(sessionId)`

**Execution Control**:
- `start_debugging(sessionId, scriptPath)`
- `attach_to_process(sessionId, processId)`
- `continue_execution(sessionId)`
- `pause_execution(sessionId)`
- `step_over(sessionId)`
- `step_in(sessionId)`
- `step_out(sessionId)`

**Inspection**:
- `get_stack_trace(sessionId)`
- `list_threads(sessionId)`
- `get_scopes(sessionId, frameId)`
- `get_variables(sessionId, variablesReference)`
- `get_local_variables(sessionId)`
- `evaluate_expression(sessionId, expression)`

#### Example: AI-Assisted Debugging

**You ask Claude**:
```
Debug UserService.java - set a breakpoint at line 42 
and show me the value of userLimit when it hits
```

**Claude's workflow** (via MCP tools):
1. `create_debug_session("javascript", "debug-session-1")`
2. `set_breakpoint(sessionId, "UserService.java", 42)`
3. `start_debugging(sessionId, "src/main/Main.java")`
4. *(breakpoint hits)*
5. `get_local_variables(sessionId)`
6. `evaluate_expression(sessionId, "userLimit")`

**Claude responds**:
```
✅ Breakpoint set at UserService.java:42
🔍 When breakpoint hit:
   userLimit = 0
   
This explains the bug - userLimit is not initialized!
The @ConfigProperty default value is missing.
```

#### Admin UI - DAP Features (In Development)

**Current**:
- ✅ Debuggers tab shows registered adapters
- ✅ Lists supported languages
- ✅ Shows installation status

**In Progress**:
- 🚧 **DAP Traces**: Show debug protocol messages
  ```
  → 14:23:45 [vscode-js-debug] initialize
  ← 14:23:45 [vscode-js-debug] initialized
  → 14:23:46 [vscode-js-debug] setBreakpoints
    { source: "UserService.java", breakpoints: [{line: 42}] }
  ← 14:23:46 [vscode-js-debug] breakpoint verified
  → 14:23:47 [vscode-js-debug] launch
  ← 14:23:50 [vscode-js-debug] stopped (breakpoint hit)
  ```

- 🚧 **Debug Sessions Panel**: Show active debugging sessions
  ```
  Active Debug Sessions:
  
  🐛 debug-session-1 (JavaScript)
     Status: PAUSED at UserService.java:42
     Thread: main (id: 1)
     PID: 15432
     [Continue] [Step Over] [Step In] [Stop]
  ```

- 🚧 **Breakpoints Panel**: Visual list of all breakpoints
  ```
  Breakpoints:
  
  ✓ UserService.java:42
    Condition: userLimit > 100
    Hit count: 3
    [Remove]
  
  ✓ Main.java:15
    [Remove]
  ```

#### Current Status

**What works today**:
- ✅ DAP infrastructure (DapServer, DapSession, DapSessionManager)
- ✅ 20 MCP tools for debug control
- ✅ vscode-js-debug integration configured
- ✅ Debuggers tab in Admin UI

**What's in progress**:
- 🚧 DAP trace display in console
- 🚧 Active sessions monitoring
- 🚧 Breakpoint visualization
- 🚧 End-to-end testing with real debug scenarios

**Example use case being tested**:
```
File: examples/javascript/buggy-app.js

You: "Debug this and find why the loop never exits"

Claude: 
1. Creates debug session
2. Sets breakpoint in loop
3. Runs the code
4. Inspects variable at each iteration
5. Finds: counter is string "1" not number 1
6. Reports: "Bug found - counter++ does string concatenation"
```

---

## Future Enhancements

🔮 **DAP trace display** for debug sessions *(in progress)*  
🔮 **Performance metrics** (request latency, message counts)  
🔮 **Trace filtering** by method, status, timestamp  
🔮 **Export traces** to JSON for analysis  
🔮 **Dark mode** toggle  
🔮 **Trace persistence** across page refreshes  
🔮 **Multi-client view** showing all connected IDEs  
🔮 **Debug session timeline** visualization  

---

## Related Documentation

- [Server Configuration](./SERVER_CONFIGURATION.md)
- [Bind Mechanism](./BIND_MECHANISM.md)
- [Extension System](./EXTENSIONS.md)
- [MCP Tools](./MCP_TOOLS.md)
