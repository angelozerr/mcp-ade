/*******************************************************************************
 * Copyright (c) 2026 IBM Corporation and others.
 *
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     Angelo ZERR - initial API and implementation
 *******************************************************************************/
package com.ibm.mcp.languagetools.lsp.server;

import com.ibm.mcp.languagetools.PathManager;
import com.ibm.mcp.languagetools.language.LanguageDocument;
import com.ibm.mcp.languagetools.lsp.LspInstanceRegistry;
import com.ibm.mcp.languagetools.lsp.client.GenericLanguageClient;
import com.ibm.mcp.languagetools.lsp.client.LspCapability;
import com.ibm.mcp.languagetools.lsp.client.LspClientFeatures;
import com.ibm.mcp.languagetools.operation.OperationEntry;
import com.ibm.mcp.languagetools.progress.ProgressMonitor;
import com.ibm.mcp.languagetools.server.ServerBase;
import com.ibm.mcp.languagetools.server.ServerStatus;
import com.ibm.mcp.languagetools.server.ServerType;
import com.ibm.mcp.languagetools.configuration.ServerTrace;
import com.ibm.mcp.languagetools.trace.TraceCollector;
import com.ibm.mcp.languagetools.utils.JsonUtils;
import com.ibm.mcp.languagetools.workspace.Workspace;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.Endpoint;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageServer;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Generic Language Server instance.
 * Works with any LSP-compliant language server based on configuration.
 *
 * @see <a href="https://microsoft.github.io/language-server-protocol/specifications/specification-current/">Language Server Protocol Specification</a>
 */
public class LspServer extends ServerBase<LspServerConfig> {

    private static final Logger LOG = Logger.getLogger(LspServer.class);

    private final URI workspaceRoot;
    private final PathManager pathManager;

    private Socket socket;
    private LanguageServer languageServer;
    private GenericLanguageClient languageClient;
    private final Map<String, List<Diagnostic>> diagnosticsCache = new ConcurrentHashMap<>();
    private final Set<String> openedFiles = ConcurrentHashMap.newKeySet();
    private final Set<String> explicitlyOpenedFiles = ConcurrentHashMap.newKeySet();
    private boolean isSocketConnection = false;
    private LspInstanceRegistry.InstanceInfo currentInstance;
    private final LspClientFeatures clientFeatures;

    public LspServer(LspServerConfig config, Workspace workspace) {
        super(config, workspace);
        this.workspaceRoot = workspace.getRootUri();
        this.pathManager = workspace.getApplication().getPathManager();

        // Create client features for managing capabilities
        this.clientFeatures = new LspClientFeatures(config);
    }

    @Override
    protected TraceCollector initializeTraceCollector(Workspace workspace) {
        return workspace.getApplication().getLspTraceCollector();
    }

    /**
     * Start the language server process and establish LSP communication.
     * First tries to connect to an existing instance via socket, falls back to launching a new process.
     *
     * @param progressMonitor Progress monitor (never null, use ProgressMonitor.none() if not available)
     */
    public CompletableFuture<Void> start(ProgressMonitor progressMonitor) {
        // progressMonitor must never be null - use ProgressMonitor.none() instead
        // If null, let it fail with NullPointerException to catch bugs early

        // Common startup checks and preparation
        if (!prepareStart()) {
            return CompletableFuture.completedFuture(null);
        }

        var config = super.getConfig();

        // Ensure server and its contributors are installed first
        return withErrorLogging(
                config.ensureInstalled(getWorkspace(), this::setStatus, progressMonitor)
                        .thenCompose(v -> ensureContributorsInstalled(progressMonitor))
                        .thenCompose(v -> CompletableFuture.runAsync(() -> {
                            // Try to find existing instance first
                            String workspacePath = Paths.get(workspaceRoot).toString();
                            LspInstanceRegistry.InstanceInfo existingInstance = LspInstanceRegistry.findInstance(workspacePath, config.getServerId());

                            if (existingInstance != null) {
                                // Connect to existing instance via socket
                                setStatus(ServerStatus.CONNECTING_TO_IDE);
                                try {
                                    connectToSocket(existingInstance.port);
                                    currentInstance = existingInstance;
                                    LOG.infof("Connected to existing %s instance on port %d (PID: %d)",
                                            config.getServerId(), existingInstance.port, existingInstance.pid);
                                    startFileWatcher(workspacePath);
                                    return;
                                } catch (IOException e) {
                                    LOG.warnf("Failed to connect to existing instance on port %d, will launch new process: %s",
                                            existingInstance.port, e.getMessage());
                                    setStatus(ServerStatus.STARTING);
                                    // Fall through to launch new process
                                }
                            }

                            // No existing instance found or connection failed - launch new process
                            try {
                                launchProcess();
                                startFileWatcher(workspacePath);
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                        }, getExecutorService()))
        );
    }

    /**
     * Start MCP-managed language server process only (do not connect to IDE instance).
     *
     * @param progressMonitor Progress monitor (never null, use ProgressMonitor.none() if not available)
     */
    public CompletableFuture<Void> startManagedOnly(ProgressMonitor progressMonitor) {
        return startManagedOnly(progressMonitor, null);
    }

    /**
     * Start MCP-managed language server process only (do not connect to IDE instance).
     *
     * @param progressMonitor Progress monitor (never null, use ProgressMonitor.none() if not available)
     * @param operationEntry optional parent entry for operation tracking (nullable)
     */
    public CompletableFuture<Void> startManagedOnly(ProgressMonitor progressMonitor, OperationEntry operationEntry) {
        var config = super.getConfig();
        LOG.infof("Starting managed-only %s for workspace: %s", config.getServerId(), workspaceRoot);
        setStatus(ServerStatus.STARTING);

        // Ensure server and its contributors are installed first
        return withErrorLogging(
                config.ensureInstalled(getWorkspace(), this::setStatus, progressMonitor, false, operationEntry)
                        .thenCompose(v -> ensureContributorsInstalled(progressMonitor))
                        .thenCompose(v -> CompletableFuture.runAsync(() -> {
                            String workspacePath = Paths.get(workspaceRoot).toString();
                            try {
                                launchProcess();
                                startFileWatcher(workspacePath);
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                        }, getExecutorService()))
        );
    }

    /**
     * Hook for subclasses to install contributors before the server process is launched.
     * Default implementation does nothing.
     */
    protected CompletableFuture<Void> ensureContributorsInstalled(ProgressMonitor progressMonitor) {
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Connect to an existing language server via socket.
     */
    private void connectToSocket(int port) throws IOException {
        var config = super.getConfig();
        LOG.infof("Connecting to %s on localhost:%d", config.getServerId(), port);

        Socket newSocket = new Socket("localhost", port);
        try {
            InputStream in = newSocket.getInputStream();
            OutputStream out = newSocket.getOutputStream();

            Launcher<LanguageServer> launcher = createLauncher(in, out);

            languageServer = launcher.getRemoteProxy();
            startListening(launcher);
            socket = newSocket;
            isSocketConnection = true;
        } catch (Exception e) {
            try {
                newSocket.close();
            } catch (IOException closeEx) {
                e.addSuppressed(closeEx);
            }
            throw e;
        }

        LOG.infof("Socket connection established to %s on port %d", config.getServerId(), port);
    }

    /**
     * Launch a new language server process.
     */
    protected void launchProcess() throws IOException {
        var config = super.getConfig();
        LOG.infof("Launching new %s process for workspace: %s", config.getServerId(), workspaceRoot);

        Process serverProcess = startProcess();
        isSocketConnection = false;

        Launcher<LanguageServer> launcher = createLauncher(serverProcess.getInputStream(), serverProcess.getOutputStream());

        languageServer = launcher.getRemoteProxy();
        startListening(launcher);

        LOG.infof("%s process started for workspace: %s", config.getServerId(), workspaceRoot);

        // Set initial status message - server is RUNNING but not ready yet
        // Will be overridden by "Ready" after initialization,
        // or by language/status notifications for servers like JDT.LS
        setStatusMessage("Not Ready");
    }

    protected Launcher<LanguageServer> createLauncher(InputStream in, OutputStream out) {
        LanguageClient client = createLanguageClient();
        if (client instanceof GenericLanguageClient glc) {
            this.languageClient = glc;
        }
        return new Launcher.Builder<LanguageServer>()
                .setLocalService(client)
                .setRemoteInterface(LanguageServer.class)
                .setInput(in)
                .setOutput(out)
                .setExecutorService(getExecutorService())
                .configureGson(JsonUtils::configureGson)
                .wrapMessages(consumer -> message -> {
                    if (getServerTrace() != ServerTrace.off) {
                        getTracing().log(message, consumer);
                    }
                    consumer.consume(message);
                })
                .create();
    }

    /**
     * Initialize the language server (send LSP initialize request).
     */
    public CompletableFuture<Void> initialize() {
        var config = super.getConfig();
        // If connected to external instance (IDE), server is already initialized
        if (isSocketConnection && currentInstance != null) {
            LOG.infof("%s already initialized by IDE (port %d, PID %d)",
                    config.getServerId(), currentInstance.port, currentInstance.pid);
            setStatus(ServerStatus.CONNECTED_TO_IDE);
            return CompletableFuture.completedFuture(null);
        }

        LOG.infof("Initializing %s for workspace: %s", config.getServerId(), workspaceRoot);

        InitializeParams params = new InitializeParams();
        params.setRootUri(workspaceRoot.toString());

        // Set process ID
        params.setProcessId((int) ProcessHandle.current().pid());

        WorkspaceFolder workspaceFolder = new WorkspaceFolder();
        workspaceFolder.setUri(workspaceRoot.toString());
        workspaceFolder.setName(Paths.get(workspaceRoot).getFileName().toString());
        params.setWorkspaceFolders(List.of(workspaceFolder));

        // Client capabilities
        ClientCapabilities capabilities = new ClientCapabilities();

        WorkspaceClientCapabilities workspace = new WorkspaceClientCapabilities();
        workspace.setWorkspaceFolders(true);
        workspace.setConfiguration(true);
        DidChangeWatchedFilesCapabilities didChangeWatchedFiles = new DidChangeWatchedFilesCapabilities();
        didChangeWatchedFiles.setDynamicRegistration(true);
        workspace.setDidChangeWatchedFiles(didChangeWatchedFiles);
        capabilities.setWorkspace(workspace);

        TextDocumentClientCapabilities textDocument = createTextDocumentClientCapabilities();
        capabilities.setTextDocument(textDocument);

        params.setCapabilities(capabilities);

        // Set trace level from .mcp-languagetools/settings.json
        // lsp.${serverId}.trace = off | messages | verbose
        params.setTrace(getServerTrace().name());

        // Prepare initialization options (hook for subclasses to add server-specific options)
        Object initOptions = prepareInitializationOptions();
        if (initOptions != null) {
            params.setInitializationOptions(initOptions);
        }

        return languageServer.initialize(params)
                .handle((initResult, error) -> {
                    if (error != null) {
                        LOG.warnf(error, "%s initialize returned error for workspace: %s", config.getServerId(), workspaceRoot);
                    } else {
                        LOG.infof("%s initialized for workspace: %s", config.getServerId(), workspaceRoot);
                        clientFeatures.setServerCapabilities(initResult.getCapabilities());
                    }
                    languageServer.initialized(new InitializedParams());
                    setStatus(ServerStatus.RUNNING);
                    setStarted(true);
                    setReady(true);
                    setStatusMessage(error != null ? "Ready (initialize error)" : "Ready");
                    return (Void) null;
                });
    }

    private static TextDocumentClientCapabilities createTextDocumentClientCapabilities() {
        TextDocumentClientCapabilities textDocument = new TextDocumentClientCapabilities();
        textDocument.setPublishDiagnostics(new PublishDiagnosticsCapabilities());
        textDocument.setCodeAction(new CodeActionCapabilities());
        textDocument.setHover(new HoverCapabilities());
        textDocument.setDefinition(new DefinitionCapabilities());
        textDocument.setDeclaration(new DeclarationCapabilities());
        textDocument.setReferences(new ReferencesCapabilities());
        DocumentSymbolCapabilities documentSymbolCapabilities = new DocumentSymbolCapabilities();
        documentSymbolCapabilities.setHierarchicalDocumentSymbolSupport(true);
        textDocument.setDocumentSymbol(documentSymbolCapabilities);
        textDocument.setRename(new RenameCapabilities());
        textDocument.setImplementation(new ImplementationCapabilities());
        textDocument.setTypeDefinition(new TypeDefinitionCapabilities());
        textDocument.setCompletion(new CompletionCapabilities());
        textDocument.setSignatureHelp(new SignatureHelpCapabilities());
        textDocument.setFormatting(new FormattingCapabilities());
        textDocument.setRangeFormatting(new RangeFormattingCapabilities());
        textDocument.setCodeLens(new CodeLensCapabilities());
        textDocument.setInlayHint(new InlayHintCapabilities());
        textDocument.setCallHierarchy(new CallHierarchyCapabilities());
        textDocument.setTypeHierarchy(new TypeHierarchyCapabilities());
        return textDocument;
    }

    /**
     * Send a request directly to the language server via JSON-RPC.
     * Used for custom LSP requests that are not standard LSP commands.
     *
     * @param method Request method (e.g., "custom/myRequest")
     * @param params Request parameters
     * @return CompletableFuture with the response
     */
    public CompletableFuture<Object> sendRequest(String method, Object params) {
        if (languageServer == null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Language server not started")
            );
        }

        // Send directly via Endpoint (JSON-RPC)
        if (languageServer instanceof Endpoint endpoint) {
            return endpoint.request(method, params)
                    .thenApply(result -> (Object) result);
        }

        return CompletableFuture.failedFuture(
                new UnsupportedOperationException("Server does not support direct requests")
        );
    }

    private static final long DIAGNOSTICS_TIMEOUT_MS = 3_000;

    /**
     * Get diagnostics for a file URI.
     * Uses pull diagnostics (textDocument/diagnostic) when supported,
     * otherwise falls back to didOpen + waitForDiagnostics.
     *
     * @param uri        file URI
     * @param languageId language identifier for didOpen
     * @param autoClose  if true, sends didClose after diagnostics are received
     */
    public CompletableFuture<List<Diagnostic>> getDiagnostics(String uri, String languageId, boolean autoClose) {
        if (isFileOpened(uri)) {
            // File is actively tracked by the server, cache is up-to-date
            List<Diagnostic> cached = diagnosticsCache.get(uri);
            return CompletableFuture.completedFuture(cached != null ? cached : Collections.emptyList());
        }

        LanguageDocument document = new LanguageDocument(URI.create(uri), languageId);
        if (supportsCapability(LspCapability.DIAGNOSTIC, document)) {
            return getDiagnosticsWithPull(uri, languageId, autoClose);
        }
        return getDiagnosticsWithDidOpen(uri, languageId, autoClose);
    }

    private CompletableFuture<List<Diagnostic>> getDiagnosticsWithPull(String uri, String languageId, boolean autoClose) {
        if (languageServer == null) {
            return CompletableFuture.completedFuture(Collections.emptyList());
        }
        if (!autoClose) {
            ensureFileOpened(uri, languageId);
        }
        DocumentDiagnosticParams params = new DocumentDiagnosticParams(new TextDocumentIdentifier(uri));
        return languageServer.getTextDocumentService().diagnostic(params)
                .thenApply(report -> {
                    if (report != null && report.isLeft()) {
                        RelatedFullDocumentDiagnosticReport full = report.getLeft();
                        List<Diagnostic> diagnostics = full.getItems() != null ? full.getItems() : Collections.emptyList();
                        diagnosticsCache.put(uri, diagnostics);
                        return diagnostics;
                    }
                    // Unchanged report: return cached diagnostics
                    List<Diagnostic> cached = diagnosticsCache.get(uri);
                    return cached != null ? cached : Collections.emptyList();
                });
    }

    private void ensureFileOpened(String uri, String languageId) {
        if (languageServer == null) {
            return;
        }
        try {
            String content = Files.readString(Paths.get(URI.create(uri)));
            DidOpenTextDocumentParams openParams = new DidOpenTextDocumentParams();
            TextDocumentItem textDocument = new TextDocumentItem();
            textDocument.setUri(uri);
            textDocument.setLanguageId(languageId);
            textDocument.setVersion(1);
            textDocument.setText(content);
            openParams.setTextDocument(textDocument);
            languageServer.getTextDocumentService().didOpen(openParams);
            markFileOpened(uri);
        } catch (Exception e) {
            LOG.warnf(e, "Failed to open file %s for ensureFileOpened", uri);
        }
    }

    private CompletableFuture<List<Diagnostic>> getDiagnosticsWithDidOpen(String uri, String languageId, boolean autoClose) {
        if (languageServer == null || languageClient == null) {
            return CompletableFuture.completedFuture(Collections.emptyList());
        }

        String content;
        try {
            content = Files.readString(Paths.get(URI.create(uri)));
        } catch (Exception e) {
            LOG.warnf(e, "Failed to read file %s for didOpen", uri);
            return CompletableFuture.completedFuture(Collections.emptyList());
        }

        CompletableFuture<List<Diagnostic>> diagnosticsFuture =
                languageClient.waitForDiagnostics(uri, DIAGNOSTICS_TIMEOUT_MS);

        DidOpenTextDocumentParams openParams = new DidOpenTextDocumentParams();
        TextDocumentItem textDocument = new TextDocumentItem();
        textDocument.setUri(uri);
        textDocument.setLanguageId(languageId);
        textDocument.setVersion(1);
        textDocument.setText(content);
        openParams.setTextDocument(textDocument);

        languageServer.getTextDocumentService().didOpen(openParams);
        markFileOpened(uri);

        return diagnosticsFuture
                .whenComplete((diags, ex) -> {
                    if (autoClose) {
                        try {
                            DidCloseTextDocumentParams closeParams = new DidCloseTextDocumentParams();
                            closeParams.setTextDocument(new TextDocumentIdentifier(uri));
                            languageServer.getTextDocumentService().didClose(closeParams);
                            markFileClosed(uri);
                        } catch (Exception e) {
                            LOG.warnf(e, "Failed to send didClose for %s", uri);
                        }
                    }
                });
    }

    /**
     * Get code actions for the given params.
     * Default: calls textDocument/codeAction.
     * Subclasses (e.g., MicroProfileLspServer) override to use custom commands.
     */
    public CompletableFuture<List<Either<Command, CodeAction>>> getCodeActions(CodeActionParams params, String languageId) {
        return languageServer.getTextDocumentService().codeAction(params);
    }

    /**
     * Send a request via workspace/executeCommand.
     * Used for routing bindRequest between servers (like vscode-java does with java.execute.workspaceCommand).
     * The target server must have registered a command handler for this method.
     *
     * @param method Request method/command (e.g., "microprofile/java/projectInfo")
     * @param params Request parameters
     * @return CompletableFuture with the response
     */
    public CompletableFuture<Object> sendCommandRequest(String method, Object params) {
        if (languageServer == null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Language server not started")
            );
        }

        // Send via workspace/executeCommand (like vscode-java does)
        ExecuteCommandParams commandParams = new ExecuteCommandParams();
        commandParams.setCommand(method);

        // If params is already a list, use it; otherwise wrap in a list
        if (params instanceof java.util.List) {
            commandParams.setArguments((List<Object>) params);
        } else if (params != null) {
            commandParams.setArguments(List.of(params));
        } else {
            commandParams.setArguments(List.of());
        }

        return languageServer.getWorkspaceService()
                .executeCommand(commandParams)
                .thenApply(result -> result);
    }

    /**
     * Execute a command on the language server with tracing.
     */
    public CompletableFuture<Object> executeCommand(String command, Object params) {
        ServerTrace trace = getServerTrace();
        boolean verbose = trace == ServerTrace.verbose;
        if (trace != ServerTrace.off) {
            getTracing().traceRequest(command, params, verbose);
        }
        long startTime = System.nanoTime();
        return sendCommandRequest(command, params)
                .whenComplete((result, error) -> {
                    if (trace != ServerTrace.off) {
                        long durationMs = (System.nanoTime() - startTime) / 1_000_000;
                        getTracing().traceResponse(command, result, error, durationMs, verbose);
                    }
                });
    }

    /**
     * Shutdown the language server.
     */
    public CompletableFuture<Void> shutdown() {
        var config = super.getConfig();
        LOG.infof("Shutting down %s for workspace: %s", config.getServerId(), workspaceRoot);

        // Set status based on current connection type
        if (isSocketConnection && currentInstance != null) {
            setStatus(ServerStatus.DISCONNECTING);
        } else {
            setStatus(ServerStatus.STOPPING);
        }

        // Unregister from shared file watcher
        String workspacePath = Paths.get(workspaceRoot).toString();
        Path instanceFile = LspInstanceRegistry.getInstanceFilePath(workspacePath, config.getServerId());
        getWorkspace().getSharedFileWatcher().unwatchFile(instanceFile);

        // Clear diagnostics cache
        diagnosticsCache.clear();

        if (languageClient != null) {
            languageClient.shutdown();
        }

        if (languageServer == null && getServerProcess() == null && socket == null) {
            setStatus(ServerStatus.STOPPED);
            return CompletableFuture.completedFuture(null);
        }

        return CompletableFuture.runAsync(() -> {
            try {
                // Try graceful shutdown first
                if (languageServer != null) {
                    try {
                        languageServer.shutdown()
                                .get(5, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        LOG.warnf("Graceful shutdown failed for %s: %s", config.getServerId(), e.getMessage());
                    }
                    try {
                        languageServer.exit();
                    } catch (Exception e) {
                        LOG.warnf("Failed to send exit to %s: %s", config.getServerId(), e.getMessage());
                    }
                    languageServer = null;
                }

                cancelListeningFuture();

                // Close socket connection if connected via socket
                if (isSocketConnection && socket != null) {
                    try {
                        socket.close();
                        LOG.infof("Closed socket connection to %s", config.getServerId());
                    } catch (IOException e) {
                        LOG.warnf("Failed to close socket: %s", e.getMessage());
                    }
                }

                // Wait for process to exit after LSP exit notification.
                // On Windows, process.destroy() is a hard kill (no SIGTERM),
                // so we must wait for the process to exit on its own first.
                if (!isSocketConnection) {
                    Process process = getServerProcess();
                    if (process != null && process.isAlive()) {
                        boolean exited = process.waitFor(10, TimeUnit.SECONDS);
                        if (!exited) {
                            LOG.warnf("Server process did not exit after LSP exit, forcing kill (PID: %d)",
                                    process.pid());
                            process.destroyForcibly();
                            process.waitFor(3, TimeUnit.SECONDS);
                        }
                    }
                }

                LOG.infof("%s shut down for workspace: %s", config.getServerId(), workspaceRoot);
                setStatus(ServerStatus.STOPPED);

            } catch (Exception e) {
                LOG.errorf(e, "Error during shutdown of %s", config.getServerId());
                setStatus(ServerStatus.STOPPED);
            } finally {
                // DON'T shutdown executor - it would reject future start() attempts
                // (see ServerBase.cleanupResources() for the same reasoning)
            }
        }, CompletableFuture.delayedExecutor(0, TimeUnit.MILLISECONDS));
    }

    /**
     * Returns the workspace root URI.
     */
    protected URI getWorkspaceRoot() {
        return workspaceRoot;
    }

    /**
     * Returns the LSP4J language server proxy.
     */
    public LanguageServer getLanguageServer() {
        return languageServer;
    }

    /**
     * Set the LSP4J language server proxy.
     * Used by subclasses that override {@link #launchProcess()} to provide
     * an in-process server connection (e.g., for testing).
     *
     * @param languageServer the language server proxy
     */
    protected void setLanguageServer(LanguageServer languageServer) {
        this.languageServer = languageServer;
    }

    /**
     * Returns the diagnostics cache, keyed by file URI.
     */
    public Map<String, List<Diagnostic>> getDiagnosticsCache() {
        return diagnosticsCache;
    }

    /**
     * Returns the language client used to receive notifications from the server.
     */
    public GenericLanguageClient getLanguageClient() {
        return languageClient;
    }


    /**
     * Get the start command used to launch the server.
     */
    public String getStartCommand() {
        var config = super.getConfig();
        return config.getCommand();
    }

    /**
     * Returns the current external IDE instance info, or {@code null} if using a managed process.
     */
    public LspInstanceRegistry.InstanceInfo getCurrentInstance() {
        return currentInstance;
    }

    /**
     * Check if a file is currently opened in this server.
     */
    public boolean isFileOpened(String uri) {
        return openedFiles.contains(uri);
    }

    /**
     * Mark a file as opened.
     */
    public void markFileOpened(String uri) {
        openedFiles.add(uri);
    }

    /**
     * Mark a file as closed.
     */
    public void markFileClosed(String uri) {
        openedFiles.remove(uri);
    }

    /**
     * Open a file in this server via didOpen (if not already opened).
     */
    public void openFile(String uri, String languageId) {
        if (!isFileOpened(uri)) {
            ensureFileOpened(uri, languageId);
        }
    }

    /**
     * Close a file in this server via didClose.
     */
    public void closeFile(String uri) {
        if (!isFileOpened(uri) || languageServer == null) {
            return;
        }
        try {
            DidCloseTextDocumentParams closeParams = new DidCloseTextDocumentParams();
            closeParams.setTextDocument(new TextDocumentIdentifier(uri));
            languageServer.getTextDocumentService().didClose(closeParams);
            markFileClosed(uri);
        } catch (Exception e) {
            LOG.warnf(e, "Failed to send didClose for %s", uri);
        }
    }

    /**
     * Open a file explicitly (via open_document tool). File stays open until closeFileExplicitly is called.
     */
    public void openFileExplicitly(String uri, String languageId) {
        openFile(uri, languageId);
        explicitlyOpenedFiles.add(uri);
    }

    /**
     * Close a file previously opened explicitly (via close_document tool).
     */
    public void closeFileExplicitly(String uri) {
        explicitlyOpenedFiles.remove(uri);
        closeFile(uri);
    }

    /**
     * Check if a file was explicitly opened via open_document tool.
     */
    public boolean isExplicitlyOpened(String uri) {
        return explicitlyOpenedFiles.contains(uri);
    }

    /**
     * Execute a request with auto didOpen/didClose if needed.
     * If the server's config has skipDidOpen for this capability, the request runs directly.
     * Otherwise, the file is auto-opened before and auto-closed after (unless explicitly opened).
     */
    public <T> CompletableFuture<T> withAutoDidOpen(
            LspCapability capability, String fileUri, String languageId,
            Supplier<CompletableFuture<T>> request) {
        if (getConfig().isSkipDidOpen(capability)) {
            return request.get();
        }
        boolean wasAlreadyOpened = isFileOpened(fileUri);
        if (!wasAlreadyOpened) {
            openFile(fileUri, languageId);
        }
        return request.get()
                .whenComplete((result, ex) -> {
                    if (!wasAlreadyOpened && !isExplicitlyOpened(fileUri)) {
                        closeFile(fileUri);
                    }
                });
    }

    /**
     * Register with the workspace's shared file watcher for IDE start/stop detection.
     */
    private void startFileWatcher(String workspacePath) {
        var config = super.getConfig();
        Path instanceFile = LspInstanceRegistry.getInstanceFilePath(workspacePath, config.getServerId());
        getWorkspace().getSharedFileWatcher().watchFile(instanceFile, () -> handleInstanceFileChanged(workspacePath));
        LOG.infof("Registered instance file watcher for %s", config.getServerId());
    }

    private void handleInstanceFileChanged(String workspacePath) {
        var config = super.getConfig();
        Path instanceFile = LspInstanceRegistry.getInstanceFilePath(workspacePath, config.getServerId());
        if (java.nio.file.Files.exists(instanceFile)) {
            LspInstanceRegistry.InstanceInfo instance = LspInstanceRegistry.findInstance(workspacePath, config.getServerId());
            if (instance != null) {
                handleInstanceChanged(instance);
            } else {
                handleInstanceRemoved();
            }
        } else {
            handleInstanceRemoved();
        }
    }

    /**
     * Handle when a new/updated instance is detected (e.g., IDE started).
     */
    private void handleInstanceChanged(LspInstanceRegistry.InstanceInfo newInstance) {
        // Only react if it's a different instance than our current one
        if (currentInstance != null && currentInstance.pid == newInstance.pid && currentInstance.port == newInstance.port) {
            LOG.debugf("Instance unchanged, ignoring");
            return;
        }

        LOG.infof("New instance detected (PID: %d, port: %d), switching connection...", newInstance.pid, newInstance.port);

        // If we launched our own server, stop it
        if (!isSocketConnection && getServerProcess() != null && getServerProcess().isAlive()) {
            LOG.infof("Stopping our own server process to switch to IDE instance");
            try {
                languageServer.shutdown().get(2, TimeUnit.SECONDS);
                languageServer.exit();
            } catch (Exception e) {
                LOG.warnf("Error stopping our server: %s", e.getMessage());
            }
            destroyProcess(0, 2000);
        }

        // Close current socket if we're already connected
        if (isSocketConnection && socket != null) {
            try {
                socket.close();
                LOG.infof("Closed previous socket connection");
            } catch (IOException e) {
                LOG.warnf("Error closing socket: %s", e.getMessage());
            }
        }

        // Connect to new instance
        try {
            connectToSocket(newInstance.port);
            currentInstance = newInstance;

            initialize()
                    .thenRun(() -> LOG.infof("Successfully switched to new instance (PID: %d, port: %d)", newInstance.pid, newInstance.port))
                    .exceptionally(ex -> {
                        LOG.errorf(ex, "Failed to initialize after switching to new instance (PID: %d, port: %d)", newInstance.pid, newInstance.port);
                        handleInstanceRemoved();
                        return null;
                    });
        } catch (IOException e) {
            LOG.errorf(e, "Failed to connect to new instance, will try to restart our own server");
            handleInstanceRemoved();
        }
    }

    /**
     * Handle when instance is removed (e.g., IDE closed).
     */
    private void handleInstanceRemoved() {
        // If we're connected via socket and the instance is gone, launch our own server
        if (isSocketConnection) {
            LOG.infof("External instance removed, launching our own server");

            // Close socket
            if (socket != null) {
                try {
                    socket.close();
                } catch (IOException e) {
                    LOG.debugf("Error closing socket: %s", e.getMessage());
                }
                socket = null;
            }

            currentInstance = null;
            isSocketConnection = false;
            languageServer = null;
            cancelListeningFuture();

            // Launch our own server
            try {
                launchProcess();
                initialize()
                        .thenRun(() -> LOG.infof("Successfully launched our own server after instance removal"))
                        .exceptionally(ex -> {
                            LOG.errorf(ex, "Failed to initialize after instance removal");
                            setStatus(ServerStatus.ERROR);
                            return null;
                        });
            } catch (IOException e) {
                LOG.errorf(e, "Failed to launch server after instance removal");
                setStatus(ServerStatus.STOPPED);
            }
        }
    }

    /**
     * Create the language client for this server.
     * Subclasses can override this to provide custom client implementations
     * (e.g., JdtLsServer provides JdtLsLanguageClient for language/status notifications).
     */
    protected LanguageClient createLanguageClient() {
        return new GenericLanguageClient(this);
    }

    /**
     * Prepare initialization options for this server.
     * Subclasses can override to add server-specific options (e.g., bundles for JDT.LS).
     *
     * @return initialization options object, or null if none
     */
    protected Object prepareInitializationOptions() {
        var config = super.getConfig();
        // Default: use options from config if present
        if (config.getInitializationOptions() != null && !config.getInitializationOptions().isEmpty()) {
            return config.getInitializationOptions();
        }
        return null;
    }

    @Override
    protected boolean isRouteRequestTracedByWire() {
        return true;
    }

    @Override
    public ServerTrace getServerTrace() {
        return getWorkspace().getWorkspaceConfiguration().getLspTraceLevel(getConfig().getServerId());
    }

    @Override
    public ServerType getServerType() {
        return ServerType.LSP;
    }

    /**
     * Check if the server supports a given capability for a file.
     *
     * @param capability the LSP capability to check
     * @param document   the language document
     * @return true if the capability is supported
     */
    public boolean supportsCapability(LspCapability capability, LanguageDocument document) {
        return clientFeatures.supportsCapability(capability, document);
    }

    /**
     * Check if the server supports a given capability (without document context).
     */
    public boolean supportsCapability(LspCapability capability) {
        return clientFeatures.supportsCapability(capability);
    }

    /**
     * Get the client features for this server.
     *
     * @return the client features
     */
    public LspClientFeatures getClientFeatures() {
        return clientFeatures;
    }

    /**
     * Send workspace/didChangeWatchedFiles notification to the language server.
     *
     * @param changes list of file events (created, changed, deleted)
     */
    public void sendDidChangeWatchedFiles(List<FileEvent> changes) {
        if (languageServer == null || changes == null || changes.isEmpty()) {
            return;
        }
        DidChangeWatchedFilesParams params = new DidChangeWatchedFilesParams();
        params.setChanges(changes);
        languageServer.getWorkspaceService().didChangeWatchedFiles(params);
    }

    /**
     * Refresh the workspace for this language server.
     * Subclasses can override to add server-specific refresh logic
     * (e.g., JDT.LS needs project.refreshLocal to sync Eclipse workspace model).
     *
     * @return a future that completes with a status message
     */
    public CompletableFuture<String> refreshWorkspace() {
        return CompletableFuture.completedFuture("OK");
    }

    /**
     * Build the workspace for this language server.
     * Subclasses can override to add server-specific build logic
     * (e.g., JDT.LS calls vscode.java.buildWorkspace).
     *
     * @param fullBuild true for a full build, false for incremental
     * @return a future that completes with a status message
     */
    public CompletableFuture<String> buildWorkspace(boolean fullBuild) {
        return CompletableFuture.completedFuture("OK");
    }

}
