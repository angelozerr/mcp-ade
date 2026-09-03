# Getting Started with JetBrains IntelliJ Language Server

Integrate the JetBrains IntelliJ Language Server into MCP ADE (Agent Development Environment) as an alternative to Eclipse JDT.LS for Java and Kotlin validation and debugging.

**Prerequisites**:
- MCP ADE (Agent Development Environment) server running (see [Getting Started](./getting-started.md))
- An MCP client (Claude Desktop, Claude Code, Bob IDE)

## Overview

The IntelliJ extension is **bundled with MCP ADE** but disabled by default because it requires EULA acceptance. Setup involves three steps in the Admin UI:

1. **Disable** the Java extension (JDT.LS) to avoid conflicts
2. **Enable** the IntelliJ extension
3. **Fill in the EULA** acceptance code in the IntelliJ server settings

The IntelliJ Language Server binary is either picked up from your VS Code installation (if the [JetBrains extension](https://marketplace.visualstudio.com/items?itemName=JetBrains.intellij-server) is installed) or **auto-downloaded** on first use.

---

## Step 1: Open the Admin UI

After [starting the MCP ADE server](./getting-started.md#step-1-build--launch-mcp-ade), open **[http://localhost:7654/admin](http://localhost:7654/admin)** and click on the **Extensions** tab.

![Admin UI -- Extensions tab](./images/jetbrains/admin-extensions-tab.png)

---

## Step 2: Disable the Java Extension (JDT.LS)

Both JDT.LS and IntelliJ handle Java files -- disable JDT.LS to avoid conflicts:

1. In the **Extensions** tab, find the **Java** extension
2. Click the **Disable** toggle

![Disable Java/JDT.LS extension](./images/jetbrains/disable-java-extension.png)

> **Tip**: You can re-enable JDT.LS at any time by toggling the extension back on.

---

## Step 3: Enable the IntelliJ Extension

The IntelliJ extension is listed in the Extensions tab but disabled by default.

1. Find the **IntelliJ** extension in the list
2. Click the **Enable** toggle

The extension is now active, but the IntelliJ Language Server cannot start yet -- it requires the EULA acceptance code.

---

## Step 4: Configure the EULA

The JetBrains IntelliJ Language Server requires you to accept the EULA before it can start.

1. In the **Extensions** tab, click on the **IntelliJ** extension to expand its servers
2. Click on the **IntelliJ Language Server** to open the server detail view
3. Go to the **SETTINGS** sub-tab
4. Fill in the **EULA acceptance code** field with your JetBrains EULA acceptance hash
5. Click **Save**

> **How to get the EULA key**: The EULA acceptance hash is obtained from the JetBrains license agreement for the IntelliJ Language Server. Check the [JetBrains IntelliJ Language Server documentation](https://marketplace.visualstudio.com/items?itemName=JetBrains.intellij-server) for details on accepting the EULA.

The setting is persisted under the key `lsp.intellij-server.settings.eula` and passed to the server via the `--eula` command-line argument.

---

## Step 5: Server Installation (Automatic)

MCP ADE auto-installs the IntelliJ Language Server binary using a two-step fallback:

1. **VS Code extension** -- If the [Java and Kotlin by IntelliJ IDEA](https://marketplace.visualstudio.com/items?itemName=JetBrains.intellij-server) VS Code extension is installed, MCP ADE reuses its server binary directly. No additional download needed.

2. **Auto-download** -- If the VS Code extension is not found, MCP ADE automatically downloads the server binary from the JetBrains Open VSX registry on first use. The binary is extracted to the server home directory and configured automatically.

> **Note**: VS Code itself is not required at runtime. The VS Code extension is only one possible source for the server binary.

You can monitor the installation progress in the Admin UI -- the server status shows **Installing** during download, then transitions to **Starting** and **Ready**.

---

## Step 6: Validate a Java File

Use a file with errors, e.g. `src/main/java/org/acme/App.java`:

```java
package org.acme;

public class App {

    public static void main(String[] args) {
        int x = "hello";
        System.out.println(y);
        List<String> list = new ArrayList<>();
    }
}
```

Ask the agent:

```
Could you validate App.java with lsp?
```

> **Important**: Use the keyword **"lsp"** so the agent uses `get_diagnostics` (routed to IntelliJ LS) instead of the `java_*` tools (which require JDT.LS).

Expected result:

```
Diagnostics for: App.java

Language Server: intellij-server (IntelliJ Language Server)
  [Error] Line 6: Incompatible types. Found: 'java.lang.String', required: 'int'
  [Error] Line 7: Cannot resolve symbol 'y'
  [Error] Line 8: Cannot resolve symbol 'List'
  [Error] Line 8: Cannot resolve symbol 'ArrayList'
```

---

## Step 7: Monitor in Admin UI

### Workspaces Tab

1. Select your workspace (e.g., `gradle-java`)
2. The **IntelliJ Language Server** shows its status: **Starting** > **Not Ready** > **Ready**
3. Click on the server to see the **TRACES** tab

![IntelliJ server starting with traces](./images/jetbrains/starting-intellij-server.png)

### MCP Traces

1. Go to the **MCP** tab > **TRACES** sub-tab

![MCP Traces -- tools/call request and response](./images/jetbrains/mcp-traces.png)

The traces show the raw MCP protocol: the `tools/call` request with `get_diagnostics` arguments, progress notifications, and the response with diagnostics.

### MCP Activity

1. Go to the **MCP** tab > **ACTIVITY** sub-tab

![MCP Activity -- get_diagnostics routed to IntelliJ](./images/jetbrains/mcp-audit.png)

The Activity view shows:
- **ARGUMENTS**: `cwd` and `uri` sent by the agent
- **STEPS**: confirms **intellij-server** handled the request via `textDocument/diagnostic`
- **RESULT**: raw diagnostics JSON returned to the agent

---

## Step 8: Enable Verbose Traces (Optional)

For detailed debugging, enable verbose traces for both LSP and MCP.

**LSP traces**:
1. Go to the **SERVERS** tab > select **IntelliJ Language Server** > **SETTINGS** sub-tab
2. Set **Trace Level** to **verbose**

![Set LSP Trace Level to verbose](./images/jetbrains/enable-trace-level-for-lsp.png)

**MCP traces**:
1. Go to the **MCP** tab > **TRACES** sub-tab
2. Set the **Trace Level** combo (top right) to **verbose**

![Set MCP Trace Level to verbose](./images/jetbrains/enable-trace-level-for-mcp.png)

You can also set traces via the workspace `settings.json`:

```json
{
  "lsp.intellij-server.trace": "verbose",
  "dap.intellij-debug.trace": "verbose"
}
```

---

## Debugging with DAP

The IntelliJ extension includes a built-in **debug adapter** (`intellij-debug`) for Java and Kotlin. It uses an embedded approach: the debug session runs inside the IntelliJ Language Server process, with no separate debug adapter to install.

### How It Works

Unlike standalone debug adapters (e.g., java-debug, debugpy), the IntelliJ debug adapter uses the `start_debug_server` launch method. When you start a debug session, MCP ADE:

1. Sends LSP requests to the IntelliJ Language Server to resolve:
   - The class document URI (from the main class name)
   - The classpath and module paths
   - The working directory
   - The Java executable path
2. Asks the IntelliJ Language Server to start a debug server
3. Connects to the debug server and manages the DAP session

### Debug a Java Program

**You type**:
```
Can you debug CacheSystem.java? Set a breakpoint at line 33 to inspect variables.
```

The assistant uses DAP tools:

1. **`start_debugging`** -- Launches with breakpoints:

```json
{
  "debuggerId": "intellij-debug",
  "configuration": {
    "type": "intellij_debugger",
    "request": "launch",
    "mainClass": "org.acme.CacheSystem",
    "cwd": "/path/to/project"
  },
  "breakpoints": [
    { "file": "src/main/java/org/acme/CacheSystem.java", "line": 33 }
  ]
}
```

2. When the breakpoint hits, use inspection tools:
   - **`get_local_variables`** -- See all variables in the current scope
   - **`evaluate_expression`** -- Evaluate any Java expression
   - **`get_stack_trace`** -- View the call stack

3. Control execution:
   - **`continue_execution`** -- Resume after a breakpoint
   - **`step_over`** / **`step_in`** / **`step_out`** -- Step through code

### Available DAP Tools

#### Session management

| Tool | What it does |
|------|-------------|
| `list_debug_adapters` | Shows available debuggers for a file |
| `get_debug_templates` | Gets launch/attach configuration templates |
| `start_debugging` | Launches or attaches a debug session |
| `close_debug_session` | Stops a debug session |
| `list_debug_sessions` | Lists active sessions |

#### Breakpoints

| Tool | What it does |
|------|-------------|
| `set_breakpoint` | Set a breakpoint (with optional condition like `x > 10`) |
| `remove_breakpoint` | Remove a breakpoint |
| `list_all_breakpoints` | List all breakpoints in a session |

#### Execution control

| Tool | What it does |
|------|-------------|
| `continue_execution` | Resume after a breakpoint |
| `pause_execution` | Pause the running program |
| `step_over` | Execute the current line |
| `step_in` | Step into a function call |
| `step_out` | Step out of the current function |

#### Inspection

| Tool | What it does |
|------|-------------|
| `get_stack_trace` | View the call stack |
| `get_local_variables` | See variables in the current frame |
| `get_variables` | Expand an object or scope |
| `get_scopes` | Get variable scopes for a stack frame |
| `evaluate_expression` | Evaluate any expression (e.g., `x + y`) |
| `get_console_output` | Read program stdout/stderr |
| `list_threads` | List program threads |

### Monitor Debug Sessions

Open `http://localhost:7654/admin` and click the **Debuggers** tab to:

- See the **intellij-debug** adapter listed
- Monitor active debug sessions
- View debug session state (running, paused, stopped)
- See DAP traces when verbose trace is enabled

---

## Language Support

The IntelliJ extension handles both **Java** and **Kotlin** files:

| Language | LSP (validation, navigation) | DAP (debugging) |
|----------|------------------------------|-----------------|
| Java     | Yes | Yes |
| Kotlin   | Yes | Yes |

---

## Troubleshooting

### IntelliJ server fails to start

**Check the EULA**:
1. Open Admin UI > Extensions > IntelliJ > IntelliJ Language Server > SETTINGS
2. Verify the **EULA acceptance code** field is filled in
3. The EULA is a required setting -- the server cannot start without it

### First request returns no diagnostics

**Known behavior**: The IntelliJ Language Server takes time to import and index your project on first use.

**Solution**: Check the server status in the Admin UI (Workspaces tab). Wait until the status shows **Ready**, then retry your request. The `intellij/importLog` status notifications show the import progress.

### Both JDT.LS and IntelliJ are running

**Conflict**: Both servers handle Java files and will produce duplicate diagnostics.

**Solution**: Disable one of them in the Extensions tab. Only one Java language server should be active at a time.

### Server binary not found

**Check**:
1. If you have the [JetBrains VS Code extension](https://marketplace.visualstudio.com/items?itemName=JetBrains.intellij-server) installed, verify it is in `~/.vscode/extensions/`
2. If auto-download was used, check the server home directory for the binary
3. Look at the server status in the Admin UI for installation errors

### Debug session fails to start

**Check**:
1. The IntelliJ Language Server must be running and **Ready** before debugging
2. Verify the main class name is correct (fully qualified, e.g., `org.acme.CacheSystem`)
3. Check that the project has been imported and the classpath is resolved

---

## Next Steps

- **[Getting Started (DAP)](./getting-started-dap.md)** -- Advanced debugging examples (HashMap bug, runtime inspection)
- **[Extension Guide](./extensions.md)** -- Extension system in depth
- **[Admin UI Guide](./admin-ui.md)** -- Full Admin UI reference
- **[Variables Reference](./variables.md)** -- All supported variables (`${setting:...}`, `${serverHome}`, etc.)
