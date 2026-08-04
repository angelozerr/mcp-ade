# File Watcher Design

## Problem

When an AI agent creates/modifies/deletes files on the file system, language servers (JDT.LS, TypeScript, etc.) are not notified. 
This causes issues like `ClassNotFoundException` when debugging because the language server never compiled the new file.

The root cause: mcp-lsp does not implement `workspace/didChangeWatchedFiles` notifications. 
When JDT.LS sends `client/registerCapability` for `workspace/didChangeWatchedFiles`, mcp-lsp ignores it 
(missing case in `LspClientFeatures.registerCapability`).

## Design

### Strategy Pattern

Two strategies depending on whether the workspace is local or remote:

```
FileChangeStrategy
├── FileWatcherStrategy        (local workspace, automatic, configurable)
└── AgentEventStrategy         (remote workspace, MCP tool notify_file_changes)
```

Detection: based on workspace URI scheme (`file://` = local, other = remote).

### FileWatcherManager (1 per workspace)

Central manager with pub/sub pattern:

```
FileWatcherManager
├── patterns from registerCapability (dynamic, based on project classpath)
├── patterns from server.json (static, optional)
├── merge/dedup patterns across servers
├── ref counting for server start/stop lifecycle
└── dispatch events to subscribed servers
```

#### Pattern Sources

1. **Dynamic (registerCapability)** - Language server registers file watchers at runtime via `client/registerCapability` 
   for `workspace/didChangeWatchedFiles`. For Java/JDT.LS, patterns are based on the actual project classpath 
   (not hardcoded `src/main/java`).

2. **Static (server.json)** - Optional fallback patterns per server:
   ```json
   {
     "fileWatchers": [
       { "globPattern": "**/*.java" },
       { "globPattern": "**/pom.xml" }
     ]
   }
   ```

#### Pattern Merging

- N language servers may request overlapping patterns
- One watcher per unique pattern, N subscribers
- Example:
  ```
  **/*.java  -> [JDT.LS, MicroProfile, Jakarta]  (3 refs)
  **/pom.xml -> [JDT.LS]                         (1 ref)
  ```
- Deduplication: `src/main/**/*.java` is already covered by `**/*.java` -> no new watcher

#### Server Lifecycle

- Server starts -> register patterns -> create missing watchers
- Server stops -> unregister patterns -> remove watchers with 0 refs
- No orphan watchers, no leaks

### File Watcher Library

Use **`io.methvin:directory-watcher`** for local file watching:
- Recursive watching (handles directory creation/deletion automatically)
- Event-based (uses OS native APIs: inotify on Linux, ReadDirectoryChangesW on Windows)
- No polling, minimal overhead

### MCP Tool: refresh_workspace

Explicit action triggered by the AI agent or admin UI — equivalent to F5/Refresh in Eclipse IDE.
No automatic refresh on server restart (consistent with `skipProjectConfiguration: true` philosophy).

- Two-level refresh:
  1. **LSP standard**: sends `workspace/didChangeWatchedFiles` to all language servers
  2. **Server-specific**: calls custom refresh commands per extension (e.g., `mcp.jdtls.refreshProject` 
     which does `project.refreshLocal()` in the MCP-JDTLS plugin)
- Extensible: each extension can contribute its own refresh hook via an extension point
- `workspace/didChangeWatchedFiles` alone is NOT sufficient for JDT.LS because 
  the Eclipse workspace model is separate from the file system. JDT.LS may ignore 
  `didChangeWatchedFiles` for files not yet in its workspace model.
- Use cases:
  - Watchers enabled: agent doesn't need to call it, but can
  - Watchers disabled: agent calls it after creating/modifying files
  - Remote: primary mechanism alongside `notify_file_changes`
  - After server restart with `skipProjectConfiguration: true`

### Server Stop/Restart Behavior

- **mcp-lsp running, language server stopped**: file watcher queues events, replays on server restart
- **Full restart (mcp-lsp stopped)**: NO automatic refresh. Agent or admin calls `refresh_workspace` if needed.
- **Future (if measured fast enough)**: optional auto-refresh on server restart, configurable via settings:
  ```json
  {
    "fileWatchers": {
      "autoRefreshOnRestart": false
    }
  }
  ```

### Settings

Global settings in `settings.json`:
```json
{
  "fileWatchers": {
    "enabled": true,
    "excludePatterns": "dist,out,.cache"
  }
}
```

Per-workspace override (future):
- Workspace settings override global settings
- Admin UI shows active value with provenance:
  ```
  File Watchers: disabled (workspace: my-project)
                  global: enabled
  ```

## Implementation Tasks

### Phase 1: Generic Refresh Mechanism (priority) ✓

- [x] Extension point for server-specific refresh logic (`LspServer.refreshWorkspace()`)
- [x] JDT.LS extension: `mcp.jdtls.refreshProject` command (does `project.refreshLocal()`)
- [x] MCP tool `refresh_workspace` — agent can call it after creating/modifying files
- [x] Admin UI: refresh button per workspace
- [x] `LspServer.sendDidChangeWatchedFiles()` method
- [x] Generic: works for all language servers, not Java-specific

### Phase 2: File Watcher Core ✓

- [x] Add `workspace/didChangeWatchedFiles` case in `LspClientFeatures.registerCapability`
- [x] Store registered file watcher patterns per server
- [x] `WorkspaceFileWatcher` with recursive directory watching and event batching
- [x] Pattern matching (glob) to filter events per server
- [x] Auto-detect local vs remote workspace (file:// scheme)
- [x] Event queuing when server is stopped, replay on restart

### Phase 3: Local File Watcher ✓

- [x] `WorkspaceFileWatcher` using Java NIO WatchService (no external dependency)
- [x] Recursive directory watching with excluded dirs (.git, node_modules, target, etc.)
- [x] `fileWatchers.enabled` setting in settings.json (global, default: false)

### Phase 4: Agent Events (remote) ✓

- [x] `notify_file_changes` MCP tool (works for both local and remote workspaces)
- [x] `Workspace.notifyFileChanges()` for programmatic notification

### Phase 5: Admin UI & Settings ✓

- [x] File watcher status badge in workspace header (watching/idle)
- [x] Refresh button per workspace
- [x] REST API for toggle file watcher (`POST /workspaces/{uri}/file-watcher`)
- [x] REST API for workspace refresh (`POST /workspaces/{uri}/refresh`)
- [x] `WorkspaceDTO` includes `fileWatcherEnabled` and `fileWatcherRunning`
- [ ] Per-workspace settings override (future)

### Phase 6: Enhancements ✓

- [x] Exclude patterns in settings (`fileWatchers.excludePatterns` comma-separated directory names)
- [x] Static patterns in server.json `fileWatchers` (e.g., `[{"globPattern": "**/*.java"}]`)
- [x] Custom exclude patterns passed to `WorkspaceFileWatcher` via constructor
- [x] `filterByPatterns` merges static (server.json) + dynamic (registerCapability) patterns

### Phase 7: Documentation & Presentation ✓

- [x] Update project documentation with file watcher mechanism (getting-started.md)
- [x] Document `refresh_workspace` and `notify_file_changes` tool usage for AI agents
- [x] Document settings (`fileWatchers.enabled`, `fileWatchers.excludePatterns`, future `autoRefreshOnRestart`)
- [x] Update presentation slides (slide 21: File Watcher — Keeping Language Servers in Sync)

## Important: Server Restart Does NOT Fix the Issue

With `skipProjectConfiguration: true` and `maven.import.enabled: false`, restarting JDT.LS 
does NOT rescan the file system. It loads its persisted workspace state from the `-data` folder.
A file created while JDT.LS was stopped (or running) will NOT be discovered on restart.

This was verified in practice: creating a Java file, restarting the mcp server, 
and launching debug still resulted in `ClassNotFoundException`.

The file watcher / `refresh_workspace` mechanism is necessary even across server restarts.

## Fix: isFullBuild

Separate from the file watcher design, `JavaDebugServer.buildWorkspace()` was changed 
from `isFullBuild: false` to `isFullBuild: true` to ensure newly discovered files are compiled 
during a full build. This is necessary but not sufficient without proper file change notifications.
