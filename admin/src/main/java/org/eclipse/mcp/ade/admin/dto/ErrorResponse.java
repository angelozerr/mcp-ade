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
package org.eclipse.mcp.ade.admin.dto;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Common error response format for all admin endpoints (LSP, DAP, MCP).
 * Provides consistent error formatting with message and stack trace.
 */
@RegisterForReflection
public class ErrorResponse {
    public String message;      // Short error message (first line)
    public String type;         // Full exception type (e.g., java.lang.NullPointerException)
    public String stackTrace;   // Full stack trace (for folding in UI)

    public ErrorResponse(String message, String type, String stackTrace) {
        this.message = message;
        this.type = type;
        this.stackTrace = stackTrace;
    }

    /**
     * Create an ErrorResponse from an exception.
     */
    public static ErrorResponse fromException(Exception e) {
        String message = e.getMessage();
        if (message == null || message.isEmpty()) {
            message = e.getClass().getName();
        }

        // Get full stack trace
        StringBuilder stack = new StringBuilder();
        for (StackTraceElement frame : e.getStackTrace()) {
            stack.append("  at ").append(frame.toString()).append("\n");
        }

        return new ErrorResponse(
            message,
            e.getClass().getName(),
            stack.toString()
        );
    }

    // Legacy constructor for backward compatibility
    public ErrorResponse(String error) {
        this.message = error;
        this.type = "Error";
        this.stackTrace = "";
    }
}
