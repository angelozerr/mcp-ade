# Bind Mechanism (bindRequest & bindNotification)

## Overview

The bind mechanism allows language servers to delegate custom requests and notifications to other language servers in a declarative way through the `server.json` configuration file.

## Concepts

### bindRequest
Routes **custom requests** from one language server to another. Default mode: `executeCommand`.

**Use case**: When a language server needs data from another server (e.g., MicroProfile needs Java project info from JDT.LS).

### bindNotification
Routes **custom notifications** from one language server to another. Default mode: `direct`.

**Use case**: When a language server needs to notify another server of changes (e.g., Qute notifies JDT.LS that the data model changed).

## Configuration Format

### Simple Format (Array of Strings)

```json
{
  "contributes": {
    "jdtls": {
      "bindRequest": [
        "qute/template/project",
        "qute/template/projectDataModel"
      ],
      "bindNotification": [
        "qute/dataModelChanged"
      ]
    }
  }
}
```

### Advanced Format (Object with Mode)

```json
{
  "contributes": {
    "jdtls": {
      "bindRequest": {
        "mode": "direct",
        "methods": [
          "qute/template/project",
          {
            "method": "qute/java/codeLens",
            "mode": "executeCommand"
          },
          {
            "method": "qute/template/javaTypes",
            "targetMethod": "jdtls/qute/getJavaTypes"
          }
        ]
      },
      "bindNotification": {
        "mode": "executeCommand",
        "methods": [
          "qute/dataModelChanged"
        ]
      }
    }
  }
}
```

## Configuration Options

### Method Entry Formats

#### String (Simple)
```json
"qute/template/project"
```
Uses:
- Same method name for source and target
- Default mode for the bind type

#### Object (Advanced)
```json
{
  "method": "qute/template/project",
  "targetMethod": "jdtls/qute/getProject",
  "mode": "direct"
}
```

Fields:
- **`method`** (required): The method name received by the source server
- **`targetMethod`** (optional): The method name to call on the target server (defaults to `method`)
- **`mode`** (optional): Routing mode for this specific method (overrides default)

### Routing Modes

| Mode | Value | Description | Default for |
|------|-------|-------------|-------------|
| **Execute Command** | `"executeCommand"` | Routes via `workspace/executeCommand` | bindRequest |
| **Direct** | `"direct"` | Direct method call on target server | bindNotification |

### Mode Priority

1. **Method-level mode**: `{ "method": "...", "mode": "direct" }`
2. **Bind-level mode**: `{ "mode": "direct", "methods": [...] }`
3. **Default mode**:
   - `bindRequest` → `executeCommand`
   - `bindNotification` → `direct`

## Real-World Examples

### Example 1: Qute Language Server

**File**: `extensions/qute/src/main/resources/lsp/qute/server.json`

```json
{
  "id": "qute",
  "name": "Qute Language Server",
  "contributes": {
    "jdtls": {
      "bundles": ["plugins/*.jar"],
      "bindRequest": [
        "qute/template/projects",
        "qute/template/project",
        "qute/template/projectDataModel",
        "qute/template/javaTypes",
        "qute/template/resolvedJavaType",
        "qute/template/javaDefinition",
        "qute/java/codeLens",
        "qute/java/diagnostics"
      ],
      "bindNotification": [
        "qute/dataModelChanged"
      ]
    }
  }
}
```

**Flow**:
1. Qute LSP server calls `client.request("qute/template/project", params)`
2. GenericLanguageClient finds `bindRequest: ["qute/template/project"]` in `contributes.jdtls`
3. Routes to JDT.LS via `workspace/executeCommand` (default mode)
4. JDT.LS executes the command and returns project info
5. Response is returned to Qute LSP server

### Example 2: MicroProfile Language Server

```json
{
  "id": "microprofile",
  "name": "MicroProfile Language Server",
  "contributes": {
    "jdtls": {
      "bundles": ["lib/*.jar"],
      "bindRequest": [
        "microprofile/java/projectInfo",
        "microprofile/java/fileInfo"
      ],
      "bindNotification": [
        "microprofile/propertiesChanged"
      ]
    }
  }
}
```

### Example 3: Custom Mode Per Method

```json
{
  "id": "custom-ls",
  "contributes": {
    "jdtls": {
      "bindRequest": {
        "mode": "direct",
        "methods": [
          "custom/fastQuery",
          {
            "method": "custom/complexQuery",
            "mode": "executeCommand"
          }
        ]
      }
    }
  }
}
```

### Example 4: Method Name Mapping

```json
{
  "id": "legacy-ls",
  "contributes": {
    "jdtls": {
      "bindRequest": [
        {
          "method": "legacy/getInfo",
          "targetMethod": "jdtls/v2/getProjectInfo"
        }
      ]
    }
  }
}
```

## Architecture

### Components

```
┌─────────────────────┐
│   LSP Server        │
│  (e.g., Qute LS)    │
└──────────┬──────────┘
           │ client.request("qute/template/project", ...)
           ▼
┌─────────────────────┐
│ GenericLanguageClient│
│  - findBindInfo()   │
│  - parseBindEntry() │
└──────────┬──────────┘
           │ Check server.json contributes
           ▼
┌─────────────────────┐
│   RequestRouter     │
│  routeRequest()     │
└──────────┬──────────┘
           │ executeCommand or direct
           ▼
┌─────────────────────┐
│  Target Server      │
│  (e.g., JDT.LS)     │
└─────────────────────┘
```

### Key Classes

**BindInfo** (record)
```java
private record BindInfo(
    String targetServerId,    // "jdtls"
    String targetMethod,      // "qute/template/project" or remapped name
    BindMode mode            // EXECUTE_COMMAND or DIRECT
) {}
```

**BindMode** (enum)
```java
public enum BindMode {
    EXECUTE_COMMAND("executeCommand"),
    DIRECT("direct");
    
    public static BindMode fromString(String mode) { ... }
}
```

**GenericLanguageClient**
- `findBindInfo(method, bindKey, defaultMode)`: Find bind configuration
- `parseBindEntry(entry, sourceMethod, targetServerId, defaultMode)`: Parse JSON entry
- `request(method, parameter)`: Handle bindRequest
- `notify(method, parameter)`: Handle bindNotification

## Usage Guidelines

### When to use bindRequest
- Querying data from another server
- Need response from target server
- Examples: get project info, resolve types, get diagnostics

### When to use bindNotification
- Fire-and-forget notifications
- No response needed
- Examples: notify of file changes, model updates

### When to use executeCommand mode
- Target server exposes functionality via `workspace/executeCommand`
- Need workspace-level operation
- Default for bindRequest

### When to use direct mode
- Direct method-to-method communication
- Faster, no command wrapping
- Default for bindNotification

## Constants

```java
// JSON field names
private static final String BIND_REQUEST = "bindRequest";
private static final String BIND_NOTIFICATION = "bindNotification";
private static final String MODE = "mode";
private static final String METHODS = "methods";
private static final String METHOD = "method";
private static final String TARGET_METHOD = "targetMethod";
```

## Benefits

✅ **Declarative Configuration**: No code changes needed to route methods  
✅ **Flexible Modes**: Choose between executeCommand and direct routing  
✅ **Method Mapping**: Rename methods between source and target  
✅ **Per-Method Modes**: Override mode for specific methods  
✅ **Type-Safe**: Enum-based mode with compile-time safety  
✅ **Maintainable**: Single `findBindInfo()` method for both request and notification

## Migration from Old Format

### Before (Not Supported)
```json
{
  "contributes": {
    "jdtls": {
      "bindMode": "direct",
      "bindRequest": ["method1", "method2"]
    }
  }
}
```

### After (Current Format)
```json
{
  "contributes": {
    "jdtls": {
      "bindRequest": {
        "mode": "direct",
        "methods": ["method1", "method2"]
      }
    }
  }
}
```

## See Also

- [Contributes Documentation](./contributes.md)
- [Extension System](./extensions.md)
- [Request Router](../core/src/main/java/org/eclipse/mcp/ade/lsp/RequestRouter.java)
