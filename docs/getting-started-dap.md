# Getting Started with Debugging (DAP)

MCP ADE (Agent Development Environment) provides debugging capabilities through the Debug Adapter Protocol (DAP). Your AI assistant can set breakpoints, step through code, inspect variables, and evaluate expressions — just like an IDE debugger.

## Prerequisites

- MCP ADE (Agent Development Environment) is running (see [Getting Started](getting-started.md))
- Your MCP client is connected

## Example: Finding a Bug the AI Can't See by Reading Code

This example shows why DAP tools matter: the code looks correct everywhere, but the program produces impossible results. Only the debugger reveals what's actually happening at runtime.

### The buggy code

You have a caching system. Entries are stored and retrieved by key, and keys can be upgraded to a new version. But after upgrading, entries become unreachable — even though they're still in the cache:

**File**: `src/main/java/org/acme/CacheSystem.java`
```java
package org.acme;

import java.util.*;

public class CacheSystem {

    static class CacheKey {
        String prefix;
        int version;

        CacheKey(String prefix, int version) {
            this.prefix = prefix;
            this.version = version;
        }

        @Override
        public int hashCode() { return Objects.hash(prefix, version); }

        @Override
        public boolean equals(Object o) {
            return o instanceof CacheKey other
                && prefix.equals(other.prefix) && version == other.version;
        }
    }

    static Map<CacheKey, String> cache = new HashMap<>();

    static void store(CacheKey key, String value) { cache.put(key, value); }
    static String retrieve(CacheKey key) { return cache.get(key); }
    static void upgradeVersion(CacheKey key) { key.version++; }

    public static void main(String[] args) {
        CacheKey key = new CacheKey("user", 1);
        store(key, "Alice");

        System.out.println("Before: " + retrieve(key));  // "Alice" ✓

        upgradeVersion(key);

        System.out.println("After:  " + retrieve(key));             // null ?!
        System.out.println("New lookup: " + retrieve(
                new CacheKey("user", 2)));                           // null ?!
        System.out.println("Cache size: " + cache.size());           // 1 — it's still there!
        System.out.println("Values: " + cache.values());             // [Alice] — it IS there!
    }
}
```

The entry is **in the cache** (`size=1`, `values=[Alice]`) but **can't be retrieved**. The code looks correct: `hashCode` and `equals` are properly implemented, `store` and `retrieve` are straightforward.

### Step 1: The AI is puzzled

**You type**:
```
After calling upgradeVersion, retrieve returns null but the entry is still in the cache. Why?
```

Reading the code, the AI sees correct `hashCode`/`equals`, correct `put`/`get` calls. It may suggest checking if `retrieve` uses the right key, or if there's a concurrency issue. The code looks fine everywhere — there's no obviously wrong line.

### Step 2: Debug with DAP to see the invisible

**You type**:
```
Can you debug this? Set a breakpoint before and after upgradeVersion to inspect the cache state.
```

The assistant uses DAP tools:

1. **`start_debugging`** — Launches with breakpoints:

```json
{
  "debuggerId": "java-debug",
  "configuration": {
    "type": "java",
    "request": "launch",
    "mainClass": "org.acme.CacheSystem",
    "cwd": "/path/to/project"
  },
  "breakpoints": [
    { "file": "src/main/java/org/acme/CacheSystem.java", "line": 33 },
    { "file": "src/main/java/org/acme/CacheSystem.java", "line": 37 }
  ]
}
```

2. **Before `upgradeVersion`** — `get_local_variables` + `evaluate_expression`:
   - `key.version = 1`, `key.hashCode() = 3599307`
   - `retrieve(key)` → `"Alice"` ✓

3. **After `upgradeVersion`** — same inspections:
   - `key.version = 2`, `key.hashCode() = 3599308` — **the hash changed!**
   - `cache.size() = 1` — the entry is still there
   - But it was stored under hash `3599307` — it's now in the **wrong bucket**

4. **`evaluate_expression`**: `new CacheKey("user", 2).hashCode()` → `3599308`. This is the correct hash for version 2, but the entry was stored with version 1's hash. The `HashMap` looks in bucket `3599308` and finds nothing.

### The root cause

`upgradeVersion()` mutates a key that's **already stored in a HashMap**. Since `HashMap` uses the hash computed at insertion time to choose the bucket, changing the key's fields makes the entry unreachable: the old hash points to the old bucket, but the key now computes a different hash.

### The fix

Never mutate a key after inserting it. Remove and re-insert instead:

```java
static void upgradeVersion(CacheKey key) {
    String value = cache.remove(key);  // remove with current hash
    key.version++;
    if (value != null) {
        cache.put(key, value);         // re-insert with new hash
    }
}
```

**The debugger revealed** the invisible: by evaluating `hashCode()` before and after the mutation, the assistant saw that the hash changed while the bucket stayed the same — something impossible to discover by reading code alone.

## Available DAP Tools

### Session management

| Tool | What it does |
|------|-------------|
| `list_debug_adapters` | Shows available debuggers for a file |
| `get_debug_templates` | Gets launch/attach configuration templates |
| `start_debugging` | Launches or attaches a debug session |
| `close_debug_session` | Stops a debug session |
| `list_debug_sessions` | Lists active sessions |

### Breakpoints

| Tool | What it does |
|------|-------------|
| `set_breakpoint` | Set a breakpoint (with optional condition like `x > 10`) |
| `remove_breakpoint` | Remove a breakpoint |
| `list_all_breakpoints` | List all breakpoints in a session |

### Execution control

| Tool | What it does |
|------|-------------|
| `continue_execution` | Resume after a breakpoint |
| `pause_execution` | Pause the running program |
| `step_over` | Execute the current line |
| `step_in` | Step into a function call |
| `step_out` | Step out of the current function |

### Inspection

| Tool | What it does |
|------|-------------|
| `get_stack_trace` | View the call stack |
| `get_local_variables` | See variables in the current frame |
| `get_variables` | Expand an object or scope |
| `get_scopes` | Get variable scopes for a stack frame |
| `evaluate_expression` | Evaluate any expression (e.g., `x + y`) |
| `get_console_output` | Read program stdout/stderr |
| `list_threads` | List program threads |

## Supported Languages

Debug adapters are available for:

| Language | Debug Adapter |
|----------|--------------|
| Java | java-debug |
| JavaScript/TypeScript | vscode-js-debug |
| Python | debugpy |
| Go | go-delve |
| C/C++ | codelldb (LLDB) |
| Dart | dart-debug |
| PHP | vscode-php-debug |
| Dockerfile | buildx-dockerfile |

All debug adapters are **auto-installed** on first use.

## Monitor in Admin UI

Open `http://localhost:7654/admin` and click the **Debuggers** tab to:

- See all available debug adapters
- Monitor active debug sessions
- View debug session state (running, paused, stopped)

## Next Steps

- **[Extension Guide](extensions.md)** — Add your own debug adapters
- **[Admin UI Guide](admin-ui.md)** — Monitor and manage servers
