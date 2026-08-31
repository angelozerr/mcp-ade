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
package org.eclipse.mcp.ade.tools;

import io.quarkiverse.mcp.server.Cancellation;

import java.util.concurrent.CompletableFuture;

/**
 * Utility class for attaching MCP cancellation handlers to asynchronous operations.
 * <p>
 * This class provides helpers to integrate MCP (Model Context Protocol) cancellation
 * with Java CompletableFutures, enabling proper cleanup when clients cancel long-running
 * tool operations.
 * </p>
 * <p>
 * <strong>LSP4J Integration:</strong> When used with LSP4J-based DAP/LSP clients,
 * calling {@code future.cancel(true)} automatically triggers the protocol-level
 * {@code cancel} request to the language server or debug adapter, if the server
 * supports the {@code supportsCancelRequest} capability.
 * </p>
 *
 * @see io.quarkiverse.mcp.server.Cancellation
 * @see java.util.concurrent.CompletableFuture
 */
public class CancellationSupport {

    /**
     * Private constructor to prevent instantiation of utility class.
     */
    private CancellationSupport() {
        // Utility class - no instances
    }

    /**
     * Attaches a cancellation handler to a CompletableFuture.
     * <p>
     * When the MCP client cancels the request via the {@link Cancellation} token,
     * this method will call {@code future.cancel(true)} to interrupt the operation.
     * </p>
     * <p>
     * <strong>DAP/LSP Behavior:</strong> For futures returned by LSP4J-based DAP or LSP
     * operations, the underlying LSP4J framework automatically:
     * <ul>
     *   <li>Checks if the server supports {@code supportsCancelRequest} capability</li>
     *   <li>Sends a protocol-level {@code cancel} request with the correct {@code requestId}</li>
     *   <li>Handles partial results or cancellation errors from the server</li>
     *   <li>Interrupts the thread if the operation is blocking</li>
     * </ul>
     * </p>
     * <p>
     * <strong>Example Usage:</strong>
     * <pre>{@code
     * @Tool(description = "Evaluate an expression")
     * public CompletableFuture<EvaluateResponse> evaluate(
     *         String expression,
     *         Cancellation cancellation) {
     *     DapSession session = getSession();
     *     return executeWithCancellation(
     *         session.evaluate(expression, frameId),
     *         cancellation
     *     );
     * }
     * }</pre>
     * </p>
     *
     * @param <T>          the type of the future's result
     * @param future       the CompletableFuture to attach cancellation to (must not be null)
     * @param cancellation the MCP cancellation token, or null if cancellation is not supported
     * @return the same future instance, for method chaining
     * @throws NullPointerException if future is null
     */
    public static <T> CompletableFuture<T> executeWithCancellation(
            CompletableFuture<T> future,
            Cancellation cancellation) {
        // Register cancellation callback if cancellation token is provided
        if (cancellation != null) {
            cancellation.onCancelled(reason -> {
                // Cancel the future with interruption enabled
                // For LSP4J futures, this automatically sends a cancel request to the server
                future.cancel(true);
            });
        }
        return future;
    }
}
