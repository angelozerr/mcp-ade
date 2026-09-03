# installer.json Reference

The `installer.json` file defines how a server or runtime is checked, downloaded, and configured. It is placed alongside `server.json` in the server directory (`${serverHome}`).

All installed files (binaries, libraries, modules) go into `${serverDist}` (`${serverHome}/dist`), keeping configuration files separate from installed content.

## Structure

```json
{
  "id": "my-server-installer",
  "name": "My Server",
  "runtime": "nodejs",
  "properties": {
    "myVar": "${serverDist}/custom/path"
  },
  "check": { ... },
  "run": { ... }
}
```

| Field | Type | Description |
|---|---|---|
| `id` | string | Unique identifier for the installer |
| `name` | string | Display name |
| `runtime` | string | Optional runtime dependency (e.g. `"nodejs"`, `"jdk"`) — installed before the server |
| `properties` | object | Custom variables available via `${key}` in all tasks |
| `check` | task | Task to verify if already installed (runs first) |
| `run` | task | Task to perform the installation (runs if check fails) |
| `env` | object | Environment variables to set for the runtime (e.g. `GOROOT`, `DOTNET_ROOT`) |

## Installation Patterns

Different servers use different installation strategies:

| Pattern | Description | Examples |
|---|---|---|
| [Binary download](#download) | Download and extract a release archive | [clangd](../extensions/c/src/main/resources/lsp/clangd/installer.json), [rust-analyzer](../extensions/rust/src/main/resources/lsp/rust-analyzer/installer.json), [lua-language-server](../extensions/lua/src/main/resources/lsp/lua-language-server/installer.json) |
| [GitHub release](#github-asset-fetcher) | Download from GitHub releases with asset matching | [zls](../extensions/zig/src/main/resources/lsp/zls/installer.json), [texlab](../extensions/latex/src/main/resources/lsp/texlab/installer.json), [marksman](../extensions/markdown/src/main/resources/lsp/marksman/installer.json) |
| [npm install](#exec) | Install via npm | [typescript-language-server](../extensions/javascript/src/main/resources/lsp/typescript-language-server/installer.json), [pyright](../extensions/python/src/main/resources/lsp/pyright/installer.json), [bash-language-server](../extensions/bash/src/main/resources/lsp/bash-language-server/installer.json) |
| [dotnet tool](#exec) | Install via `dotnet tool install` | [fsautocomplete](../extensions/dotnet/src/main/resources/lsp/fsautocomplete/installer.json), [roslyn](../extensions/dotnet/src/main/resources/lsp/roslyn/installer.json) |
| [Resource copy](#copy) | Copy a bundled JAR from classpath | [jakarta](../extensions/jakarta/src/main/resources/lsp/jakarta/installer.json), [microprofile](../extensions/microprofile/src/main/resources/lsp/microprofile/installer.json) |
| [Multi-download](#download) | Multiple chained downloads | [quarkus](../extensions/quarkus/src/main/resources/lsp/quarkus/installer.json), [qute](../extensions/quarkus/src/main/resources/lsp/qute/installer.json) |
| [Runtime download](#download) | Download a runtime (JDK, Node.js, etc.) | [jdk](../extensions/java/src/main/resources/runtime/jdk/installer.json), [nodejs](../extensions/javascript/src/main/resources/runtime/nodejs/installer.json), [go](../extensions/go/src/main/resources/runtime/go/installer.json) |
| [System check only](#exec) | Verify system install, no auto-download | [gopls](../extensions/go/src/main/resources/lsp/gopls/installer.json), [ocaml-lsp](../extensions/ocaml/src/main/resources/lsp/ocaml-lsp/installer.json) |
| [VS Code extension fallback](#task-chaining) | Use VS Code extension, fallback to download | [intellij-server](../extensions/intellij/src/main/resources/lsp/intellij-server/installer.json) |

## Task Types

Tasks are nested objects where the key is the task type. Each task can have:
- `name` — display name for progress reporting
- `onSuccess` — next task to run if this one succeeds
- `onFail` — next task to run if this one fails

### fileExists

Checks if a file exists on disk. Supports glob patterns (`*`, `?`).

```json
{
  "fileExists": {
    "name": "Check if server is installed",
    "file": {
      "windows": "${serverDist}/bin/server.exe",
      "default": "${serverDist}/bin/server"
    }
  }
}
```

### download

Downloads and extracts an archive. The extraction destination is always `${serverDist}`.

```json
{
  "download": {
    "name": "Download server",
    "url": "https://example.com/server-1.0.tar.gz",
    "output": {
      "file": {
        "name": {
          "windows": "bin/server.exe",
          "default": "bin/server"
        },
        "executable": true
      },
      "stripRootDir": true
    },
    "onSuccess": {
      "configureServer": {
        "name": "Configure server",
        "command": "${serverDist}/${dist.file}"
      }
    }
  }
}
```

| Field | Type | Description |
|---|---|---|
| `url` | string | Direct download URL (fallback if asset fetcher fails) |
| `github` | object | GitHub release asset fetcher (see below) |
| `output` | object | Optional output configuration |
| `output.file.name` | string | Relative path to the main file within `${serverDist}` (sets `${dist.file}`) |
| `output.file.executable` | boolean | Set executable permission after extraction |
| `output.stripRootDir` | boolean | Strip the root directory from the archive (useful when archives contain a single top-level folder with a dynamic name) |

#### GitHub asset fetcher

```json
{
  "github": {
    "owner": "clangd",
    "repository": "clangd",
    "prerelease": true,
    "asset": {
      "windows": "clangd-windows*.zip",
      "unix": "clangd-linux*.zip",
      "mac": "clangd-mac*.zip"
    }
  }
}
```

### exec

Executes a shell command.

```json
{
  "exec": {
    "name": "Install via npm",
    "command": "npm install --prefix ${serverDist} typescript-language-server",
    "workingDir": "${serverDist}/node_modules",
    "timeout": 60000,
    "shell": true
  }
}
```

| Field | Type | Default | Description |
|---|---|---|---|
| `command` | string | | Command to execute (supports variable substitution) |
| `workingDir` | string | | Working directory (created if missing) |
| `timeout` | integer | none | Timeout in milliseconds |
| `shell` | boolean | `true` | Run via system shell (`cmd /c` on Windows, `sh -c` on Unix) |

### copy

Copies a classpath resource to a destination.

```json
{
  "copy": {
    "name": "Copy server JAR",
    "source": "/lsp/my-server/lib/server.jar",
    "destination": "${serverDist}/lib/server.jar"
  }
}
```

### extractResource

Extracts a bundled archive from classpath resources.

```json
{
  "extractResource": {
    "name": "Extract bundled server",
    "source": "/lsp/my-server/server.tar.gz",
    "destination": "${serverDist}"
  }
}
```

### configureServer

Sets the server launch command. This task is typically used as an `onSuccess` of a download or exec task.

```json
{
  "configureServer": {
    "name": "Configure server command",
    "command": {
      "windows": "${serverDist}/bin/server.exe --stdio",
      "default": "${serverDist}/bin/server --stdio"
    }
  }
}
```

## OS-Specific Values

Any string field can be made OS-specific using an object with platform keys:

```json
{
  "windows": "value for Windows",
  "unix": "value for Linux",
  "mac": "value for macOS",
  "default": "fallback value"
}
```

Architecture-specific values are also supported:

```json
{
  "windows": "value",
  "unix": {
    "x86_64": "value for Linux x64",
    "arm64": "value for Linux ARM"
  },
  "mac": {
    "x86_64": "value for macOS x64",
    "arm64": "value for macOS ARM (Apple Silicon)"
  }
}
```

## Task Chaining

Tasks can be chained with `onSuccess` and `onFail`:

```json
{
  "check": {
    "fileExists": {
      "name": "Check VS Code extension",
      "file": "${vscodeExtension:my-ext}/server/bin/server",
      "onSuccess": {
        "configureServer": {
          "command": "${vscodeExtension:my-ext}/server/bin/server --stdio"
        }
      },
      "onFail": {
        "fileExists": {
          "name": "Check if already downloaded",
          "file": "${serverDist}/bin/server"
        }
      }
    }
  }
}
```

## Variables

See [variables.md](variables.md) for the complete variable reference. Key variables for installer.json:

| Variable | Description |
|---|---|
| `${serverDist}` | Installation directory for binaries and libraries |
| `${serverHome}` | Server configuration directory (contains `server.json`, `installer.json`) |
| `${extensionHome}` | Extension directory (parent of server type directories) |
| `${mcpHome}` | MCP ADE root directory |
| `${dist.file}` | Relative path to the main file within `${serverDist}` (set by `download` task) |
| `${workspaceFolder}` | Current workspace directory |

## Complete Examples

### Binary download (clangd)

```json
{
  "id": "clangd-installer",
  "name": "clangd",
  "check": {
    "fileExists": {
      "name": "Check if Clangd is installed",
      "file": {
        "windows": "${serverDist}/bin/clangd.exe",
        "default": "${serverDist}/bin/clangd"
      }
    }
  },
  "run": {
    "download": {
      "name": "Download clangd",
      "github": {
        "owner": "clangd",
        "repository": "clangd",
        "prerelease": true,
        "asset": {
          "windows": "clangd-windows*.zip",
          "unix": "clangd-linux*.zip",
          "mac": "clangd-mac*.zip"
        }
      },
      "output": {
        "stripRootDir": true,
        "file": {
          "name": {
            "windows": "bin/clangd.exe",
            "default": "bin/clangd"
          },
          "executable": true
        }
      },
      "onSuccess": {
        "configureServer": {
          "name": "Configure clangd",
          "command": "${serverDist}/${dist.file}"
        }
      }
    }
  }
}
```

### npm install (typescript-language-server)

```json
{
  "id": "typescript-language-server-installer",
  "name": "typescript-language-server",
  "runtime": "nodejs",
  "check": {
    "fileExists": {
      "name": "Check if installed",
      "file": {
        "windows": "${serverDist}/node_modules/.bin/typescript-language-server.cmd",
        "default": "${serverDist}/node_modules/.bin/typescript-language-server"
      }
    }
  },
  "run": {
    "exec": {
      "name": "Install via npm",
      "command": "npm install --prefix ${serverDist} typescript-language-server typescript",
      "workingDir": "${serverDist}/node_modules",
      "onSuccess": {
        "configureServer": {
          "name": "Configure server",
          "command": {
            "windows": "${serverDist}/node_modules/.bin/typescript-language-server.cmd --stdio",
            "default": "${serverDist}/node_modules/.bin/typescript-language-server --stdio"
          }
        }
      }
    }
  }
}
```

### Multi-download (quarkus)

```json
{
  "id": "quarkus",
  "name": "Quarkus Extension",
  "check": {
    "fileExists": {
      "name": "Check if installed",
      "file": "${serverDist}/lib/com.redhat.quarkus.ls.jar"
    }
  },
  "run": {
    "download": {
      "name": "Download Quarkus LS",
      "github": {
        "owner": "redhat-developer",
        "repository": "quarkus-ls",
        "asset": "com.redhat.quarkus.ls-*.jar"
      },
      "output": {
        "file": { "name": "lib/com.redhat.quarkus.ls.jar" }
      },
      "onSuccess": {
        "download": {
          "name": "Download Quarkus JDT plugin",
          "github": {
            "owner": "redhat-developer",
            "repository": "quarkus-ls",
            "asset": "com.redhat.microprofile.jdt.quarkus_*.jar"
          },
          "output": {
            "file": { "name": "plugins/com.redhat.microprofile.jdt.quarkus.jar" }
          }
        }
      }
    }
  }
}
```

### Runtime (JDK)

```json
{
  "id": "jdk",
  "name": "JDK",
  "url": "https://adoptium.net",
  "check": {
    "exec": {
      "name": "Check if Java is available",
      "command": {
        "windows": "where java",
        "default": "which java"
      }
    }
  },
  "run": {
    "download": {
      "name": "Download JDK",
      "github": {
        "owner": "adoptium",
        "repository": "temurin21-binaries",
        "asset": { "windows": "jdk_x64_windows*.zip", "unix": "jdk_x64_linux*.tar.gz" }
      },
      "output": {
        "stripRootDir": true,
        "file": {
          "name": { "windows": "bin/java.exe", "default": "bin/java" },
          "executable": true
        }
      },
      "onSuccess": {
        "configureServer": {
          "name": "Configure JDK path",
          "command": { "windows": "${serverDist}/bin/java.exe", "default": "${serverDist}/bin/java" }
        }
      }
    }
  }
}
```
