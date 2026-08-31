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
package org.eclipse.mcp.ade.utils;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import org.eclipse.lsp4j.jsonrpc.json.MessageJsonHandler;

import java.util.HashMap;

public class JsonUtils {

    private static final Gson PRETTY_PRINT_GSON = new GsonBuilder()
            .setPrettyPrinting()
            .create();

    private static final Gson LSP4J_GSON = new MessageJsonHandler(new HashMap<>()).getGson();

    private JsonUtils() {
    }

    public static void configureGson(GsonBuilder builder) {
        builder.setObjectToNumberStrategy(com.google.gson.ToNumberPolicy.LONG_OR_DOUBLE);
    }

    public static Gson getLsp4jGson() {
        return LSP4J_GSON;
    }

    public static Gson getPrettyPrintGson() {
        return PRETTY_PRINT_GSON;
    }

    public static <T> T toModel(Object object, Class<T> clazz) {
        if (object == null) {
            return null;
        }
        if (clazz.isInstance(object)) {
            return clazz.cast(object);
        }
        if (object instanceof JsonElement) {
            return LSP4J_GSON.fromJson((JsonElement) object, clazz);
        }
        return LSP4J_GSON.fromJson(LSP4J_GSON.toJson(object), clazz);
    }
}
