# MCP Language Tools

> **MCP LT** is an [MCP](https://modelcontextprotocol.io/) server for [LSP](https://microsoft.github.io/language-server-protocol/) and [DAP](https://microsoft.github.io/debug-adapter-protocol/).

*— What! Yet another MCP Server for LSP?*

Not just LSP. Also **DAP** — debugging. And not just a server — a **standalone platform**.
No IDE required. MCP LT manages multiple language servers and debug adapters, handles their lifecycle,
and exposes all their capabilities as MCP tools to any AI assistant.

*— OK but what can it actually do?*

Diagnostics, code navigation, refactoring, code generation, debugging with breakpoints and variable inspection — for **Java, Python, JavaScript, Go, Rust, C/C++**, and [40+ more languages](docs/why-mcp-lt.md). **47 bundled extensions**, **52 LSP servers**, **8 DAP servers**. Java alone has **60 dedicated tools** powered by Eclipse JDT.LS.

*— [Read the full conversation with our skeptic →](docs/why-mcp-lt.md)*

### Key highlights

- **Multi-language support**: 47 extensions covering Java, JavaScript/TypeScript, Python, Go, Rust, C/C++, C#, Ruby, Scala, Kotlin, Dart, Swift, Zig, Haskell, Elixir, Erlang, OCaml, F#, Clojure, HTML/CSS/JSON, Vue, Svelte, Angular, PHP, Lua, Perl, R, Julia, Fortran, Ada, Crystal, Pascal, Bash, Terraform, LaTeX, Markdown, Nix, TOML, Elm, Ansible, XML, YAML, Dockerfile, and more
- **Both LSP and DAP**: Full language server support (diagnostics, navigation, refactoring) and debug adapter support (breakpoints, stepping, variables)
- **Server collaboration**: LSP and DAP servers can communicate with each other (e.g., MicroProfile LS leverages JDT.LS for Java type resolution). See [Bind Mechanism](docs/bind-mechanism.md)
- **Auto-installation**: Language servers and debug adapters are automatically downloaded and installed on first use
- **Declarative extensions**: Each server is defined by two JSON files (`server.json` + `installer.json`) — no code required. For advanced cases (e.g., JDT.LS), Java code can be used via SPI
- **Extensible via classpath or bundles**: Contribute extensions as Maven modules on the classpath, or add them at runtime via MCP tools and admin UI
- **IDE settings support**: Loads `.vscode/settings.json` and `.bob/settings.json` to send as LSP `workspace/configuration`, so language servers receive the same settings as in your IDE
- **Admin console**: Web UI to manage servers, monitor workspaces, and view traces at `http://localhost:7654/admin`
- **Any MCP client**: Works with [Claude Desktop](https://claude.ai/download), [Claude Code](https://docs.anthropic.com/en/docs/claude-code), [Bob IDE](https://bob.ibm.com/), and any MCP-compatible assistant

![Admin Console](docs/images/admin-console.png)

## Quick Start

- **[Getting Started (LSP)](docs/getting-started.md)** — Get diagnostics, references, and code navigation working in 5 minutes
- **[Getting Started (DAP)](docs/getting-started-dap.md)** — Debug your code with breakpoints, stepping, and variable inspection

## MCP Tools

### LSP Tools

| Tool | Description |
|------|-------------|
| `get_diagnostics` | Get errors and warnings for a file |
| `get_all_diagnostics` | Get diagnostics for all files in a workspace |
| `go_to_definition` | Jump to where a symbol is defined |
| `go_to_declaration` | Jump to where a symbol is declared |
| `go_to_type_definition` | Jump to where the type of a symbol is defined |
| `find_references` | Find all usages of a symbol |
| `find_implementations` | Find implementations of an interface/abstract class |
| `get_hover_info` | Get type information and documentation for a symbol |
| `get_completions` | Get code completion suggestions at a position |
| `get_signature_help` | Get parameter information for a function/method call |
| `get_document_symbols` | Get all symbols (classes, methods, variables) in a document |
| `get_code_actions` | Get quick fixes and refactoring suggestions |
| `rename` | Rename a symbol across the workspace |
| `search_workspace_symbols` | Search for classes, methods, variables by name |
| `format_document` | Format an entire document (supports `apply` parameter) |
| `format_document_range` | Format a specific range in a document (supports `apply` parameter) |
| `get_code_lenses` | Get code lenses (run test, reference counts, etc.) |
| `get_inlay_hints` | Get inlay hints (inferred types, parameter names) |
| `apply_workspace_edit` | Apply text edits to a file on disk |
| `open_document` / `close_document` | Keep a file open for multiple LSP operations |

### DAP Tools

| Tool | Description |
|------|-------------|
| `list_debug_adapters` | List available debug adapters with supported languages |
| `get_debug_templates` | Get launch/attach configuration templates |
| `start_debugging` | Launch or attach a debug session |
| `list_debug_sessions` / `close_debug_session` | Manage debug sessions |
| `set_breakpoint` / `remove_breakpoint` / `list_all_breakpoints` | Manage line breakpoints |
| `set_instruction_breakpoint` / `remove_instruction_breakpoint` / `list_instruction_breakpoints` | Manage instruction-level breakpoints |
| `continue_execution` / `pause_execution` | Continue or pause execution |
| `step_over` / `step_in` / `step_out` | Step through code (statement or instruction granularity) |
| `get_stack_trace` | View the call stack |
| `list_threads` | List all threads in the debugged program |
| `get_scopes` / `get_variables` | Inspect variable scopes and values |
| `get_local_variables` | Shortcut for local variables in the current frame |
| `evaluate_expression` | Evaluate expressions in debug context |
| `get_console_output` | Read program stdout/stderr output |
| `disassemble` | Disassemble instructions at a memory address |
| `detach_from_process` | Detach without terminating the process |
| `get_debug_statistics` | Get statistics about active debug sessions |

### Java Tools (60 tools from Java extension)

These tools are inspired by [javalens-mcp](https://github.com/pzalutski-pixel/javalens-mcp) and provide deep Java analysis, navigation, refactoring, and code generation capabilities powered by [Eclipse JDT.LS](https://github.com/eclipse-jdtls/eclipse.jdt.ls). Results use compact JSON with relative paths for token efficiency.

#### Common Parameters

Most Java tools share these standard parameters:

| Parameter | Description |
|-----------|-------------|
| `cwd` | Current working directory (project root path). Example: `'/home/user/project'` or `'C:\Users\project'` |
| `fileUri` | File URI (must be `file://` URI as in LSP). Example: `'file:///home/user/project/src/main/java/Main.java'` |
| `line` | Line number (0-based) |
| `character` | Character position in the line (0-based) |

#### The `apply` Parameter (Preview vs Apply)

All refactoring tools (except `java_organize_imports`) support an optional `apply` parameter that controls whether changes are written to disk:

| Value | Behavior |
|-------|----------|
| `false` (default) | **Preview mode** — Returns a preview of the changes (text edits, affected files) without modifying any file. Use this to review what will change before committing. |
| `true` | **Apply mode** — Applies the refactoring changes directly to disk. Files are modified in place. |

**Recommended workflow:**
1. Call the refactoring tool with `apply: false` (or omit `apply`) to preview changes
2. Review the returned text edits and affected files
3. If satisfied, call again with `apply: true` to apply

These 17 tools support the `apply` parameter: `java_rename_symbol`, `java_extract_method`, `java_extract_variable`, `java_extract_constant`, `java_extract_interface`, `java_extract_superclass`, `java_inline_method`, `java_inline_variable`, `java_change_method_signature`, `java_convert_anonymous_to_lambda`, `java_encapsulate_field`, `java_introduce_parameter_object`, `java_move_type_to_new_file`, `java_pull_up`, `java_push_down`, `java_convert_to_record`, `java_move_type_to_package`.

#### The `scope` Parameter (Workspace vs Project)

Several search tools support an optional `scope` parameter to limit search results to project sources only, avoiding large result sets from JDK and library code:

| Value | Behavior |
|-------|----------|
| *(omitted)* | **Workspace scope** (default) — Searches the entire workspace including dependencies |
| `"project"` | **Project scope** — Searches only project source files (excludes JDK/libraries) |

When using `scope: "project"`, you can optionally specify `projectName` to target a specific project in multi-project workspaces (defaults to the first Java project).

These tools support the `scope` parameter: `java_find_references`, `java_find_field_writes`, `java_find_annotation_usages`, `java_find_type_instantiations`, `java_get_call_hierarchy_incoming`, `java_get_framework_info`.

#### Analysis (9 tools)

| Tool | Description |
|------|-------------|
| `java_get_type_hierarchy` | Get supertypes, super interfaces, and subtypes of a Java type |
| `java_get_call_hierarchy_incoming` | Find all callers of a method |
| `java_get_call_hierarchy_outgoing` | Find all methods called by a method |
| `java_find_annotation_usages` | Find all usages of a Java annotation type |
| `java_find_type_instantiations` | Find all `new Type()` instantiations of a Java type |
| `java_analyze_file` | Get comprehensive analysis of a Java file (types, methods, fields, diagnostics, complexity) |
| `java_analyze_type` | Get comprehensive analysis of a Java type (hierarchy, members, references, complexity) |
| `java_analyze_method` | Get comprehensive analysis of a Java method (complexity, callers, callees, overrides) |
| `java_analyze_change_impact` | Analyze the ripple effect of changing a symbol |

#### Navigation (5 tools)

| Tool | Description |
|------|-------------|
| `java_go_to_definition` | Navigate to the definition of a Java symbol |
| `java_get_hover_info` | Get rich hover information for a Java symbol (signature, Javadoc, type info, enclosing element) |
| `java_get_signature_help` | Get method signature help at a specific position |
| `java_get_super_method` | Find the method that a Java method overrides or implements |
| `java_get_document_symbols` | Get all symbols (types, methods, fields) in a Java file |

#### Code Search (10 tools)

| Tool | Description |
|------|-------------|
| `java_find_references` | Find all references to a Java symbol (type, method, or field). Prefer over Grep for Java symbol usages |
| `java_find_implementations` | Find all implementations of a Java interface, abstract class, or method |
| `java_find_field_writes` | Find all write accesses to a field |
| `java_find_tests` | Find test methods in a Java file (JUnit 4/5, TestNG) |
| `java_find_affected_tests` | Find tests transitively affected by changes to a symbol |
| `java_find_unused_code` | Find unused code in a Java file (imports, private fields, methods, variables) |
| `java_find_unreachable_code` | Find unreachable code in a Java file (dead code after return/throw) |
| `java_suggest_imports` | Find import candidates for an unresolved type name |
| `java_get_type_usage_summary` | Get a comprehensive usage summary for a Java type |
| `java_search_symbols` | Search for Java symbols by name pattern (supports glob, e.g. 'My*Service') |

#### Reference Search (1 tool)

| Tool | Description |
|------|-------------|
| `java_find_type_usages` | Find specific usages of a Java type by kind: `cast`, `catch`, `instanceof`, `throws`, `type_argument` |

#### Refactoring (18 tools)

All refactoring tools (except `java_organize_imports`) support the [`apply` parameter](#the-apply-parameter-preview-vs-apply) for preview/apply workflow.

| Tool | Description |
|------|-------------|
| `java_rename_symbol` | Rename a Java symbol across the entire project |
| `java_organize_imports` | Organize imports: remove unused and sort |
| `java_extract_method` | Extract a code selection into a new method |
| `java_extract_variable` | Extract an expression into a local variable |
| `java_extract_constant` | Extract an expression into a static final constant field |
| `java_extract_interface` | Extract an interface from a Java class |
| `java_extract_superclass` | Extract a superclass from a Java class |
| `java_inline_method` | Inline a method by replacing call sites with the method body |
| `java_inline_variable` | Inline a local variable by replacing usages with its initializer |
| `java_change_method_signature` | Change a method's signature and update all call sites |
| `java_convert_anonymous_to_lambda` | Convert an anonymous class to a lambda expression |
| `java_encapsulate_field` | Encapsulate a field by generating getter/setter methods |
| `java_introduce_parameter_object` | Bundle method parameters into a parameter object class |
| `java_move_type_to_new_file` | Move a nested/inner type to its own top-level file |
| `java_pull_up` | Pull members up from a subclass into its superclass |
| `java_push_down` | Push members down from a superclass into its subclasses |
| `java_convert_to_record` | Convert a Java class to a record (Java 16+) |
| `java_move_type_to_package` | Move a top-level Java type to a different package |

#### Code Generation (5 tools)

| Tool | Description |
|------|-------------|
| `java_generate_getters_setters` | Generate getter and/or setter methods for fields |
| `java_generate_constructor` | Generate a constructor from fields |
| `java_generate_to_string` | Generate a toString() method |
| `java_generate_equals_hashcode` | Generate equals() and hashCode() methods |
| `java_generate_delegate_methods` | Generate delegate methods for a field's type |

#### Diagnostics & Fix (4 tools)

| Tool | Description |
|------|-------------|
| `java_validate_syntax` | Quick syntax-only validation (no semantic analysis) |
| `java_get_quick_fixes` | Get available quick fixes for problems at a position. Pass optional `fixId` to apply a specific fix |
| `java_diagnose_and_fix` | Diagnose problems and optionally apply safe auto-fixes |
| `java_apply_cleanup` | Apply a code cleanup to a Java file |

#### Code Quality (3 tools)

| Tool | Description |
|------|-------------|
| `java_find_possible_bugs` | Find potential bug patterns in a Java file |
| `java_find_circular_dependencies` | Find circular package dependencies in a Java project |
| `java_code_quality_report` | Run all quality checks on a Java file in one call (unused code, bugs, complexity) |

#### Framework (1 tool)

| Tool | Description |
|------|-------------|
| `java_get_framework_info` | Get framework-specific information from a Java project. `kind`: `endpoints` (HTTP/REST routes), `jpa` (JPA entity model), `di` (dependency injection registrations) |

#### Project (4 tools)

| Tool | Description |
|------|-------------|
| `java_get_project_structure` | Get the package hierarchy and file structure |
| `java_get_classpath_info` | Get the classpath entries of a Java project |
| `java_get_type_members` | Get all members of a Java type |
| `java_get_dependency_graph` | Get the import-based dependency graph |

#### Tool Summary

| Category | Count |
|----------|-------|
| Analysis | 9 |
| Navigation | 5 |
| Code Search | 10 |
| Reference Search | 1 |
| Refactoring | 18 |
| Code Generation | 5 |
| Diagnostics & Fix | 4 |
| Code Quality | 3 |
| Framework | 1 |
| Project | 4 |
| **Total** | **60** |

### Workspace Tools

| Tool | Description |
|------|-------------|
| `list_workspaces` | List active workspaces |
| `list_language_servers` | List language servers with status |
| `refresh_workspace` | Refresh all language servers to synchronize with file system changes |
| `build_workspace` | Build all language servers (auto full/incremental based on file watcher state) |
| `notify_file_changes` | Notify language servers about specific file changes |

### Extension Tools

| Tool | Description |
|------|-------------|
| `list_extensions` | List all installed extensions |
| `add_extension` | Add a new extension (LSP/DAP servers) |
| `remove_extension` | Remove an extension |
| `add_lsp_server` / `add_dap_server` | Add a server to an extension |
| `remove_lsp_server` / `remove_dap_server` | Remove a server |
| `enable_extension` / `disable_extension` | Enable or disable an extension |

## Bundled Extensions

**47 extensions** with **52 LSP servers** and **8 DAP servers**:

| Extension | LSP Server | DAP Server |
|-----------|-----------|------------|
| **Ada** | ada-language-server | — |
| **Angular** | angular-language-server | — |
| **Ansible** | ansible-language-server | — |
| **Bash** | bash-language-server | — |
| **C/C++** | clangd | codelldb |
| **C#** | OmniSharp | — |
| **Clojure** | clojure-lsp | — |
| **Crystal** | Crystalline | — |
| **Dart** | dart-lsp | dart-debug |
| **Dockerfile** | dockerfile-language-server | buildx-dockerfile |
| **Elixir** | ElixirLS | — |
| **Elm** | elm-language-server | — |
| **Erlang** | erlang-ls | — |
| **F#** | FsAutoComplete | — |
| **Fortran** | fortls | — |
| **Go** | gopls | go-delve |
| **Haskell** | haskell-language-server | — |
| **Jakarta EE** | jakarta-ls | — |
| **Java** | JDT.LS | java-debug |
| **JavaScript** | typescript-language-server | vscode-js-debug |
| **Julia** | LanguageServer.jl | — |
| **Kotlin** | kotlin-language-server | — |
| **LaTeX** | Texlab | — |
| **Liberty** | lemminx-liberty, liberty-ls | — |
| **Lua** | lua-language-server | — |
| **Markdown** | Marksman | — |
| **MicroProfile** | microprofile-ls | — |
| **Nix** | nil | — |
| **OCaml** | ocaml-lsp | — |
| **Pascal** | pasls | — |
| **Perl** | PerlNavigator | — |
| **PHP** | Intelephense | vscode-php-debug |
| **Python** | Pyright | debugpy |
| **Quarkus** | quarkus-ls, qute-ls | — |
| **R** | R Language Server | — |
| **Ruby** | Solargraph | — |
| **Rust** | rust-analyzer | — |
| **Scala** | Metals | — |
| **Svelte** | svelte-language-server | — |
| **Swift** | SourceKit-LSP | — |
| **Terraform** | terraform-ls | — |
| **TOML** | Taplo | — |
| **Vue** | vue-language-server | — |
| **Web** | HTML LS, CSS LS, JSON LS | — |
| **XML** | LemMinX | — |
| **YAML** | yaml-language-server | — |
| **Zig** | ZLS | — |

## Adding Your Own Extensions

You can add custom LSP/DAP servers in three ways:

1. **Via MCP tools**: Use `add_extension`, `add_lsp_server`, `add_dap_server` — the AI assistant can do it for you
2. **Via Admin UI**: Use the extensions management page at `http://localhost:7654/admin`
3. **Via extension modules**: Create a Maven module with server descriptors

See the **[Extension Guide](docs/extensions.md)** for details.

## Admin Console

Access the admin UI at `http://localhost:7654/admin` to:

- **Manage extensions**: Install, enable, disable, remove extensions and their servers
- **Control servers**: Start, stop, restart LSP and DAP servers
- **Monitor workspaces**: View active workspaces and connected MCP clients
- **View traces**: Debug LSP and MCP communication
- **Debug sessions**: Monitor active debug sessions

See the **[Admin UI Guide](docs/admin-ui.md)** for details.

## Running

### First time: build the project

```bash
./mvnw clean install -DskipTests
```

### Dev mode (with hot-reload)

```bash
cd dev
../mvnw quarkus:dev
```

The server starts at `http://localhost:7654`. Admin UI at `http://localhost:7654/admin`.

### Configure your MCP client

```json
{
  "mcpServers": {
    "mcp-languagetools": {
      "type": "http",
      "url": "http://localhost:7654/mcp"
    }
  }
}
```

## Project Structure

```
mcp-lsp/
├── core/                        # Core framework (LSP/DAP integration, MCP tools, admin UI)
├── extensions/                  # 47 language extensions
│   ├── java/                    # Java (JDT.LS + java-debug)
│   ├── javascript/              # JavaScript/TypeScript (ts-language-server + vscode-js-debug)
│   ├── python/                  # Python (Pyright + debugpy)
│   ├── go/                      # Go (gopls + go-delve)
│   ├── rust/                    # Rust (rust-analyzer)
│   ├── c/                       # C/C++ (clangd + codelldb)
│   ├── csharp/                  # C# (OmniSharp)
│   ├── web/                     # HTML, CSS, JSON (vscode-langservers-extracted)
│   ├── angular/                 # Angular
│   ├── vue/                     # Vue
│   ├── svelte/                  # Svelte
│   ├── dart/                    # Dart (dart-lsp + dart-debug)
│   ├── kotlin/                  # Kotlin
│   ├── scala/                   # Scala (Metals)
│   ├── ruby/                    # Ruby (Solargraph)
│   ├── php/                     # PHP (Intelephense + vscode-php-debug)
│   ├── elixir/                  # Elixir (ElixirLS)
│   ├── haskell/                 # Haskell
│   ├── clojure/                 # Clojure
│   ├── xml/                     # XML (LemMinX)
│   ├── yaml/                    # YAML
│   ├── bash/                    # Bash
│   ├── lua/                     # Lua
│   ├── terraform/               # Terraform
│   ├── dockerfile/              # Dockerfile
│   ├── ...                      # + 22 more (Ada, Ansible, Crystal, Elm, Erlang, F#, Fortran,
│   │                            #   Julia, Jakarta EE, LaTeX, Liberty, Markdown, MicroProfile,
│   │                            #   Nix, OCaml, Pascal, Perl, Quarkus, R, Swift, TOML, Zig)
├── admin/                       # Admin UI module
└── dev/                         # Dev distribution (core + all extensions)
```

## Technology Stack

- **[Quarkus](https://quarkus.io/)** — Java framework
- **[Quarkus MCP Server](https://docs.quarkiverse.io/quarkus-mcp-server/dev/index.html)** — MCP implementation
- **[LSP4J](https://github.com/eclipse-lsp4j/lsp4j)** — LSP implementation
- **[DAP4J](https://github.com/nicoschl/dap4j)** — DAP implementation

## Documentation

- **[Getting Started (LSP)](docs/getting-started.md)** — Code validation and navigation
- **[Getting Started (DAP)](docs/getting-started-dap.md)** — Debugging
- **[Extension Guide](docs/extensions.md)** — Add your own servers
- **[Admin UI Guide](docs/admin-ui.md)** — Web console
- **[Bind Mechanism](docs/bind-mechanism.md)** — How servers collaborate
