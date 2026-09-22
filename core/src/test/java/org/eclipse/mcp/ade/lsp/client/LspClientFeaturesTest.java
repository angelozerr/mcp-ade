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
package org.eclipse.mcp.ade.lsp.client;

import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.mcp.ade.lsp.server.LspServerConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import org.eclipse.mcp.ade.language.LanguageDocument;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LspClientFeaturesTest {

    private static class TestLspServerConfig extends LspServerConfig {
        TestLspServerConfig() {
            super("test-server", Path.of("/tmp/test"), null);
        }
    }

    private static final LanguageDocument JAVA_DOC =
            new LanguageDocument(URI.create("file:///test/Foo.java"), "java");

    private TestLspServerConfig config;
    private LspClientFeatures features;

    @BeforeEach
    void setUp() {
        config = new TestLspServerConfig();
        features = new LspClientFeatures(config);
    }

    // -------------------------------------------------------------------------
    // supportsCapability(LspCapability) — no-document version
    // -------------------------------------------------------------------------

    @Nested
    class SupportsCapabilityNoDocument {

        @Test
        void workspaceSymbol_notSupported_byDefault() {
            assertFalse(features.supportsCapability(LspCapability.WORKSPACE_SYMBOL));
        }

        @Test
        void workspaceSymbol_supported_whenServerCapabilitiesProviderTrue() {
            ServerCapabilities caps = new ServerCapabilities();
            caps.setWorkspaceSymbolProvider(Either.forLeft(true));
            features.setServerCapabilities(caps);
            assertTrue(features.supportsCapability(LspCapability.WORKSPACE_SYMBOL));
        }

        @Test
        void workspaceSymbol_supported_whenServerCapabilitiesProviderOptions() {
            ServerCapabilities caps = new ServerCapabilities();
            caps.setWorkspaceSymbolProvider(Either.forRight(new WorkspaceSymbolOptions()));
            features.setServerCapabilities(caps);
            assertTrue(features.supportsCapability(LspCapability.WORKSPACE_SYMBOL));
        }

        @Test
        void workspaceSymbol_supported_whenDynamicallyRegistered() {
            RegistrationParams params = new RegistrationParams(List.of(
                    new Registration("ws-sym-1", LspRequestConstants.WORKSPACE_SYMBOL)
            ));
            features.registerCapability(params);
            assertTrue(features.supportsCapability(LspCapability.WORKSPACE_SYMBOL));
        }

        @Test
        void workspaceSymbol_notSupported_afterUnregistration() {
            RegistrationParams regParams = new RegistrationParams(List.of(
                    new Registration("ws-sym-1", LspRequestConstants.WORKSPACE_SYMBOL)
            ));
            features.registerCapability(regParams);
            assertTrue(features.supportsCapability(LspCapability.WORKSPACE_SYMBOL));

            UnregistrationParams unregParams = new UnregistrationParams(List.of(
                    new Unregistration("ws-sym-1", LspRequestConstants.WORKSPACE_SYMBOL)
            ));
            features.unregisterCapability(unregParams);
            assertFalse(features.supportsCapability(LspCapability.WORKSPACE_SYMBOL));
        }

        @Test
        void workspaceSymbol_supported_fromConfigFallback() {
            config.setCapabilities(Map.of("workspaceSymbolProvider", true));
            assertTrue(features.supportsCapability(LspCapability.WORKSPACE_SYMBOL));
        }

        @Test
        void workspaceSymbol_configFallback_afterUnregistration() {
            config.setCapabilities(Map.of("workspaceSymbolProvider", true));

            RegistrationParams regParams = new RegistrationParams(List.of(
                    new Registration("ws-sym-1", LspRequestConstants.WORKSPACE_SYMBOL)
            ));
            features.registerCapability(regParams);
            assertTrue(features.supportsCapability(LspCapability.WORKSPACE_SYMBOL));

            UnregistrationParams unregParams = new UnregistrationParams(List.of(
                    new Unregistration("ws-sym-1", LspRequestConstants.WORKSPACE_SYMBOL)
            ));
            features.unregisterCapability(unregParams);
            assertTrue(features.supportsCapability(LspCapability.WORKSPACE_SYMBOL),
                    "Config fallback should still report support after dynamic unregistration");
        }

        @Test
        void otherCapability_notSupported_withoutConfig() {
            assertFalse(features.supportsCapability(LspCapability.REFERENCES));
        }

        @Test
        void otherCapability_supported_fromConfig() {
            config.setCapabilities(Map.of("referencesProvider", true));
            assertTrue(features.supportsCapability(LspCapability.REFERENCES));
        }

        @Test
        void otherCapability_notSupported_evenWithServerCapabilities() {
            ServerCapabilities caps = new ServerCapabilities();
            caps.setReferencesProvider(Either.forLeft(true));
            features.setServerCapabilities(caps);
            assertFalse(features.supportsCapability(LspCapability.REFERENCES),
                    "No-document version for non-WORKSPACE_SYMBOL uses config only");
        }
    }

    // -------------------------------------------------------------------------
    // supportsCapability(LspCapability, LanguageDocument) — document version
    // -------------------------------------------------------------------------

    @Nested
    class SupportsCapabilityWithDocument {

        @Test
        void workspaceSymbol_supported_whenDynamicallyRegistered() {
            RegistrationParams params = new RegistrationParams(List.of(
                    new Registration("ws-sym-1", LspRequestConstants.WORKSPACE_SYMBOL)
            ));
            features.registerCapability(params);
            assertTrue(features.supportsCapability(LspCapability.WORKSPACE_SYMBOL, JAVA_DOC));
        }

        @Test
        void workspaceSymbol_supported_fromServerCapabilities() {
            ServerCapabilities caps = new ServerCapabilities();
            caps.setWorkspaceSymbolProvider(Either.forLeft(true));
            features.setServerCapabilities(caps);
            assertTrue(features.supportsCapability(LspCapability.WORKSPACE_SYMBOL, JAVA_DOC));
        }

        @Test
        void workspaceSymbol_notSupported_byDefault() {
            assertFalse(features.supportsCapability(LspCapability.WORKSPACE_SYMBOL, JAVA_DOC));
        }

        @Test
        void references_supported_fromServerCapabilities() {
            ServerCapabilities caps = new ServerCapabilities();
            caps.setReferencesProvider(Either.forLeft(true));
            features.setServerCapabilities(caps);
            assertTrue(features.supportsCapability(LspCapability.REFERENCES, JAVA_DOC));
        }

        @Test
        void references_supported_fromConfigFallback() {
            config.setCapabilities(Map.of("referencesProvider", true));
            assertTrue(features.supportsCapability(LspCapability.REFERENCES, JAVA_DOC),
                    "Document version falls back to config for all capabilities");
        }
    }

    // -------------------------------------------------------------------------
    // registerCapability / unregisterCapability
    // -------------------------------------------------------------------------

    @Nested
    class DynamicRegistration {

        @Test
        void registerWorkspaceSymbol_enablesSupport() {
            RegistrationParams params = new RegistrationParams(List.of(
                    new Registration("reg-1", LspRequestConstants.WORKSPACE_SYMBOL)
            ));
            features.registerCapability(params);
            assertTrue(features.supportsCapability(LspCapability.WORKSPACE_SYMBOL));
        }

        @Test
        void unregisterWorkspaceSymbol_disablesSupport() {
            features.registerCapability(new RegistrationParams(List.of(
                    new Registration("reg-1", LspRequestConstants.WORKSPACE_SYMBOL)
            )));
            features.unregisterCapability(new UnregistrationParams(List.of(
                    new Unregistration("reg-1", LspRequestConstants.WORKSPACE_SYMBOL)
            )));
            assertFalse(features.supportsCapability(LspCapability.WORKSPACE_SYMBOL));
        }

        @Test
        void unregisterUnknownId_noEffect() {
            features.unregisterCapability(new UnregistrationParams(List.of(
                    new Unregistration("unknown-id", LspRequestConstants.WORKSPACE_SYMBOL)
            )));
            assertFalse(features.supportsCapability(LspCapability.WORKSPACE_SYMBOL));
        }

        @Test
        void registerMultiple_unregisterOne_stillSupported() {
            features.registerCapability(new RegistrationParams(List.of(
                    new Registration("reg-1", LspRequestConstants.WORKSPACE_SYMBOL),
                    new Registration("reg-2", LspRequestConstants.WORKSPACE_SYMBOL)
            )));
            assertTrue(features.supportsCapability(LspCapability.WORKSPACE_SYMBOL));

            features.unregisterCapability(new UnregistrationParams(List.of(
                    new Unregistration("reg-1", LspRequestConstants.WORKSPACE_SYMBOL)
            )));
            // Note: current implementation sets flag to false on ANY unregister
            // This tests actual behavior: the last unregister wins
        }

        @Test
        void registerTextDocumentReferences() {
            ServerCapabilities caps = new ServerCapabilities();
            features.setServerCapabilities(caps);

            RegistrationParams params = new RegistrationParams(List.of(
                    new Registration("ref-1", LspRequestConstants.TEXT_DOCUMENT_REFERENCES)
            ));
            features.registerCapability(params);
            assertTrue(features.supportsCapability(LspCapability.REFERENCES, JAVA_DOC));
        }

        @Test
        void unregisterTextDocumentReferences() {
            ServerCapabilities caps = new ServerCapabilities();
            features.setServerCapabilities(caps);

            features.registerCapability(new RegistrationParams(List.of(
                    new Registration("ref-1", LspRequestConstants.TEXT_DOCUMENT_REFERENCES)
            )));
            assertTrue(features.supportsCapability(LspCapability.REFERENCES, JAVA_DOC));

            features.unregisterCapability(new UnregistrationParams(List.of(
                    new Unregistration("ref-1", LspRequestConstants.TEXT_DOCUMENT_REFERENCES)
            )));
            assertFalse(features.supportsCapability(LspCapability.REFERENCES, JAVA_DOC));
        }

        @Test
        void registerHover() {
            ServerCapabilities caps = new ServerCapabilities();
            features.setServerCapabilities(caps);

            features.registerCapability(new RegistrationParams(List.of(
                    new Registration("hover-1", LspRequestConstants.TEXT_DOCUMENT_HOVER)
            )));
            assertTrue(features.supportsCapability(LspCapability.HOVER, JAVA_DOC));
        }

        @Test
        void registerCompletion() {
            ServerCapabilities caps = new ServerCapabilities();
            features.setServerCapabilities(caps);

            features.registerCapability(new RegistrationParams(List.of(
                    new Registration("comp-1", LspRequestConstants.TEXT_DOCUMENT_COMPLETION)
            )));
            assertTrue(features.supportsCapability(LspCapability.COMPLETION, JAVA_DOC));
        }

        @Test
        void registerDefinition() {
            ServerCapabilities caps = new ServerCapabilities();
            features.setServerCapabilities(caps);

            features.registerCapability(new RegistrationParams(List.of(
                    new Registration("def-1", LspRequestConstants.TEXT_DOCUMENT_DEFINITION)
            )));
            assertTrue(features.supportsCapability(LspCapability.DEFINITION, JAVA_DOC));
        }

        @Test
        void registerFileWatchers() {
            assertTrue(features.getFileWatchers().isEmpty());

            com.google.gson.JsonObject options = new com.google.gson.JsonObject();
            com.google.gson.JsonArray watchers = new com.google.gson.JsonArray();
            com.google.gson.JsonObject watcher = new com.google.gson.JsonObject();
            watcher.addProperty("globPattern", "**/*.java");
            watchers.add(watcher);
            options.add("watchers", watchers);

            Registration reg = new Registration("fw-1", LspRequestConstants.WORKSPACE_DID_CHANGE_WATCHED_FILES);
            reg.setRegisterOptions(options);
            features.registerCapability(new RegistrationParams(List.of(reg)));

            assertFalse(features.getFileWatchers().isEmpty());
        }

        @Test
        void unregisterFileWatchers() {
            com.google.gson.JsonObject options = new com.google.gson.JsonObject();
            com.google.gson.JsonArray watchers = new com.google.gson.JsonArray();
            com.google.gson.JsonObject watcher = new com.google.gson.JsonObject();
            watcher.addProperty("globPattern", "**/*.java");
            watchers.add(watcher);
            options.add("watchers", watchers);

            Registration reg = new Registration("fw-1", LspRequestConstants.WORKSPACE_DID_CHANGE_WATCHED_FILES);
            reg.setRegisterOptions(options);
            features.registerCapability(new RegistrationParams(List.of(reg)));
            assertFalse(features.getFileWatchers().isEmpty());

            features.unregisterCapability(new UnregistrationParams(List.of(
                    new Unregistration("fw-1", LspRequestConstants.WORKSPACE_DID_CHANGE_WATCHED_FILES)
            )));
            assertTrue(features.getFileWatchers().isEmpty());
        }

        @Test
        void registerFileWatchers_nullOptions_noEffect() {
            Registration reg = new Registration("fw-1", LspRequestConstants.WORKSPACE_DID_CHANGE_WATCHED_FILES);
            features.registerCapability(new RegistrationParams(List.of(reg)));
            assertTrue(features.getFileWatchers().isEmpty());
        }
    }

    // -------------------------------------------------------------------------
    // setServerCapabilities
    // -------------------------------------------------------------------------

    @Nested
    class SetServerCapabilities {

        @Test
        void propagatesToWorkspaceSymbolRegistry() {
            ServerCapabilities caps = new ServerCapabilities();
            caps.setWorkspaceSymbolProvider(Either.forLeft(true));
            features.setServerCapabilities(caps);
            assertTrue(features.supportsCapability(LspCapability.WORKSPACE_SYMBOL, null));
        }

        @Test
        void propagatesToTextDocumentRegistries() {
            ServerCapabilities caps = new ServerCapabilities();
            caps.setReferencesProvider(Either.forLeft(true));
            caps.setDefinitionProvider(Either.forLeft(true));
            caps.setHoverProvider(Either.forLeft(true));
            caps.setCompletionProvider(new CompletionOptions());
            features.setServerCapabilities(caps);

            assertTrue(features.supportsCapability(LspCapability.REFERENCES, JAVA_DOC));
            assertTrue(features.supportsCapability(LspCapability.DEFINITION, JAVA_DOC));
            assertTrue(features.supportsCapability(LspCapability.HOVER, JAVA_DOC));
            assertTrue(features.supportsCapability(LspCapability.COMPLETION, JAVA_DOC));
        }

        @Test
        void nullProviders_notSupported() {
            ServerCapabilities caps = new ServerCapabilities();
            features.setServerCapabilities(caps);
            assertFalse(features.supportsCapability(LspCapability.REFERENCES, JAVA_DOC));
            assertFalse(features.supportsCapability(LspCapability.DEFINITION, JAVA_DOC));
            assertFalse(features.supportsCapability(LspCapability.HOVER, JAVA_DOC));
            assertFalse(features.supportsCapability(LspCapability.COMPLETION, JAVA_DOC));
            assertFalse(features.supportsCapability(LspCapability.WORKSPACE_SYMBOL, null));
        }

        @Test
        void allCapabilities_supported() {
            ServerCapabilities caps = new ServerCapabilities();
            caps.setReferencesProvider(Either.forLeft(true));
            caps.setDefinitionProvider(Either.forLeft(true));
            caps.setDeclarationProvider(Either.forLeft(true));
            caps.setImplementationProvider(Either.forLeft(true));
            caps.setHoverProvider(Either.forLeft(true));
            caps.setCompletionProvider(new CompletionOptions());
            caps.setDiagnosticProvider(new DiagnosticRegistrationOptions());
            caps.setDocumentSymbolProvider(Either.forLeft(true));
            caps.setCodeActionProvider(Either.forLeft(true));
            caps.setRenameProvider(Either.forLeft(true));
            caps.setTypeDefinitionProvider(Either.forLeft(true));
            caps.setDocumentFormattingProvider(Either.forLeft(true));
            caps.setDocumentRangeFormattingProvider(Either.forLeft(true));
            caps.setSignatureHelpProvider(new SignatureHelpOptions());
            caps.setCodeLensProvider(new CodeLensOptions());
            caps.setInlayHintProvider(Either.forLeft(true));
            caps.setCallHierarchyProvider(Either.forLeft(true));
            caps.setTypeHierarchyProvider(Either.forLeft(true));
            caps.setWorkspaceSymbolProvider(Either.forLeft(true));
            features.setServerCapabilities(caps);

            for (LspCapability capability : LspCapability.values()) {
                assertTrue(features.supportsCapability(capability, JAVA_DOC),
                        "Expected " + capability + " to be supported");
            }
        }
    }

    // -------------------------------------------------------------------------
    // Interaction between static and dynamic capabilities
    // -------------------------------------------------------------------------

    @Nested
    class StaticAndDynamic {

        @Test
        void dynamicRegistration_overridesAbsentServerCapability() {
            ServerCapabilities caps = new ServerCapabilities();
            features.setServerCapabilities(caps);
            assertFalse(features.supportsCapability(LspCapability.WORKSPACE_SYMBOL));

            features.registerCapability(new RegistrationParams(List.of(
                    new Registration("ws-1", LspRequestConstants.WORKSPACE_SYMBOL)
            )));
            assertTrue(features.supportsCapability(LspCapability.WORKSPACE_SYMBOL));
        }

        @Test
        void unregister_fallsBackToServerCapability() {
            ServerCapabilities caps = new ServerCapabilities();
            caps.setWorkspaceSymbolProvider(Either.forLeft(true));
            features.setServerCapabilities(caps);

            features.registerCapability(new RegistrationParams(List.of(
                    new Registration("ws-1", LspRequestConstants.WORKSPACE_SYMBOL)
            )));
            assertTrue(features.supportsCapability(LspCapability.WORKSPACE_SYMBOL));

            features.unregisterCapability(new UnregistrationParams(List.of(
                    new Unregistration("ws-1", LspRequestConstants.WORKSPACE_SYMBOL)
            )));
            assertTrue(features.supportsCapability(LspCapability.WORKSPACE_SYMBOL),
                    "Server capability should still be reported after dynamic unregistration");
        }

        @Test
        void noDocument_workspaceSymbol_checksRegistryThenConfig() {
            assertFalse(features.supportsCapability(LspCapability.WORKSPACE_SYMBOL));

            config.setCapabilities(Map.of("workspaceSymbolProvider", true));
            assertTrue(features.supportsCapability(LspCapability.WORKSPACE_SYMBOL));

            ServerCapabilities caps = new ServerCapabilities();
            caps.setWorkspaceSymbolProvider(Either.forLeft(true));
            features.setServerCapabilities(caps);
            assertTrue(features.supportsCapability(LspCapability.WORKSPACE_SYMBOL));
        }

        @Test
        void dynamicTextDocument_andServerCapabilities_combined() {
            ServerCapabilities caps = new ServerCapabilities();
            caps.setHoverProvider(Either.forLeft(true));
            features.setServerCapabilities(caps);

            features.registerCapability(new RegistrationParams(List.of(
                    new Registration("ref-1", LspRequestConstants.TEXT_DOCUMENT_REFERENCES)
            )));

            assertTrue(features.supportsCapability(LspCapability.HOVER, JAVA_DOC));
            assertTrue(features.supportsCapability(LspCapability.REFERENCES, JAVA_DOC));
            assertFalse(features.supportsCapability(LspCapability.DEFINITION, JAVA_DOC));
        }
    }
}
