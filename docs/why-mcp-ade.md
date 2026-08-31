# Why MCP ADE (Agent Development Environment)?

*A conversation between a skeptic and MCP ADE.*

---

**MCP ADE**: I'm an MCP server for LSP and DAP.

**Skeptic**: What! Yet another MCP server for LSP?

**MCP ADE**: Did you notice the "and DAP" part?

**Skeptic**: DAP — as in [Debug Adapter Protocol](https://microsoft.github.io/debug-adapter-protocol/)? So you support debugging too?

**MCP ADE**: Yes. Breakpoints, stepping, variable inspection, expression evaluation — through any debug adapter that speaks the protocol.

**Skeptic**: But my IDE already handles all of this. Why would I need MCP ADE?

**MCP ADE**: If your IDE has its own MCP server — like [JetBrains MCP Server](https://www.jetbrains.com/help/idea/mcp-server.html) — use that instead.

MCP ADE is for AI assistants that **don't live inside an IDE**: [Claude Code](https://docs.anthropic.com/en/docs/claude-code) in your terminal, [Claude Desktop](https://claude.ai/download) on your desktop, [Bob Shell](https://bob.ibm.com/) in a terminal. They have no IDE behind them.

**Skeptic**: So MCP ADE is standalone? No IDE needed at all?

**MCP ADE**: Nothing. It starts, downloads the language servers and debug adapters, and exposes everything through MCP. Your AI assistant connects directly — no IDE in the loop.

**Skeptic**: OK that makes sense. But for the LSP part, there are already other MCP servers that do that. What makes you different?

**MCP ADE**: A few things — but let me show you a concrete example first. Imagine Claude Code is working on your Java project and introduces a compilation error. Here's what happens with MCP ADE:

1. Claude calls `get_diagnostics` — the language server reports the error **instantly**, no need to recompile the project
2. Claude calls `get_quick_fixes` — the language server suggests fixes for that exact error
3. Claude applies the fix — done

Without MCP ADE, Claude would have to **rebuild the entire project** just to discover the mistake, then parse the compiler output to understand it. That's slow on large projects and wastes a lot of tokens. With MCP ADE, the feedback loop is instant.

**Skeptic**: OK, that's compelling. But that's just diagnostics. What else?

**MCP ADE**: MCP ADE is a **platform** — it manages multiple language servers and debug adapters, handles their lifecycle, and exposes all their capabilities as MCP tools. Out of the box: Java, JavaScript/TypeScript, Python, Go, Rust, C/C++, XML, YAML, Kotlin, Dart, PHP, Lua, and Dockerfile — each with its standard language server, and many with a debug adapter too.

**Skeptic**: A platform — so how do you add a language?

**MCP ADE**: Each language server is packaged as an **extension**, defined by just two JSON files — `server.json` for configuration and `installer.json` for auto-download. No code required. Want to add support for a new language? Write two small JSON files and you're done.

**Skeptic**: No code at all? What about complex servers like JDT.LS that need special setup?

**MCP ADE**: For those cases, you can use Java code via SPI. JDT.LS is a good example — it needs custom initialization, workspace management, and special command handling. The SPI extension point lets you handle that while the core framework still manages the lifecycle.

**Skeptic**: OK, but what can you actually do beyond diagnostics?

**MCP ADE**: Take Java as an example — there are [**60 dedicated Java tools**](../README.md#java-tools-60-tools-from-java-extension) covering analysis, navigation, refactoring, code generation, diagnostics, code quality, and even framework support for Spring/Jakarta endpoints and JPA models.

**Skeptic**: 60 tools? Like what?

**MCP ADE**: Rename a symbol across the entire project. Extract a method, a variable, a constant. Inline a method. Pull members up to a superclass or push them down to subclasses. Change a method signature and update all call sites. Convert a class to a Java 16 record. Analyze files with complexity metrics. Find circular dependencies. Generate constructors, equals/hashCode, toString — all powered by the Eclipse JDT.LS refactoring engine.

**Skeptic**: That's basically what IntelliJ or Eclipse can do...

**MCP ADE**: Exactly — but now your AI assistant can do it too. And with **preview mode**: every refactoring tool lets you preview the changes before applying them. The AI can review what will change, then decide whether to apply.

**Skeptic**: OK impressive for Java. But that's just one language. The others just get basic diagnostics?

**MCP ADE**: Each language gets whatever its language server provides. Rust with rust-analyzer gives you full type analysis. Python with Pyright gives you type checking. Go with gopls gives you the full Go tooling experience. The MCP tools for diagnostics, references, definitions, and code actions work with **all** language servers.

**Skeptic**: Wait — you said MicroProfile LS, Quarkus LS... those depend on JDT.LS for Java type resolution. How does that work if they're separate servers?

**MCP ADE**: That's the [Bind Mechanism](bind-mechanism.md). Servers can communicate with each other — not just LSP-to-LSP, but also LSP-to-DAP. MicroProfile LS delegates Java type resolution to JDT.LS. The Java debug adapter (`java-debug`) communicates with JDT.LS to resolve classpath and launch configurations. MCP ADE manages all this automatically.

**Skeptic**: So those VS Code / Bob IDE extensions that depend on JDT.LS — they work here as-is?

**MCP ADE**: Yes, and that's a key point. The language servers for MicroProfile, Quarkus, and Liberty that exist as VS Code or Bob IDE extensions are **reused as-is** in MCP ADE — including their delegate command handlers that communicate with JDT.LS. So out of the box, your AI assistant gets framework support for MicroProfile, Quarkus, and Liberty, with the same capabilities as in your IDE.

**Skeptic**: And the DAP side — can I debug Java and JavaScript the same way?

**MCP ADE**: Yes. Each debug adapter is also an extension. Java uses `java-debug`, JavaScript uses `vscode-js-debug`, Python uses `debugpy`, Go uses `go-delve`, C/C++ uses `codelldb`, Rust uses `codelldb` too. Same MCP tools for all of them — `start_debugging`, `set_breakpoint`, `step_over`, `evaluate_expression`, `get_local_variables`...

**Skeptic**: I work with Rust and sometimes I need to debug at the disassembly level. Can MCP ADE handle that?

**MCP ADE**: Yes. With `codelldb`, you can use the `disassemble` tool to inspect instructions at a memory address, and `set_instruction_breakpoint` to break at specific instruction addresses. You can even step at instruction granularity with `step_over`, `step_in`, and `step_out` — just set the granularity to `"instruction"`.

**Skeptic**: How does an AI assistant even know which configuration to use to start a debug session?

**MCP ADE**: There's a `get_debug_templates` tool that returns ready-to-use launch/attach configurations for each debug adapter. The AI picks the right one, fills in the project path, and starts debugging. No manual setup.

**Skeptic**: What about settings? My language servers need specific configuration.

**MCP ADE**: By default, MCP ADE loads `.vscode/settings.json` and `.bob/settings.json` and sends them to language servers via `workspace/configuration`. Your servers receive the same settings as in your IDE — no duplication. And if you use a different settings format, you can extend this via Java SPI to load settings from any source.

**Skeptic**: I suppose I need to install and configure a bunch of things to get this working?

**MCP ADE**: Almost nothing. Language servers, debug adapters, and build servers are **auto-installed** — MCP ADE downloads them on first use based on the `installer.json` descriptor.

**Skeptic**: OK, but you still need the runtimes, right? If I don't have Java or Node.js installed, nothing works.

**MCP ADE**: Not necessarily. Runtimes like **JDK**, **Node.js**, and **Julia** are also **auto-installable** — MCP ADE downloads them from GitHub releases on first use. They're shared across all servers that depend on them: install once, used by many. For other runtimes like Go, Python, Ruby, or .NET, MCP ADE detects them on your system and gives you clear messages guiding manual installation if they're missing. You can see everything in the **Runtimes** tab of the admin console.

**Skeptic**: But how does MCP ADE know which project I'm working on?

**MCP ADE**: When an AI assistant calls a tool with a `cwd` parameter — say `/home/user/my-project` — MCP ADE creates a **workspace** in memory for that directory, starts LSP and DAP server instances attached to it, and reuses them for all subsequent calls on the same `cwd`.

**Skeptic**: So if I have VS Code open on the same directory and I launch Claude Code — that's two JDT.LS instances on the same project?

**MCP ADE**: Today, yes. That's the tradeoff of being standalone.

However, there's an ongoing experiment to support **multiple language clients** on a single LSP server instance (for servers built with [LSP4J](https://github.com/eclipse-lsp4j/lsp4j), like JDT.LS). Once validated, MCP ADE would **connect to an existing JDT.LS instance** already running in your IDE instead of launching its own — your IDE and your AI assistant sharing the same language server.

**Skeptic**: And if I want to see what's going on under the hood?

**MCP ADE**: There's an **Admin Console** at `http://localhost:7654/admin`. You can manage extensions, monitor workspaces, view LSP and MCP traces, and see active debug sessions — all from a web UI.

**Skeptic**: OK you've sold me on the features. Now tell me what doesn't work.

**MCP ADE**: Fair. Large codebases are a challenge right now. Language servers like JDT.LS need time to load a project, especially with Maven dependency resolution (M2E). On a big Java project, you may have to wait a while before tools become responsive. This is something we're actively investigating.

Also, MCP ADE is still a young project. Not every language server feature is exposed as an MCP tool yet, and some edge cases in DAP may not be covered.

**Skeptic**: So it's not production-ready for large projects?

**MCP ADE**: Not yet for the largest ones. It works well on small to medium projects. The loading time is the main bottleneck — once the language server is ready, the tools are fast. We're working on it.

**Skeptic**: Alright. Where do I start?

**MCP ADE**: Pick your path:
- **[Getting Started with LSP](getting-started.md)** — diagnostics, navigation, refactoring in 5 minutes
- **[Getting Started with DAP](getting-started-dap.md)** — debugging with breakpoints and variable inspection

---

*Still skeptical? [Open an issue](https://github.com/nicoschl/mcp-lsp/issues) — we like tough questions.*
