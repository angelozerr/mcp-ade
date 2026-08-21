# Build Support Modes: Native, Fast and BSP

MCP Language Tools supports 3 build support modes for JDT.LS, independently
configurable for Maven and Gradle via settings:

```
lsp.jdtls.settings.maven.buildSupport = native | fast
lsp.jdtls.settings.gradle.buildSupport = native | fast | bsp
```

## Overview

| | Native | Fast | BSP |
|---|---|---|---|
| Build tool | Maven / Gradle | Maven / Gradle | Gradle only |
| Import | M2E / Buildship (inside JDT.LS) | External CLI (`mvn dependency:build-classpath`) | Build Server Protocol |
| Scope | Entire workspace | One module on demand | One module on demand |
| Disk cache | No | Yes (`~/.mcp-languagetools/classpath-cache/`) | Yes |
| 1st launch time (Quarkus) | 1-2 hours | ~150s | ~60s |
| 2nd launch time | ~5 min | ~20s | ~15s |
| Best suited for | Interactive human IDE | AI agent / targeted operation | AI agent / targeted operation |

## Native Mode

The default mode. JDT.LS uses its built-in importers:
- **Maven**: M2E (`MavenProjectImporter`, order=400)
- **Gradle**: Buildship (`GradleProjectImporter`, order=300)

### How it works

```
JDT.LS startup
───────────────
1. initialize → M2E/Buildship scans the workspace
2. Imports ALL modules (every pom.xml / build.gradle found)
3. Full Maven/Gradle resolution for each module
4. Builds all projects
5. JDT indexes all classes
```

### Why it's slow

On a project like Quarkus (~1400 modules), M2E creates an Eclipse project for
each `pom.xml` and runs `readMavenProject()` on every one. It's an **exhaustive**
import — all or nothing. There's no "single module on demand" mode.

## Fast Mode

Fast mode inverts the approach: **no import at startup, everything on demand**.

### Principle

Classpath extraction is performed **outside JDT.LS** by the MCP server:
- **Maven**: `mvn dependency:build-classpath` (external process)
- **Gradle**: same principle via Gradle APIs

The result is written as **JSON descriptor files** into the JDT.LS data
directory (`<dataDir>/mcp-classpath/<projectName>.json`). Two JDT.LS
extension points consume these descriptors:

- **McpProjectImporter** (`IProjectImporter`, order=10) — runs during
  `initialize`, before M2E (400) and Buildship (300). When descriptors
  exist, blocks native importers via `isResolved()=true`. Creates Eclipse
  IProject resources with Java nature and configures builders.
- **McpBuildSupport** (`IBuildSupport`, order=50) — reads the JSON descriptor
  and calls `setRawClasspath()` to configure source roots, JRE container,
  project references, and library JARs.

### Flow: 1st launch (cold start)

```
initialize (60s)                         After ServiceReady
──────────────────                       ────────────────────
1. McpProjectImporter activates          1. First tool triggers ensureModuleSetup()
2. No descriptors → skip                2. Classpath extraction via Maven CLI (~90s)
3. M2E/Gradle disabled via settings     3. Write JSON descriptors (reactor + main)
4. JDT.LS starts with no projects       4. Call java.project.import (~50ms)
                                         5. McpProjectImporter creates projects
                                         6. Save to disk cache
                                         7. Module ready → tools available
```

### Flow: 2nd launch (warm cache)

```
Before initialize                        After ServiceReady
──────────────────                       ────────────────────
1. Cache valid → write descriptors       1. ensureModuleSetup() detects cache + descriptor
2. Create mcp-classpath/ directory       2. Skip java.project.import (nothing changed)
                                         3. Module ready in ~150ms
initialize (10s)
──────────────────
1. McpProjectImporter finds descriptors
2. Creates projects + classpath
3. Indexing starts immediately
```

### JSON Descriptor

Each file at `<dataDir>/mcp-classpath/<projectName>.json` contains:

```json
{
  "projectName": "quarkus-awt",
  "projectPath": "C:/Users/.../quarkus/extensions/awt/runtime",
  "sourceRoots": ["src/main/java", "src/test/java"],
  "classpathJars": ["~/.m2/repository/.../quarkus-core-999-SNAPSHOT.jar", ...],
  "projectReferences": ["arc", "quarkus-core"],
  "disableBuilders": false
}
```

- **projectReferences**: reactor module project names (intra-workspace dependencies)
- **disableBuilders**: `true` for reactor modules (source-only projects — no
  JDT diagnostics or compilation, avoids noise on non-targeted modules)

### Two-pass import

McpProjectImporter uses a two-pass approach to handle project references:

1. **Pass 1**: create/open all projects with Java nature
2. **Pass 2**: configure builders and classpath (`setRawClasspath`)

This ordering ensures project references (reactor dependencies) are resolvable
during classpath configuration, regardless of descriptor processing order.

### Classpath cache

The disk cache (`~/.mcp-languagetools/classpath-cache/`) stores:
- The full `ClasspathInfo` (source roots, JARs, reactor deps)
- Build file timestamps (pom.xml, build.gradle)

Automatic invalidation when:
- A pom.xml/build.gradle has changed (different timestamp)
- A referenced JAR no longer exists (e.g., `~/.m2/repository` cleaned)

### `force=false` optimization

`McpBuildSupport.update(project, force=false, monitor)` compares the current
classpath with the new one. If identical, `setRawClasspath()` is skipped —
avoids re-indexing on repeated `java.project.import` calls (e.g., during
sibling module preloading).

## BSP Mode (Build Server Protocol)

BSP mode is a variant of fast mode, specific to Gradle. Instead of extracting
the classpath via an external CLI process, it uses the **Build Server Protocol**
to communicate with a Gradle BSP server.

### Differences from Fast

| | Fast (Maven CLI) | BSP (Gradle) |
|---|---|---|
| Extraction | External `mvn` process | Persistent BSP connection |
| Communication | Stdout (temp file) | JSON-RPC (LSP-like) |
| Latency | New process each time | Persistent server, fast requests |
| Build tool | Maven and Gradle | Gradle only |

### How it works

```
1. MCP server starts the Gradle BSP server (if not already running)
2. BSP request buildTarget/dependencySources for the target module
3. Response → ClasspathInfo
4. Writes the same JSON descriptor format as fast mode
5. McpProjectImporter/McpBuildSupport process descriptors identically
```

The BSP server stays alive between requests, making subsequent extractions
much faster than spawning a new Maven CLI process.

### Shared architecture

All 3 modes converge to the same entry point on the JDT.LS side:

```
                    ┌─────────────────┐
                    │  McpBuildSupport │ ← IBuildSupport (order=50)
                    │  setRawClasspath │
                    └────────┬────────┘
                             │ reads
                    ┌────────┴────────┐
                    │  JSON           │ ← <dataDir>/mcp-classpath/*.json
                    │  Descriptor     │
                    └────────┬────────┘
                             │ written by
              ┌──────────────┼──────────────┐
              │              │              │
     ┌────────┴───────┐ ┌───┴────┐ ┌───────┴──────┐
     │ Maven CLI      │ │ Gradle │ │ Gradle BSP   │
     │ (fast mode)    │ │ (fast) │ │ (bsp mode)   │
     │ dependency:    │ │        │ │ buildTarget/  │
     │ build-classpath│ │        │ │ dependencies  │
     └────────────────┘ └────────┘ └──────────────┘
```

McpProjectImporter and McpBuildSupport are **build-tool agnostic** — they
only read JSON. Build-tool-specific logic lives in the MCP server extensions
(`extensions/java`), through the `BuildSupport` interface (synchronous, for
Maven/Gradle CLI) and `BspBuildSupport` interface (asynchronous, for BSP).

## Why this works for an AI agent

An AI agent works fundamentally differently from a developer in an IDE:

- **Developer**: opens a workspace, freely navigates between files → needs
  **everything** indexed and ready
- **AI agent**: receives a specific file path, executes an operation → needs
  **a single module** ready

Fast/BSP mode exploits this difference: instead of preparing the entire
workspace "just in case", it prepares only what's requested, when it's
requested. With sibling preloading, neighboring modules are configured in
the background after the first tool call.

## Performance summary

Measurements on Quarkus (`extensions/awt/runtime`, simple module, 43 JARs):

| Step | Native (M2E) | Fast 1st | Fast 2nd |
|---|---|---|---|
| `initialize` | 60s | 61s | **10s** |
| Import/Resolution | 300-600s | 91s (Maven CLI) | **150ms** (cache) |
| Project setup | included | **48ms** (`java.project.import`) | skip |
| `validateLaunchConfig` | 0s (all ready) | 96ms (indexing in progress) | **6s** |
| **Total** | **1-2 hours** | **~152s** | **~21s** |

The 7x gain between 1st and 2nd launch comes primarily from the classpath
cache (0s Maven) and project setup during `initialize` (McpProjectImporter
reads descriptors written from cache before JDT.LS starts).
