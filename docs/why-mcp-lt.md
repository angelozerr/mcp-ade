# Why MCP Language Tools?

*A conversation between a skeptic and MCP LT.*

---

**MCP LT**: Hi! I'm an MCP server for LSP and DAP.

**Skeptic**: What! Yet another MCP server for LSP?

**MCP LT**: Indeed, there are MCP servers for LSP. But did you notice the "and DAP" part?

**Skeptic**: DAP? What's that?

**MCP LT**: [DAP (Debug Adapter Protocol)](https://microsoft.github.io/debug-adapter-protocol/) is to debuggers what LSP is to language features. It's a standard protocol that lets you debug programs — set breakpoints, step through code, inspect variables — with any debug adapter that speaks the protocol.

**Skeptic**: OK, so you support debugging too. But why do I need that when my IDE already handles it?

**MCP LT**: Good question. If you're working inside an IDE that already has its own MCP server — like [JetBrains MCP Server](https://www.jetbrains.com/help/idea/mcp-server.html) — then use that. It already knows your project, your settings, your run configurations.

MCP LT is for a different scenario: AI assistants that **don't live inside an IDE**. [Claude Code](https://docs.anthropic.com/en/docs/claude-code) runs in your terminal. [Claude Desktop](https://claude.ai/download) runs on your desktop. [Bob Shell](https://bob.ibm.com/) runs in a terminal. They have no IDE behind them. MCP LT gives them LSP and DAP capabilities as a **standalone process** — no IDE required.

**Skeptic**: Standalone? So I don't need VS Code or anything running?

**MCP LT**: Nothing. MCP LT is a standalone server. It starts, downloads and manages the language servers and debug adapters itself, and exposes everything through MCP. Your AI assistant connects to it directly — no IDE in the loop.

**Skeptic**: OK that makes sense. But for the LSP part, there are already other MCP servers that do that. What makes you different?

**MCP LT**: Keep reading — you'll see. Let me start with how it's built. MCP LT is a **platform** that manages language servers and debug adapters, handles their lifecycle, and exposes their capabilities as MCP tools.

**Skeptic**: A platform? That sounds overengineered. What does that actually mean?

**MCP LT**: It means each language server is packaged as an **extension**, defined by just two JSON files — `server.json` for configuration and `installer.json` for auto-download. No code required. Want to add support for a new language? Write two small JSON files and you're done.

**Skeptic**: Wait, no code at all? What about complex servers like JDT.LS that need special setup?

**MCP LT**: For those cases, you can use Java code via SPI. JDT.LS is a good example — it needs custom initialization, workspace management, and special command handling. The SPI extension point lets you handle that while the core framework still manages the lifecycle.

**Skeptic**: OK so how many languages do you actually support?

**MCP LT**: Out of the box: Java, JavaScript/TypeScript, Python, Go, Rust, C/C++, XML, YAML, Kotlin, Dart, PHP, Lua, and Dockerfile. Each with its standard language server, and many with a debug adapter too.

**Skeptic**: That's the list of languages, but what can you actually *do* with them? Get diagnostics and go to definition — is that really useful for an AI?

**MCP LT**: Diagnostics alone are a game-changer. Think about what happens today when an AI assistant makes a code change that introduces an error. Without diagnostics, it has to **recompile the entire project** to discover the mistake — that's slow on large projects and consumes a lot of tokens parsing compiler output. With MCP LT, the language server reports the error **instantly** as a diagnostic. The AI sees exactly what's wrong, applies a quick fix, and moves on. No full rebuild, no wasted tokens.

**Skeptic**: OK, that's a good point. But beyond diagnostics, is there more?

**MCP LT**: Far more than that. Take Java as an example — there are [**80 dedicated Java tools**](../README.md#java-tools-79-tools-from-java-extension) covering analysis, navigation, refactoring, code generation, diagnostics, code quality, and even framework support for Spring/Jakarta endpoints and JPA models.

**Skeptic**: 80 tools? Like what?

**MCP LT**: Rename a symbol across the entire project. Extract a method, a variable, a constant. Inline a method. Pull members up to a superclass or push them down to subclasses. Change a method signature and update all call sites. Convert a class to a Java 16 record. Analyze cyclomatic complexity. Find circular dependencies. Generate constructors, equals/hashCode, toString — all powered by the Eclipse JDT.LS refactoring engine.

**Skeptic**: That's basically what IntelliJ or Eclipse can do...

**MCP LT**: Exactly — but now your AI assistant can do it too. And with **preview mode**: every refactoring tool lets you preview the changes before applying them. The AI can review what will change, then decide whether to apply.

**Skeptic**: OK impressive for Java. But that's just one language. The others just get basic diagnostics?

**MCP LT**: Each language gets whatever its language server provides. Rust with rust-analyzer gives you full type analysis. Python with Pyright gives you type checking. Go with gopls gives you the full Go tooling experience. The MCP tools for diagnostics, references, definitions, and code actions work with **all** language servers.

**Skeptic**: You mentioned servers can collaborate? What does that mean?

**MCP LT**: It's called the [Bind Mechanism](bind-mechanism.md). Servers can communicate with each other — not just LSP-to-LSP, but also LSP-to-DAP. For example, MicroProfile LS needs to resolve Java types — so it delegates to JDT.LS. And the Java debug adapter (`java-debug`) communicates with JDT.LS to resolve classpath and launch configurations. MCP LT manages all this communication between servers automatically.

**Skeptic**: Wait — so MicroProfile, Quarkus, Liberty... those VS Code / Bob IDE extensions that depend on JDT.LS, they work here too?

**MCP LT**: Yes, and that's a key point. The language servers for MicroProfile, Quarkus, and Liberty that exist as VS Code or Bob IDE extensions are **reused as-is** in MCP LT — including their delegate command handlers that communicate with JDT.LS. So out of the box, your AI assistant gets framework support for MicroProfile, Quarkus, and Liberty, with the same capabilities as in your IDE.

**Skeptic**: And the DAP side — can I debug Java and JavaScript the same way?

**MCP LT**: Yes. Each debug adapter is also an extension. Java uses `java-debug`, JavaScript uses `vscode-js-debug`, Python uses `debugpy`, Go uses `go-delve`, C/C++ uses `codelldb`, Rust uses `codelldb` too. Same MCP tools for all of them — `start_debugging`, `set_breakpoint`, `step_over`, `evaluate_expression`, `get_local_variables`...

**Skeptic**: I work with Rust and sometimes I need to debug at the disassembly level. Can MCP LT handle that?

**MCP LT**: Yes. With `codelldb`, you can use the `disassemble` tool to inspect instructions at a memory address, and `set_instruction_breakpoint` to break at specific instruction addresses. You can even step at instruction granularity with `step_over`, `step_in`, and `step_out` — just set the granularity to `"instruction"`.

**Skeptic**: How does an AI assistant even know which configuration to use to start a debug session?

**MCP LT**: There's a `get_debug_templates` tool that returns ready-to-use launch/attach configurations for each debug adapter. The AI picks the right one, fills in the project path, and starts debugging. No manual setup.

**Skeptic**: What about settings? My language servers need specific configuration.

**MCP LT**: By default, MCP LT loads `.vscode/settings.json` and `.bob/settings.json` and sends them to language servers via `workspace/configuration`. Your servers receive the same settings as in your IDE — no duplication. And if you use a different settings format, you can extend this via Java SPI to load settings from any source.

**Skeptic**: I suppose I need to install and configure a bunch of things to get this working?

**MCP LT**: Almost nothing. The only thing you need is the **runtime** required by the language server or debug adapter — Java for JDT.LS, Node.js for TypeScript servers, Python for debugpy, etc. The language servers and debug adapters themselves are **auto-installed**: MCP LT downloads them on first use based on the `installer.json` descriptor. You just need the runtime, MCP LT handles the rest.

**Skeptic**: But how does MCP LT know which project I'm working on?

**MCP LT**: When an AI assistant calls a tool with a `cwd` parameter — say `/home/user/my-project` — MCP LT creates a **workspace** in memory for that directory. It then starts LSP and DAP server instances attached to that workspace. All subsequent tool calls for the same `cwd` reuse those instances. It's the same model as an IDE: one project directory = one workspace = dedicated server instances.

**Skeptic**: Wait — so if I have VS Code or Bob IDE open on the same directory, and I also launch Claude Code on that same `cwd`, they won't share the same LSP server instances? For something like JDT.LS, running two instances on the same project sounds heavy...

**MCP LT**: You're right, today they won't share instances — MCP LT runs its own. That's the tradeoff of being standalone: full independence from the IDE, but also separate resource usage. For heavy servers like JDT.LS, that means two instances loading the same project.

However, there's an ongoing experiment to support **multiple language clients** on a single LSP server instance (for servers built with [LSP4J](https://github.com/eclipse-lsp4j/lsp4j), like JDT.LS). Once validated and integrated, MCP LT would be able to **connect to an existing JDT.LS instance** already running in your IDE, instead of launching its own. That would eliminate the duplication entirely — your IDE and your AI assistant sharing the same language server.

**Skeptic**: And if I want to see what's going on under the hood?

**MCP LT**: There's an **Admin Console** at `http://localhost:7654/admin`. You can manage extensions, monitor workspaces, view LSP and MCP traces, and see active debug sessions — all from a web UI.

**Skeptic**: Alright, I'm starting to see the picture. But who would actually use this?

**MCP LT**: Anyone who wants their AI assistant to have LSP and DAP capabilities. [Claude Code](https://docs.anthropic.com/en/docs/claude-code) can use it to refactor Java code safely. [Claude Desktop](https://claude.ai/download) can use it to diagnose errors in any language. [Bob IDE](https://bob.ibm.com/) and Bob Shell get the same power. Any MCP-compatible client gets the full power of LSP and DAP — without reinventing the wheel.

**Skeptic**: Sounds great. But what are the limitations? What does it *not* handle well?

**MCP LT**: Let's be honest — large codebases are a challenge right now. Language servers like JDT.LS need time to load a project, especially with Maven dependency resolution (M2E). On a big Java project, you may have to wait a while before tools become responsive. This is something we're actively investigating.

Also, MCP LT is still a young project. Not every language server feature is exposed as an MCP tool yet, and some edge cases in DAP may not be covered. It works well, but it's not battle-tested at the scale of a mature IDE.

**Skeptic**: Fair enough. At least you're upfront about it. I'll give it a try — where do I start?

**MCP LT**: Pick your path:
- **[Getting Started with LSP](getting-started.md)** — diagnostics, navigation, refactoring in 5 minutes
- **[Getting Started with DAP](getting-started-dap.md)** — debugging with breakpoints and variable inspection

---

*Still skeptical? [Open an issue](https://github.com/nicoschl/mcp-lsp/issues) — we like tough questions.*
