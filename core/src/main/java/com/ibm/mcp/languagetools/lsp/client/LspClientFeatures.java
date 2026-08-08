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
package com.ibm.mcp.languagetools.lsp.client;

import com.google.gson.JsonObject;
import com.ibm.mcp.languagetools.language.LanguageDocument;
import com.ibm.mcp.languagetools.lsp.client.capabilities.*;
import com.ibm.mcp.languagetools.lsp.server.LspServerConfig;
import org.eclipse.lsp4j.DidChangeWatchedFilesRegistrationOptions;
import org.eclipse.lsp4j.FileSystemWatcher;
import org.eclipse.lsp4j.RegistrationParams;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.UnregistrationParams;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * LSP client features - manages server capabilities (static and dynamic).
 */
public class LspClientFeatures {

    private final LspServerConfig config;

    // Capability registries
    private final ReferencesCapabilityRegistry referencesRegistry;
    private final DefinitionCapabilityRegistry definitionRegistry;
    private final DeclarationCapabilityRegistry declarationRegistry;
    private final ImplementationCapabilityRegistry implementationRegistry;
    private final HoverCapabilityRegistry hoverRegistry;
    private final CompletionCapabilityRegistry completionRegistry;
    private final DiagnosticCapabilityRegistry diagnosticRegistry;
    private final DocumentSymbolCapabilityRegistry documentSymbolRegistry;
    private final CodeActionCapabilityRegistry codeActionRegistry;
    private final RenameCapabilityRegistry renameRegistry;
    private final TypeDefinitionCapabilityRegistry typeDefinitionRegistry;
    private final FormattingCapabilityRegistry formattingRegistry;
    private final RangeFormattingCapabilityRegistry rangeFormattingRegistry;
    private final SignatureHelpCapabilityRegistry signatureHelpRegistry;
    private final CodeLensCapabilityRegistry codeLensRegistry;
    private final InlayHintCapabilityRegistry inlayHintRegistry;
    private final WorkspaceSymbolCapabilityRegistry workspaceSymbolRegistry;

    // Dynamic capabilities registered via client/registerCapability
    private final Map<String, Runnable> dynamicRegistrations = new ConcurrentHashMap<>();

    // File watcher patterns registered via workspace/didChangeWatchedFiles
    private final List<FileSystemWatcher> fileWatchers = new CopyOnWriteArrayList<>();

    public LspClientFeatures(LspServerConfig config) {
        this.config = config;
        this.referencesRegistry = new ReferencesCapabilityRegistry(this);
        this.definitionRegistry = new DefinitionCapabilityRegistry(this);
        this.declarationRegistry = new DeclarationCapabilityRegistry(this);
        this.implementationRegistry = new ImplementationCapabilityRegistry(this);
        this.hoverRegistry = new HoverCapabilityRegistry(this);
        this.completionRegistry = new CompletionCapabilityRegistry(this);
        this.diagnosticRegistry = new DiagnosticCapabilityRegistry(this);
        this.documentSymbolRegistry = new DocumentSymbolCapabilityRegistry(this);
        this.codeActionRegistry = new CodeActionCapabilityRegistry(this);
        this.renameRegistry = new RenameCapabilityRegistry(this);
        this.typeDefinitionRegistry = new TypeDefinitionCapabilityRegistry(this);
        this.formattingRegistry = new FormattingCapabilityRegistry(this);
        this.rangeFormattingRegistry = new RangeFormattingCapabilityRegistry(this);
        this.signatureHelpRegistry = new SignatureHelpCapabilityRegistry(this);
        this.codeLensRegistry = new CodeLensCapabilityRegistry(this);
        this.inlayHintRegistry = new InlayHintCapabilityRegistry(this);
        this.workspaceSymbolRegistry = new WorkspaceSymbolCapabilityRegistry();
    }

    /**
     * Set server capabilities from initialize response.
     */
    public void setServerCapabilities(ServerCapabilities serverCapabilities) {
        referencesRegistry.setServerCapabilities(serverCapabilities);
        definitionRegistry.setServerCapabilities(serverCapabilities);
        declarationRegistry.setServerCapabilities(serverCapabilities);
        implementationRegistry.setServerCapabilities(serverCapabilities);
        hoverRegistry.setServerCapabilities(serverCapabilities);
        completionRegistry.setServerCapabilities(serverCapabilities);
        diagnosticRegistry.setServerCapabilities(serverCapabilities);
        documentSymbolRegistry.setServerCapabilities(serverCapabilities);
        codeActionRegistry.setServerCapabilities(serverCapabilities);
        renameRegistry.setServerCapabilities(serverCapabilities);
        typeDefinitionRegistry.setServerCapabilities(serverCapabilities);
        formattingRegistry.setServerCapabilities(serverCapabilities);
        rangeFormattingRegistry.setServerCapabilities(serverCapabilities);
        signatureHelpRegistry.setServerCapabilities(serverCapabilities);
        codeLensRegistry.setServerCapabilities(serverCapabilities);
        inlayHintRegistry.setServerCapabilities(serverCapabilities);
        workspaceSymbolRegistry.setServerCapabilities(serverCapabilities);
    }

    /**
     * Check if the language server is enabled.
     * This can be controlled by user configuration.
     */
    public boolean isEnabled() {
        // TODO: Check user configuration to see if server is disabled
        // For now, always enabled
        return true;
    }

    /**
     * Check if the server supports a given capability for a file.
     *
     * @param capability the LSP capability to check
     * @param document   the language document
     * @return true if the capability is supported
     */
    public boolean supportsCapability(LspCapability capability, LanguageDocument document) {
        return switch (capability) {
            case REFERENCES -> referencesRegistry.isReferencesSupported(document);
            case DEFINITION -> definitionRegistry.isDefinitionSupported(document);
            case DECLARATION -> declarationRegistry.isDeclarationSupported(document);
            case IMPLEMENTATION -> implementationRegistry.isImplementationSupported(document);
            case HOVER -> hoverRegistry.isHoverSupported(document);
            case COMPLETION -> completionRegistry.isCompletionSupported(document);
            case DIAGNOSTIC -> diagnosticRegistry.isDiagnosticSupported(document);
            case DOCUMENT_SYMBOL -> documentSymbolRegistry.isDocumentSymbolSupported(document);
            case CODE_ACTION -> codeActionRegistry.isCodeActionSupported(document);
            case RENAME -> renameRegistry.isRenameSupported(document);
            case TYPE_DEFINITION -> typeDefinitionRegistry.isTypeDefinitionSupported(document);
            case FORMATTING -> formattingRegistry.isFormattingSupported(document);
            case RANGE_FORMATTING -> rangeFormattingRegistry.isRangeFormattingSupported(document);
            case SIGNATURE_HELP -> signatureHelpRegistry.isSignatureHelpSupported(document);
            case CODE_LENS -> codeLensRegistry.isCodeLensSupported(document);
            case INLAY_HINT -> inlayHintRegistry.isInlayHintSupported(document);
            case WORKSPACE_SYMBOL -> workspaceSymbolRegistry.isWorkspaceSymbolSupported();
        };
    }

    public boolean supportsCapability(LspCapability capability) {
        return switch (capability) {
            case WORKSPACE_SYMBOL -> workspaceSymbolRegistry.isWorkspaceSymbolSupported();
            default -> false;
        };
    }

    /**
     * Register a dynamic capability.
     * Called when the server sends a client/registerCapability notification.
     */
    public void registerCapability(RegistrationParams params) {
        params.getRegistrations().forEach(reg -> {
            String id = reg.getId();
            String method = reg.getMethod();
            Object registerOptions = reg.getRegisterOptions();

            if (!(registerOptions instanceof JsonObject)) {
                return;
            }

            JsonObject jsonOptions = (JsonObject) registerOptions;

            // Delegate to appropriate registry based on method
            switch (method) {
                case LspRequestConstants.TEXT_DOCUMENT_REFERENCES -> {
                    var options = referencesRegistry.registerCapability(jsonOptions);
                    dynamicRegistrations.put(id, () -> referencesRegistry.unregisterCapability(options));
                }
                case LspRequestConstants.TEXT_DOCUMENT_DEFINITION -> {
                    var options = definitionRegistry.registerCapability(jsonOptions);
                    dynamicRegistrations.put(id, () -> definitionRegistry.unregisterCapability(options));
                }
                case LspRequestConstants.TEXT_DOCUMENT_DECLARATION -> {
                    var options = declarationRegistry.registerCapability(jsonOptions);
                    dynamicRegistrations.put(id, () -> declarationRegistry.unregisterCapability(options));
                }
                case LspRequestConstants.TEXT_DOCUMENT_IMPLEMENTATION -> {
                    var options = implementationRegistry.registerCapability(jsonOptions);
                    dynamicRegistrations.put(id, () -> implementationRegistry.unregisterCapability(options));
                }
                case LspRequestConstants.TEXT_DOCUMENT_HOVER -> {
                    var options = hoverRegistry.registerCapability(jsonOptions);
                    dynamicRegistrations.put(id, () -> hoverRegistry.unregisterCapability(options));
                }
                case LspRequestConstants.TEXT_DOCUMENT_COMPLETION -> {
                    var options = completionRegistry.registerCapability(jsonOptions);
                    dynamicRegistrations.put(id, () -> completionRegistry.unregisterCapability(options));
                }
                case LspRequestConstants.TEXT_DOCUMENT_DIAGNOSTIC -> {
                    var options = diagnosticRegistry.registerCapability(jsonOptions);
                    dynamicRegistrations.put(id, () -> diagnosticRegistry.unregisterCapability(options));
                }
                case LspRequestConstants.TEXT_DOCUMENT_DOCUMENT_SYMBOL -> {
                    var options = documentSymbolRegistry.registerCapability(jsonOptions);
                    dynamicRegistrations.put(id, () -> documentSymbolRegistry.unregisterCapability(options));
                }
                case LspRequestConstants.TEXT_DOCUMENT_CODE_ACTION -> {
                    var options = codeActionRegistry.registerCapability(jsonOptions);
                    dynamicRegistrations.put(id, () -> codeActionRegistry.unregisterCapability(options));
                }
                case LspRequestConstants.TEXT_DOCUMENT_RENAME -> {
                    var options = renameRegistry.registerCapability(jsonOptions);
                    dynamicRegistrations.put(id, () -> renameRegistry.unregisterCapability(options));
                }
                case LspRequestConstants.TEXT_DOCUMENT_TYPE_DEFINITION -> {
                    var options = typeDefinitionRegistry.registerCapability(jsonOptions);
                    dynamicRegistrations.put(id, () -> typeDefinitionRegistry.unregisterCapability(options));
                }
                case LspRequestConstants.TEXT_DOCUMENT_FORMATTING -> {
                    var options = formattingRegistry.registerCapability(jsonOptions);
                    dynamicRegistrations.put(id, () -> formattingRegistry.unregisterCapability(options));
                }
                case LspRequestConstants.TEXT_DOCUMENT_RANGE_FORMATTING -> {
                    var options = rangeFormattingRegistry.registerCapability(jsonOptions);
                    dynamicRegistrations.put(id, () -> rangeFormattingRegistry.unregisterCapability(options));
                }
                case LspRequestConstants.TEXT_DOCUMENT_SIGNATURE_HELP -> {
                    var options = signatureHelpRegistry.registerCapability(jsonOptions);
                    dynamicRegistrations.put(id, () -> signatureHelpRegistry.unregisterCapability(options));
                }
                case LspRequestConstants.TEXT_DOCUMENT_CODE_LENS -> {
                    var options = codeLensRegistry.registerCapability(jsonOptions);
                    dynamicRegistrations.put(id, () -> codeLensRegistry.unregisterCapability(options));
                }
                case LspRequestConstants.TEXT_DOCUMENT_INLAY_HINT -> {
                    var options = inlayHintRegistry.registerCapability(jsonOptions);
                    dynamicRegistrations.put(id, () -> inlayHintRegistry.unregisterCapability(options));
                }
                case LspRequestConstants.WORKSPACE_DID_CHANGE_WATCHED_FILES -> {
                    DidChangeWatchedFilesRegistrationOptions options =
                            com.ibm.mcp.languagetools.utils.JsonUtils.toModel(jsonOptions, DidChangeWatchedFilesRegistrationOptions.class);
                    if (options != null && options.getWatchers() != null) {
                        List<FileSystemWatcher> watchers = options.getWatchers();
                        fileWatchers.addAll(watchers);
                        dynamicRegistrations.put(id, () -> fileWatchers.removeAll(watchers));
                    }
                }
            }
        });
    }

    /**
     * Unregister a dynamic capability.
     * Called when the server sends a client/unregisterCapability notification.
     */
    public void unregisterCapability(UnregistrationParams params) {
        params.getUnregisterations().forEach(unreg -> {
            String id = unreg.getId();
            Runnable unregisterHandler = dynamicRegistrations.remove(id);
            if (unregisterHandler != null) {
                unregisterHandler.run();
            }
        });
    }

    public LspServerConfig getConfig() {
        return config;
    }

    public ReferencesCapabilityRegistry getReferencesRegistry() {
        return referencesRegistry;
    }

    public List<FileSystemWatcher> getFileWatchers() {
        return fileWatchers;
    }
}
