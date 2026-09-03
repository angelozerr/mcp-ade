# Variables Reference

MCP ADE (Agent Development Environment) supports variable substitution in `server.json` commands, `installer.json` templates, and DAP launch configurations.

## Syntax

All variables use the `${name}` syntax:

```
${serverDist}/bin/lemminx --stdio
```

Prefixed variables use `${prefix:name}`:

```
${vscodeExtension:jetbrains.intellij-server}/server/bin/intellij-server
```

## Directory Structure

```
<mcpHome>/                                  ← ${mcpHome}
  extensions/
    <extensionId>/                          ← ${extensionHome}
      lsp/
        <serverId>/                         ← ${serverHome}
          server.json
          installer.json
          dist/                             ← ${serverDist}
            bin/
            lib/
            node_modules/
      dap/
        <serverId>/                         ← ${serverHome}
          server.json
          installer.json
          dist/                             ← ${serverDist}
            ...
  workspace-storage/
    <serverId>/
      <workspaceName>-<hash>/               ← ${workspaceStorageDir}
```

## Built-in Variables

| Variable | Description | Example path | Available in |
|---|---|---|---|
| `${mcpHome}` | Root directory of MCP ADE | `~/.mcp-ade` | installer |
| `${extensionHome}` | Extension directory | `~/.mcp-ade/extensions/c` | installer |
| `${serverHome}` | Server configuration directory (contains `server.json`, `installer.json`) | `~/.mcp-ade/extensions/c/lsp/clangd` | command, installer |
| `${serverDist}` | Server distribution directory (installed binaries, libraries, modules) | `~/.mcp-ade/extensions/c/lsp/clangd/dist` | command, installer |
| `${userHome}` | User's home directory | `~` | command, installer |
| `${workspaceFolder}` | Current project/workspace directory | `/home/user/my-project` | installer, DAP launch config |
| `${workspaceRoot}` | Deprecated alias for `${workspaceFolder}` | | DAP launch config |
| `${workspaceStorageDir}` | Per-server, per-workspace data directory | `~/.mcp-ade/workspace-storage/jdtls/my-project-12345` | command |
| `${vscodeExtension:id}` | Path to a VS Code extension directory | `~/.vscode/extensions/jetbrains.intellij-server-1.0.0` | command |
| `${port}` | Auto-allocated TCP port for the DAP server | `12345` | DAP command |
| `${address}` | Extracted address from server output | `127.0.0.1` | DAP readyPattern |
| `${dist.file}` | Relative path to the main file within `${serverDist}` (set by the download task) | `bin/clangd` | installer onSuccess |

## Examples

### server.json command

```json
{
  "command": {
    "windows": "${serverDist}/bin/lemminx.exe --stdio",
    "default": "${serverDist}/bin/lemminx --stdio"
  }
}
```

### installer.json — download with configureServer

```json
{
  "check": {
    "fileExists": {
      "name": "Check server",
      "file": "${serverDist}/bin/jdtls"
    }
  },
  "run": {
    "download": {
      "url": "https://example.com/server.tar.gz",
      "output": {
        "file": {
          "name": { "windows": "bin/jdtls.bat", "default": "bin/jdtls" },
          "executable": true
        }
      },
      "onSuccess": {
        "configureServer": {
          "command": "\"${serverDist}/${dist.file}\" -configuration \"${mcpHome}/.cache/jdtls\""
        }
      }
    }
  }
}
```

### installer.json — npm install

```json
{
  "check": {
    "fileExists": {
      "name": "Check if server is installed",
      "file": {
        "windows": "${serverDist}/node_modules/.bin/typescript-language-server.cmd",
        "default": "${serverDist}/node_modules/.bin/typescript-language-server"
      }
    }
  },
  "run": {
    "exec": {
      "name": "Install server",
      "command": "npm install --prefix ${serverDist} typescript-language-server typescript",
      "workingDir": "${serverDist}/node_modules"
    }
  }
}
```

### DAP launch config with ${port}

```json
{
  "launch": {
    "default": "${serverDist}/dlv dap --listen=127.0.0.1:${port}"
  },
  "debugServerReadyPattern": "DAP server listening at: ${address}:${port}"
}
```

### VS Code extension reference with server data directory

```json
{
  "command": {
    "default": "${vscodeExtension:jetbrains.intellij-server}/server/bin/intellij-server --stdio --system-path \"${workspaceStorageDir}\""
  }
}
```

## Custom Variable Resolvers (SPI)

You can add custom variables by implementing the `VariableResolver` SPI:

```java
package com.example;

import org.eclipse.mcp.ade.variable.VariableExpression;
import org.eclipse.mcp.ade.variable.VariableContext;
import org.eclipse.mcp.ade.variable.VariableResolver;

public class MyVariableResolver implements VariableResolver {

    @Override
    public String resolve(VariableExpression expression, VariableContext context) {
        if ("myTool".equals(expression.prefix())) {
            // Handle ${myTool:configPath}
            return findMyToolPath(expression.name());
        }
        return null; // not handled by this resolver
    }
}
```

Register it in `META-INF/services/org.eclipse.mcp.ade.variable.VariableResolver`:

```
com.example.MyVariableResolver
```
