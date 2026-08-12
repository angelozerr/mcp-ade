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
package com.ibm.mcp.languagetools.it.trace;

import java.util.Map;
import java.util.Queue;

/**
 * Parsed LSP trace data storing server responses keyed by method name,
 * and document content from {@code textDocument/didOpen} notifications.
 * <p>
 * Each method maps to a queue of response JSON strings, allowing multiple
 * calls to the same method to be replayed in order.
 */
public class LspTraceData {

    private final Map<String, Queue<String>> responsesByMethod;
    private final Map<String, String> openDocuments;

    public LspTraceData(Map<String, Queue<String>> responsesByMethod,
                        Map<String, String> openDocuments) {
        this.responsesByMethod = responsesByMethod;
        this.openDocuments = openDocuments;
    }

    /**
     * Get the documents that were opened (from {@code textDocument/didOpen} notifications).
     *
     * @return map of document URI to file content
     */
    public Map<String, String> getOpenDocuments() {
        return openDocuments;
    }

    /**
     * Get the next response JSON for the given LSP method.
     * <p>
     * Polls the queue, so each call returns the next recorded response.
     * Returns {@code null} if no more responses are available for the method.
     *
     * @param method the LSP method name (e.g., "textDocument/hover", "initialize")
     * @return the next response JSON string, or null if none available
     */
    public String getNextResponse(String method) {
        Queue<String> queue = responsesByMethod.get(method);
        if (queue == null || queue.isEmpty()) {
            return null;
        }
        return queue.poll();
    }

    /**
     * Check if there are any responses stored for the given method.
     *
     * @param method the LSP method name
     * @return true if at least one response is available
     */
    public boolean hasResponse(String method) {
        Queue<String> queue = responsesByMethod.get(method);
        return queue != null && !queue.isEmpty();
    }
}
