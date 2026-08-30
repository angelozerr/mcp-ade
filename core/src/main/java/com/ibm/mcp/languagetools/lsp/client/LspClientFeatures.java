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
import com.ibm.mcp.languagetools.lsp.client.capabilities.TextDocumentServerCapabilityRegistry;
import com.ibm.mcp.languagetools.lsp.client.capabilities.WorkspaceSymbolCapabilityRegistry;
import com.ibm.mcp.languagetools.lsp.server.LspServerConfig;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

import static com.ibm.mcp.languagetools.lsp.client.capabilities.TextDocumentServerCapabilityRegistry.hasCapability;

/**
 * LSP client features - manages server capabilities (static and dynamic).
 */
public class LspClientFeatures {

    private final LspServerConfig config;

    private final Map<LspCapability, TextDocumentServerCapabilityRegistry<?>> capabilityRegistries = new EnumMap<>(LspCapability.class);
    private final Map<String, TextDocumentServerCapabilityRegistry<?>> registriesByMethod = new ConcurrentHashMap<>();
    private final WorkspaceSymbolCapabilityRegistry workspaceSymbolRegistry;

    private final Map<String, Runnable> dynamicRegistrations = new ConcurrentHashMap<>();
    private final List<FileSystemWatcher> fileWatchers = new CopyOnWriteArrayList<>();

    public LspClientFeatures(LspServerConfig config) {
        this.config = config;
        register(LspCapability.REFERENCES, sc -> hasCapability(sc.getReferencesProvider()), ReferenceRegistrationOptions.class);
        register(LspCapability.DEFINITION, sc -> hasCapability(sc.getDefinitionProvider()), DefinitionRegistrationOptions.class);
        register(LspCapability.DECLARATION, sc -> hasCapability(sc.getDeclarationProvider()), DeclarationRegistrationOptions.class);
        register(LspCapability.IMPLEMENTATION, sc -> hasCapability(sc.getImplementationProvider()), ImplementationRegistrationOptions.class);
        register(LspCapability.HOVER, sc -> hasCapability(sc.getHoverProvider()), HoverRegistrationOptions.class);
        register(LspCapability.COMPLETION, sc -> sc.getCompletionProvider() != null, CompletionRegistrationOptions.class);
        register(LspCapability.DIAGNOSTIC, sc -> sc.getDiagnosticProvider() != null, DiagnosticRegistrationOptions.class);
        register(LspCapability.DOCUMENT_SYMBOL, sc -> hasCapability(sc.getDocumentSymbolProvider()), DocumentSymbolRegistrationOptions.class);
        register(LspCapability.CODE_ACTION, sc -> hasCapability(sc.getCodeActionProvider()), CodeActionRegistrationOptions.class);
        register(LspCapability.RENAME, sc -> hasCapability(sc.getRenameProvider()), RenameOptions.class);
        register(LspCapability.TYPE_DEFINITION, sc -> hasCapability(sc.getTypeDefinitionProvider()), TypeDefinitionRegistrationOptions.class);
        register(LspCapability.FORMATTING, sc -> hasCapability(sc.getDocumentFormattingProvider()), DocumentFormattingRegistrationOptions.class);
        register(LspCapability.RANGE_FORMATTING, sc -> hasCapability(sc.getDocumentRangeFormattingProvider()), DocumentRangeFormattingRegistrationOptions.class);
        register(LspCapability.SIGNATURE_HELP, sc -> sc.getSignatureHelpProvider() != null, SignatureHelpRegistrationOptions.class);
        register(LspCapability.CODE_LENS, sc -> sc.getCodeLensProvider() != null, CodeLensRegistrationOptions.class);
        register(LspCapability.INLAY_HINT, sc -> hasCapability(sc.getInlayHintProvider()), InlayHintRegistrationOptions.class);
        register(LspCapability.CALL_HIERARCHY, sc -> hasCapability(sc.getCallHierarchyProvider()), CallHierarchyRegistrationOptions.class);
        register(LspCapability.TYPE_HIERARCHY, sc -> hasCapability(sc.getTypeHierarchyProvider()), TypeHierarchyRegistrationOptions.class);
        this.workspaceSymbolRegistry = new WorkspaceSymbolCapabilityRegistry();
    }

    private <T extends TextDocumentRegistrationOptions> void register(
            LspCapability capability,
            Predicate<ServerCapabilities> predicate,
            Class<T> optionsClass) {
        var registry = new TextDocumentServerCapabilityRegistry<>(this, predicate, optionsClass);
        capabilityRegistries.put(capability, registry);
        registriesByMethod.put(capability.getMethod(), registry);
    }

    /**
     * Set server capabilities from initialize response.
     */
    public void setServerCapabilities(ServerCapabilities serverCapabilities) {
        capabilityRegistries.values().forEach(r -> r.setServerCapabilities(serverCapabilities));
        workspaceSymbolRegistry.setServerCapabilities(serverCapabilities);
    }

    /**
     * Check if the server supports a given capability for a file.
     * Checks in order: initialize response, dynamic registrations, server.json declaration.
     */
    public boolean supportsCapability(LspCapability capability, LanguageDocument document) {
        if (capability == LspCapability.WORKSPACE_SYMBOL) {
            return workspaceSymbolRegistry.isWorkspaceSymbolSupported();
        }
        var registry = capabilityRegistries.get(capability);
        if (registry != null && registry.isSupported(document)) {
            return true;
        }
        return config.hasCapability(capability.getCapabilityKey());
    }

    public boolean supportsCapability(LspCapability capability) {
        if (capability == LspCapability.WORKSPACE_SYMBOL) {
            return workspaceSymbolRegistry.isWorkspaceSymbolSupported();
        }
        return false;
    }

    /**
     * Register a dynamic capability.
     */
    public void registerCapability(RegistrationParams params) {
        params.getRegistrations().forEach(reg -> {
            String id = reg.getId();
            String method = reg.getMethod();
            Object registerOptions = reg.getRegisterOptions();

            if (!(registerOptions instanceof JsonObject jsonOptions)) {
                return;
            }

            if (LspRequestConstants.WORKSPACE_DID_CHANGE_WATCHED_FILES.equals(method)) {
                DidChangeWatchedFilesRegistrationOptions options =
                        com.ibm.mcp.languagetools.utils.JsonUtils.toModel(jsonOptions, DidChangeWatchedFilesRegistrationOptions.class);
                if (options != null && options.getWatchers() != null) {
                    List<FileSystemWatcher> watchers = options.getWatchers();
                    fileWatchers.addAll(watchers);
                    dynamicRegistrations.put(id, () -> fileWatchers.removeAll(watchers));
                }
                return;
            }

            var registry = registriesByMethod.get(method);
            if (registry != null) {
                var options = registry.registerCapability(jsonOptions);
                dynamicRegistrations.put(id, () -> registry.unregisterCapability(options));
            }
        });
    }

    /**
     * Unregister a dynamic capability.
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

    @SuppressWarnings("unchecked")
    public <T extends TextDocumentRegistrationOptions> TextDocumentServerCapabilityRegistry<T> getRegistry(LspCapability capability) {
        return (TextDocumentServerCapabilityRegistry<T>) capabilityRegistries.get(capability);
    }

    public List<FileSystemWatcher> getFileWatchers() {
        return fileWatchers;
    }
}
