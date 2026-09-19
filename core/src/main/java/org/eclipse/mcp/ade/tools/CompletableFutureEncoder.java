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

import io.quarkiverse.mcp.server.ToolResponse;
import io.quarkiverse.mcp.server.ToolResponseEncoder;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Encoder for async tool responses (CompletableFuture / Uni).
 *
 * <p>quarkus-mcp-server 2.0.1 wraps CompletableFuture returns into
 * {@code Uni<CompletionStage>} before reaching the encoder, so we
 * must handle both {@link Uni} and {@link CompletableFuture} types.</p>
 */
@ApplicationScoped
@SuppressWarnings("rawtypes")
public class CompletableFutureEncoder implements ToolResponseEncoder<Object> {

    @Override
    public boolean supports(Class<?> runtimeType) {
        return Uni.class.isAssignableFrom(runtimeType)
                || CompletableFuture.class.isAssignableFrom(runtimeType);
    }

    @Override
    public ToolResponse encode(Object value) {
        try {
            Object result;
            if (value instanceof Uni<?> uni) {
                result = uni.await().indefinitely();
            } else if (value instanceof CompletableFuture<?> cf) {
                result = cf.join();
            } else {
                result = value;
            }
            return ToolResponse.success(result.toString());
        } catch (CompletionException ex) {
            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
            return ToolResponse.error(cause.getMessage());
        } catch (Exception ex) {
            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
            return ToolResponse.error(cause.getMessage());
        }
    }
}
