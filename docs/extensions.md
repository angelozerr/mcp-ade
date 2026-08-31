# Extension Guide

An **extension** groups one or more LSP servers and/or DAP servers under a single language identifier (e.g., "java", "python"). This page explains how to add your own servers to MCP ADE (Agent Development Environment).

## Adding Servers at Runtime

The simplest way to add a server is at runtime, without writing any code.

### Via MCP tools (AI assistant)

Ask your AI assistant:

```
Add a new LSP server for Ruby using solargraph
```

The assistant will use the `add_lsp_server` or `add_extension` tools to register the server.

Available tools:
- `add_extension` — Create a new extension
- `add_lsp_server` — Add an LSP server to an extension
- `add_dap_server` — Add a DAP server to an extension
- `get_extension_schemas` — Get the JSON schemas for server.json and installer.json

### Via Admin UI

Open `http://localhost:7654/admin` and use the extensions management interface to add, configure, enable, or disable servers.

### Via file system

Create a directory under `~/.mcp-languagetools/extensions/`:

```
~/.mcp-languagetools/extensions/
  ruby/
    lsp/
      solargraph/
        server.json
        installer.json    (optional)
```

The server will be discovered on next startup.

## Server Descriptors

### server.json

Declares a language server or debug adapter:

```json
{
  "id": "pyright",
  "name": "Pyright (Python Language Server)",
  "description": "Python language server based on Pyright",
  "url": "https://github.com/microsoft/pyright",
  "documentSelector": [
    { "language": "python" }
  ]
}
```

Key fields:
- **id**: Unique server identifier
- **name**: Display name
- **documentSelector**: Which files this server handles (by language, file pattern, or scheme)

### installer.json (optional)

Defines how to auto-install the server:

```json
{
  "id": "pyright",
  "name": "Pyright (Python Language Server)",
  "check": {
    "fileExists": {
      "name": "Check if Pyright is installed",
      "file": "${serverHome}/node_modules/.bin/pyright-langserver*"
    }
  },
  "run": {
    "exec": {
      "name": "Install Pyright",
      "workingDir": "${serverHome}/node_modules",
      "command": {
        "windows": "npm.cmd install pyright --force",
        "default": "npm install pyright --force"
      },
      "onSuccess": {
        "configureServer": {
          "name": "Configure Pyright command",
          "command": {
            "windows": "${serverHome}/node_modules/.bin/pyright-langserver.cmd --stdio",
            "default": "${serverHome}/node_modules/.bin/pyright-langserver --stdio"
          }
        }
      }
    }
  }
}
```

The installer:
1. **Checks** if the server is already installed (`check` section)
2. **Runs** the installation command if not found (`run` section)
3. **Configures** the server command after installation (`onSuccess.configureServer`)

`${serverHome}` is automatically resolved to the server's installation directory. See [Variables Reference](variables.md) for all available variables.

## Creating a Bundled Extension Module

For packaging servers as part of the MCP ADE (Agent Development Environment) build, create a Maven module under `extensions/`.

### Directory structure

```
extensions/ruby/
  pom.xml
  src/main/resources/
    mcp-extension.json
    lsp/
      solargraph/
        server.json
        installer.json
    dap/
      ruby-debug/
        server.json
        installer.json
```

### mcp-extension.json

A simple file at the resource root that declares the extension id:

```json
{"id": "ruby"}
```

This groups all LSP and DAP servers under this directory into the "ruby" extension.

### pom.xml

```xml
<project>
    <parent>
        <groupId>com.ibm.mcp</groupId>
        <artifactId>mcp-ade-parent</artifactId>
        <version>0.1.0-SNAPSHOT</version>
        <relativePath>../../pom.xml</relativePath>
    </parent>
    <artifactId>ruby-extension</artifactId>
    <name>Ruby Extension</name>
</project>
```

Then add the module to the root `pom.xml` and as a dependency in `dev/pom.xml`.

## Extension Management Tools

| Tool | Description |
|------|-------------|
| `list_extensions` | List all installed extensions with their servers |
| `add_extension` | Add a new extension |
| `remove_extension` | Remove an extension and its servers |
| `enable_extension` / `disable_extension` | Enable or disable an extension |
| `enable_lsp_server` / `disable_lsp_server` | Enable or disable a single LSP server |
| `enable_dap_server` / `disable_dap_server` | Enable or disable a single DAP server |
| `get_extension_schemas` | Get JSON schemas for server.json and installer.json |

## Next Steps

- **[Bind Mechanism](bind-mechanism.md)** — How servers collaborate (e.g., MicroProfile LS depending on JDT.LS)
- **[Admin UI Guide](admin-ui.md)** — Manage extensions in the web console
