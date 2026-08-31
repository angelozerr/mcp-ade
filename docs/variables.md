# Variables Reference

MCP ADE (Agent Development Environment) supports variable substitution in `server.json` commands, `installer.json` templates, and DAP launch configurations.

## Syntax

All variables use the `${name}` syntax:

```
${serverHome}/bin/lemminx --stdio
```

Prefixed variables use `${prefix:name}`:

```
${vscodeExtension:jetbrains.intellij-server}/server/bin/intellij-server
```

## Built-in Variables

| Variable | Description | Available in |
|---|---|---|
| `${serverHome}` | Root installation directory for the server | command, installer |
| `${userHome}` | User's home directory | command, installer |
| `${mcpHome}` | Root directory of the MCP ADE (Agent Development Environment) installation | installer |
| `${workspaceFolder}` | Current project/workspace directory | installer, DAP launch config |
| `${workspaceRoot}` | Deprecated alias for `${workspaceFolder}` | DAP launch config |
| `${vscodeExtension:id}` | Path to a VS Code extension directory | command |
| `${port}` | Auto-allocated TCP port for the DAP server | DAP command |
| `${address}` | Extracted address from server output | DAP readyPattern |
| `${output.dir}` | Output directory from the download step | installer onSuccess |
| `${output.file.name}` | Output filename from the download step | installer onSuccess |

## Examples

### server.json command

```json
{
  "command": {
    "windows": "${serverHome}/bin/lemminx.exe --stdio",
    "default": "${serverHome}/bin/lemminx --stdio"
  }
}
```

### installer.json

```json
{
  "check": {
    "fileExists": {
      "name": "Check server",
      "file": "${serverHome}/bin/jdtls"
    }
  },
  "run": {
    "download": {
      "url": "https://example.com/server.tar.gz",
      "output": { "dir": "${serverHome}" },
      "onSuccess": {
        "configureServer": {
          "command": "\"${serverHome}/${output.file.name}\" -configuration \"${mcpHome}/.cache/jdtls\""
        }
      }
    }
  }
}
```

### DAP launch config with ${port}

```json
{
  "launch": {
    "default": "${serverHome}/dlv dap --listen=127.0.0.1:${port}"
  },
  "debugServerReadyPattern": "DAP server listening at: ${address}:${port}"
}
```

### VS Code extension reference

```json
{
  "command": {
    "default": "${vscodeExtension:jetbrains.intellij-server}/server/bin/intellij-server --stdio"
  }
}
```

## Custom Variable Resolvers (SPI)

You can add custom variables by implementing the `VariableResolver` SPI:

```java
package com.example;

import variable.org.eclipse.mcp.ade.VariableExpression;
import variable.org.eclipse.mcp.ade.VariableContext;
import variable.org.eclipse.mcp.ade.VariableResolver;

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

Register it in `META-INF/services/com.ibm.mcp.languagetools.variable.VariableResolver`:

```
com.example.MyVariableResolver
```
