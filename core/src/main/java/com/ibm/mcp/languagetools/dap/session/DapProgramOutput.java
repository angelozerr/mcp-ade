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
package com.ibm.mcp.languagetools.dap.session;

import org.eclipse.lsp4j.debug.OutputEventArguments;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingDeque;

/**
 * Captures and buffers program output (stdout/stderr/console) from a DAP debug session.
 * This is the output of the debugged program, not the DAP protocol traces.
 */
public class DapProgramOutput {

    /**
     * Single line of output using DAP types directly.
     */
    public record OutputLine(String category, String text) {
        @Override
        public String toString() {
            return text; // For simple concatenation
        }
    }

    private static final int MAX_LINES = 200;
    private final LinkedBlockingDeque<OutputLine> lines = new LinkedBlockingDeque<>(MAX_LINES);

    /**
     * Add output from a DAP OutputEvent.
     */
    public void addOutput(OutputEventArguments event) {
        if (event == null || event.getOutput() == null || event.getOutput().isEmpty()) {
            return;
        }

        String category = event.getCategory() != null ? event.getCategory() : "stdout";
        addRawOutput(category, event.getOutput());
    }

    /**
     * Add raw output text (e.g. from process stdout when the debug adapter
     * does not send OutputEvents).
     */
    public void addRawOutput(String category, String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        OutputLine line = new OutputLine(category, text);
        while (!lines.offerLast(line)) {
            lines.pollFirst();
        }
    }

    /**
     * Get all captured output as a single string (all categories mixed).
     */
    public String getAll() {
        List<OutputLine> snapshot = new ArrayList<>(lines);
        if (snapshot.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (OutputLine line : snapshot) {
            sb.append(line.text());
        }
        return sb.toString();
    }

    /**
     * Get all captured output with category prefixes for clarity.
     * Format: [stdout] text\n[stderr] error\n
     */
    public String getAllWithCategories() {
        List<OutputLine> snapshot = new ArrayList<>(lines);
        if (snapshot.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (OutputLine line : snapshot) {
            if (!"stdout".equals(line.category())) {
                sb.append("[").append(line.category()).append("] ");
            }
            sb.append(line.text());
        }
        return sb.toString();
    }

    /**
     * Get the number of lines currently buffered.
     */
    public int getLineCount() {
        return lines.size();
    }

    /**
     * Clear all buffered output.
     */
    public void clear() {
        lines.clear();
    }

    /**
     * Check if there is any output buffered.
     */
    public boolean hasOutput() {
        return !lines.isEmpty();
    }
}
