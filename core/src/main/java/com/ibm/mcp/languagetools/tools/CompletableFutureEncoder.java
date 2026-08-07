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
package com.ibm.mcp.languagetools.tools;

import io.quarkiverse.mcp.server.ToolResponse;
import io.quarkiverse.mcp.server.ToolResponseEncoder;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Encoder for CompletableFuture<String> tool responses.
 * Allows tools to return CompletableFuture<String> instead of blocking with .join().
 */
@ApplicationScoped
public class CompletableFutureEncoder implements ToolResponseEncoder<CompletableFuture> {

    @Override
    public boolean supports(Class<?> runtimeType) {
        return CompletableFuture.class.equals(runtimeType);
    }

    @Override
    public ToolResponse encode(CompletableFuture value) {
        try {
            return ToolResponse.success(value.join().toString());
        } catch (CompletionException ex) {
            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
            return ToolResponse.error(cause.getMessage());
        }
    }
}
