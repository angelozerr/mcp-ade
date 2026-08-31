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
package org.eclipse.mcp.ade.lsp.server;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ReadyNotificationTest {

    // -- Method-only matching (IntelliJ style) --

    @Test
    void methodOnly_matchesCorrectMethod() {
        var rn = new LspServerConfig.ReadyNotification("intellij/ready-for-test");
        assertTrue(rn.matches("intellij/ready-for-test", null));
    }

    @Test
    void methodOnly_rejectsDifferentMethod() {
        var rn = new LspServerConfig.ReadyNotification("intellij/ready-for-test");
        assertFalse(rn.matches("other/notification", null));
    }

    // -- Object matching with JsonObject params --

    @Test
    void withMatch_matchesJsonObjectParams() {
        var rn = new LspServerConfig.ReadyNotification(
                "language/status", Map.of("type", "ServiceReady"));
        JsonObject params = new JsonObject();
        params.addProperty("type", "ServiceReady");
        params.addProperty("message", "Ready");
        assertTrue(rn.matches("language/status", params));
    }

    @Test
    void withMatch_rejectsWrongValue() {
        var rn = new LspServerConfig.ReadyNotification(
                "language/status", Map.of("type", "ServiceReady"));
        JsonObject params = new JsonObject();
        params.addProperty("type", "Starting");
        assertFalse(rn.matches("language/status", params));
    }

    @Test
    void withMatch_rejectsMissingField() {
        var rn = new LspServerConfig.ReadyNotification(
                "language/status", Map.of("type", "ServiceReady"));
        JsonObject params = new JsonObject();
        params.addProperty("message", "Ready");
        assertFalse(rn.matches("language/status", params));
    }

    @Test
    void withMatch_rejectsNullParams() {
        var rn = new LspServerConfig.ReadyNotification(
                "language/status", Map.of("type", "ServiceReady"));
        assertFalse(rn.matches("language/status", null));
    }

    // -- Pojo params (Gson serialization) --

    @Test
    void withMatch_matchesPojoParams() {
        var rn = new LspServerConfig.ReadyNotification(
                "language/status", Map.of("type", "ServiceReady"));
        StatusReport pojo = new StatusReport();
        pojo.type = "ServiceReady";
        pojo.message = "Ready";
        assertTrue(rn.matches("language/status", pojo));
    }

    @Test
    void withMatch_rejectsPojoWrongValue() {
        var rn = new LspServerConfig.ReadyNotification(
                "language/status", Map.of("type", "ServiceReady"));
        StatusReport pojo = new StatusReport();
        pojo.type = "Starting";
        pojo.message = "Indexing...";
        assertFalse(rn.matches("language/status", pojo));
    }

    // -- Dot notation (nested fields) --

    @Test
    void dotNotation_matchesNestedField() {
        var rn = new LspServerConfig.ReadyNotification(
                "server/status", Map.of("status.state", "ready"));
        JsonObject status = new JsonObject();
        status.addProperty("state", "ready");
        JsonObject params = new JsonObject();
        params.add("status", status);
        assertTrue(rn.matches("server/status", params));
    }

    @Test
    void dotNotation_rejectsWrongNestedValue() {
        var rn = new LspServerConfig.ReadyNotification(
                "server/status", Map.of("status.state", "ready"));
        JsonObject status = new JsonObject();
        status.addProperty("state", "indexing");
        JsonObject params = new JsonObject();
        params.add("status", status);
        assertFalse(rn.matches("server/status", params));
    }

    @Test
    void dotNotation_rejectsMissingNestedPath() {
        var rn = new LspServerConfig.ReadyNotification(
                "server/status", Map.of("status.state", "ready"));
        JsonObject params = new JsonObject();
        params.addProperty("other", "value");
        assertFalse(rn.matches("server/status", params));
    }

    @Test
    void dotNotation_matchesNestedPojo() {
        var rn = new LspServerConfig.ReadyNotification(
                "server/status", Map.of("status.state", "ready"));
        ServerStatusReport pojo = new ServerStatusReport();
        pojo.status = new NestedStatus();
        pojo.status.state = "ready";
        assertTrue(rn.matches("server/status", pojo));
    }

    // ===== StatusNotification tests =====

    @Test
    void status_extractsMessageFromJsonObject() {
        var sn = new LspServerConfig.StatusNotification("language/status", "message");
        JsonObject params = new JsonObject();
        params.addProperty("type", "Starting");
        params.addProperty("message", "Indexing workspace...");
        assertEquals("Indexing workspace...", sn.extractMessage("language/status", params));
    }

    @Test
    void status_returnsNullForWrongMethod() {
        var sn = new LspServerConfig.StatusNotification("language/status", "message");
        JsonObject params = new JsonObject();
        params.addProperty("message", "hello");
        assertNull(sn.extractMessage("other/method", params));
    }

    @Test
    void status_returnsNullForMissingField() {
        var sn = new LspServerConfig.StatusNotification("language/status", "message");
        JsonObject params = new JsonObject();
        params.addProperty("type", "Starting");
        assertNull(sn.extractMessage("language/status", params));
    }

    @Test
    void status_extractsFromPojo() {
        var sn = new LspServerConfig.StatusNotification("language/status", "message");
        StatusReport pojo = new StatusReport();
        pojo.type = "Starting";
        pojo.message = "Loading project...";
        assertEquals("Loading project...", sn.extractMessage("language/status", pojo));
    }

    @Test
    void status_extractsNestedFieldWithDotNotation() {
        var sn = new LspServerConfig.StatusNotification("server/status", "status.state");
        JsonObject status = new JsonObject();
        status.addProperty("state", "indexing");
        JsonObject params = new JsonObject();
        params.add("status", status);
        assertEquals("indexing", sn.extractMessage("server/status", params));
    }

    @Test
    void status_returnsNullForNullParams() {
        var sn = new LspServerConfig.StatusNotification("language/status", "message");
        assertNull(sn.extractMessage("language/status", null));
    }

    // -- Test POJOs --

    static class StatusReport {
        String type;
        String message;
    }

    static class ServerStatusReport {
        NestedStatus status;
    }

    static class NestedStatus {
        String state;
    }
}
