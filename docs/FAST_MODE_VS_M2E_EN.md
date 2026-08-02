# Fast Mode vs M2E: Why Fast Mode is Much Faster

## The Problem with M2E (Full Mode)

When JDT.LS starts in classic mode on a multi-module Maven project (e.g., Quarkus ~1400 modules), M2E performs the following steps:

1. **Workspace scan**: traverses the entire file tree to find `pom.xml` files
2. **Import ALL modules**: creates an Eclipse project for each `pom.xml` (~1400 projects)
3. **Maven configuration for each project**: runs `MavenProjectFacade.readMavenProject()` for each module, triggering a full Maven resolution (POM parsing, dependency resolution, downloads if needed)
4. **Build all projects**: compiles all modules, resolves classpaths, indexes
5. **JDT indexing**: indexes all classes from all JARs across all modules

**Total time: 1-2 hours** on a project like Quarkus (~1400 modules), with high memory consumption (OOM risk).

### Why M2E Cannot Be Configured to Be as Fast

M2E is designed as an **exhaustive** import: it needs to understand the entire workspace to function. Its configuration options don't fundamentally change this approach:

| M2E Option | What it does | What it doesn't do |
|---|---|---|
| `java.import.maven.enabled=false` | Prevents import of **new** projects | Doesn't prevent processing of projects **already** in the workspace |
| `java.import.exclusions=["**"]` | Excludes folders from scanning | Hack: also prevents legitimate projects from being found |
| `java.autobuild.enabled=false` | Disables auto-build | M2E still runs Maven configuration for each project |
| `skipProjectConfiguration=true` | Skips configuration at init | Good, but doesn't solve the residual projects problem |

The fundamental issue: **M2E doesn't support "single module on demand" mode**. It's all-or-nothing:
- Either it imports everything (slow but complete)
- Or it imports nothing (fast but useless)

Even with `skipProjectConfiguration=true`, projects from a previous run that remain in the `-data` directory are still processed by M2E on restart ("Updating X configuration"), adding ~40s of overhead.

## The Fast Mode Approach

Fast mode takes the opposite approach: **no import at startup, everything on demand**.

### Architecture

```
Startup (fast mode)                      First tool call (e.g., diagnostics on arc-processor)
───────────────────                      ────────────────────────────────────────────────────
1. Clean residual projects (<1s)         1. Module detection (pom.xml) (<1ms)
2. skipProjectConfiguration=true         2. Maven classpath extraction (~30s 1st run, 0s cache)
3. M2E/Gradle import disabled            3. Reactor module setup as source projects (~2s)
4. JDT.LS starts in ~5s                  4. Target module setup (setupProject) (~1s)
5. ServiceReady immediate                5. Module ready → tools available
```

> **No build needed**: all MCP tools use the JDT index or `ASTParser` directly.
> Navigation/search tools rely on the index (populated by `setRawClasspath`).
> Diagnostic tools (`diagnoseAndFix`, etc.) compute errors via `ASTParser` with bindings.
> Debug (java-debug) handles its own build cycle separately.

### Time Comparison

| Step | M2E (full) | Fast Mode (1st run) | Fast Mode (cache) |
|---|---|---|---|
| JDT.LS startup | 5s | 5s | 5s |
| Import/Scan | 300-600s (all modules) | 0s (no scan) | 0s |
| Maven configuration | 600-3600s (all modules) | 30s (1 targeted module) | 0s (from cache) |
| Project setup | included above | 3s (module + reactor deps) | 3s |
| **Total** | **1-2 hours** | **~38s** | **~8s** |

### Why This Works for AI Agents

An AI agent (Claude, etc.) works fundamentally differently from a human developer in an IDE:

- **A developer** opens a workspace and freely navigates between files → needs **everything** indexed and ready
- **An AI agent** receives a specific file path and executes an operation on it → needs only **one module** ready

Fast mode exploits this difference: instead of preparing the entire workspace "just in case", it prepares only what is requested, when it is requested.

## Key Optimizations

### 1. Residual Project Cleanup

The Eclipse `-data` directory persists across restarts. Projects created during a previous run remain present. Even with `skipProjectConfiguration=true`, M2E detects these existing projects and "updates" them (~40s overhead).

**Solution**: clean `.metadata/.plugins/org.eclipse.core.resources/.projects/` and `.snap` before starting JDT.LS in fast mode. Result: 0 projects at startup = 0s M2E overhead.

JDT indexes (in `.metadata/.plugins/org.eclipse.jdt.core/`) are preserved — no need to re-index JARs.

### 2. `skipProjectConfiguration=true`

Native JDT.LS capability (verified in `ProjectsManager.java` line 116) that prevents scanning and importing new projects at startup.

### 3. Disk-based Classpath Cache

In `fast+cache` mode, the classpath extracted from Maven is saved to disk. On next startup:
- No Maven call (0s instead of 30s)
- The project still needs to be created in the workspace (setupProject ~3s)

### 4. Reactor Modules as Source Projects

Intra-workspace dependencies (Maven reactor modules) are created as JDT source projects (no builders, no Maven resolution). This enables cross-module navigation and type resolution at no cost.

## Summary

| | M2E (full) | Fast Mode |
|---|---|---|
| Philosophy | Prepare everything upfront | Prepare on demand |
| Suited for | Interactive human IDE | AI agent / targeted operation |
| Projects created at startup | All (~1400) | 0 |
| Projects created on use | 0 | 1 + its reactor deps (~7) |
| OOM risk | High (large projects) | Low |
| Time to first tool | 0s (everything ready) | ~38s (on-demand setup) |
| Total startup → ready | 1-2 hours | 5s (ServiceReady) |
