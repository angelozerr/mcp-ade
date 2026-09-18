# mcpade CLI

The `mcpade` command-line tool provides admin operations for MCP ADE without going through MCP tools or the admin UI. Any AI agent (Claude Code, Copilot, Cursor) can call `mcpade` alongside `grep`, `git`, and other shell commands.

## Installation

### Build

```bash
./mvnw package -pl cli -DskipTests
```

This creates a fat JAR at `cli/target/mcp-ade-cli-0.1.0-SNAPSHOT.jar`.

### Add to PATH

**Linux/macOS:**
```bash
export PATH="$PATH:/path/to/mcp-languagetools/cli"
```

**Windows:**
Add `C:\path\to\mcp-languagetools\cli` to your system PATH, or run directly:
```cmd
cli\mcpade.cmd
```

## Prerequisites

- Java 17 or higher
- A running MCP ADE server (default: `http://localhost:7654`)

## Usage

```
mcpade <domain> <action> [--key value ...]
```

### Domains

| Domain      | Description                        |
|-------------|------------------------------------|
| `extension` | Manage extensions                  |
| `lsp`       | Manage LSP language servers        |
| `dap`       | Manage DAP debug adapters          |
| `bsp`       | Manage BSP build servers           |

### Global options

| Option         | Description                                     |
|----------------|-------------------------------------------------|
| `--port <n>`   | Server port (default: 7654)                     |
| `--url <url>`  | Server URL (overrides --port, for remote servers)|
| `--json`       | Output in JSON format                           |
| `--help`       | Show help                                       |
| `--version`    | Show version                                    |

## Extension commands

```bash
# List all extensions
mcpade extension list

# Enable an extension
mcpade extension enable --id java

# Disable an extension
mcpade extension disable --id ada

# Add an extension from a folder/ZIP/JAR
mcpade extension add --id my-ext --source /path/to/extension

# Remove a user-installed extension
mcpade extension remove --id my-ext

# Show extension JSON schemas
mcpade extension schemas
```

## LSP server commands

```bash
# List all LSP servers
mcpade lsp list

# Enable an LSP server
mcpade lsp enable --id rust-analyzer

# Disable an LSP server
mcpade lsp disable --id ada-language-server

# Add an LSP server
mcpade lsp add --source /path/to/server

# Remove an LSP server
mcpade lsp remove --id my-server
```

## DAP server commands

```bash
mcpade dap list
mcpade dap enable --id java-debug
mcpade dap disable --id java-debug
mcpade dap add --source /path/to/server
mcpade dap remove --id my-dap
```

## BSP server commands

```bash
mcpade bsp list
mcpade bsp enable --id sbt-bsp
mcpade bsp disable --id sbt-bsp
mcpade bsp add --source /path/to/server
mcpade bsp remove --id my-bsp
```

## Connecting to a remote server

```bash
mcpade --url http://remote-host:7654 extension list
```

## Discovering commands

Run `mcpade` without arguments to see all available commands from the running server:

```bash
mcpade
```

This displays static help plus live commands fetched from the server.

## Admin UI sync

Changes made via `mcpade` are reflected in real-time in the admin UI (`http://localhost:7654/admin`) through WebSocket notifications. No browser refresh needed.

## Architecture

- **CLI module** (`cli/`): standalone Picocli client, no dependency on core or admin
- **Command annotations** (`core/`): `@Command`, `@CommandArg`, `CommandDomain` in `org.eclipse.mcp.ade.command`
- **REST endpoint** (`core/`): `GET /api/commands` (list), `POST /api/commands/{domain}/{action}` (execute)
- **Transport**: HTTP (stdio planned)
