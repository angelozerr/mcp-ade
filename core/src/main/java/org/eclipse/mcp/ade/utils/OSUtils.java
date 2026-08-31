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

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class OSUtils {

    private static final String WINDOWS = "windows";
    private static final String MAC = "mac";
    private static final String LINUX = "linux";
    private static final String UNIX = "unix";
    private static final String DEFAULT = "default";

    public static final String OS_KEY;
    public static final String ARCH_KEY;

    static {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            OS_KEY = WINDOWS;
        } else if (os.contains("mac")) {
            OS_KEY = MAC;
        } else {
            OS_KEY = LINUX;
        }

        String arch = System.getProperty("os.arch", "").toLowerCase();
        // Normalize JVM arch names to the keys used in installer.json
        if ("amd64".equals(arch) || "x86_64".equals(arch)) {
            ARCH_KEY = "x86_64";
        } else if ("aarch64".equals(arch) || "arm64".equals(arch)) {
            ARCH_KEY = "arm64";
        } else if ("x86".equals(arch) || "i386".equals(arch) || "i686".equals(arch)) {
            ARCH_KEY = "x86";
        } else {
            ARCH_KEY = arch;
        }
    }

    public static boolean isWindows() {
        return WINDOWS.equals(OS_KEY);
    }

    public static boolean isMac() {
        return MAC.equals(OS_KEY);
    }

    public static boolean isLinux() {
        return LINUX.equals(OS_KEY);
    }

    /**
     * Resolve an OS-and-architecture-specific string from a JSON property.
     * <p>
     * The property value can be:
     * <ul>
     *   <li>A simple string (returned as-is)</li>
     *   <li>An object with OS keys: {@code {"windows": "...", "mac": "...", "unix": "...", "default": "..."}}</li>
     *   <li>An object with OS keys whose values are arch objects:
     *       {@code {"windows": {"x86_64": "...", "arm64": "..."}, ...}}</li>
     * </ul>
     *
     * @param json     the JSON object containing the property
     * @param property the property name to resolve
     * @return the resolved string, or null if not found
     */
    public static String getStringFromOs(JsonObject json, String property) {
        if (!json.has(property)) {
            return null;
        }
        JsonElement element = json.get(property);
        if (element.isJsonPrimitive()) {
            return element.getAsString();
        }
        if (element.isJsonObject()) {
            return resolveOsAndArch(element.getAsJsonObject());
        }
        return null;
    }

    private static String resolveOsAndArch(JsonObject osMap) {
        JsonElement osValue = getOsValue(osMap);
        if (osValue == null) {
            return null;
        }
        if (osValue.isJsonPrimitive()) {
            return osValue.getAsString();
        }
        if (osValue.isJsonObject()) {
            return resolveArch(osValue.getAsJsonObject());
        }
        return null;
    }

    private static JsonElement getOsValue(JsonObject osMap) {
        if (osMap.has(OS_KEY)) {
            return osMap.get(OS_KEY);
        }
        // "unix" is an alias for "linux"
        if (LINUX.equals(OS_KEY) && osMap.has(UNIX)) {
            return osMap.get(UNIX);
        }
        if (osMap.has(DEFAULT)) {
            return osMap.get(DEFAULT);
        }
        return null;
    }

    private static String resolveArch(JsonObject archMap) {
        if (archMap.has(ARCH_KEY)) {
            JsonElement value = archMap.get(ARCH_KEY);
            if (value.isJsonPrimitive()) {
                return value.getAsString();
            }
        }
        // Try raw JVM os.arch as fallback
        String rawArch = System.getProperty("os.arch", "").toLowerCase();
        if (!rawArch.equals(ARCH_KEY) && archMap.has(rawArch)) {
            JsonElement value = archMap.get(rawArch);
            if (value.isJsonPrimitive()) {
                return value.getAsString();
            }
        }
        if (archMap.has(DEFAULT)) {
            JsonElement value = archMap.get(DEFAULT);
            if (value.isJsonPrimitive()) {
                return value.getAsString();
            }
        }
        return null;
    }

    /**
     * Resolves the full path of a command on the system PATH
     * using {@code where} (Windows) or {@code which} (Unix).
     *
     * @return the resolved absolute path, or null if not found
     */
    public static String resolveCommandPath(String command) {
        return resolveCommandPath(command, null);
    }

    /**
     * Resolves the full path of a command, optionally using a custom environment
     * (e.g. with the installer's runtime directory prepended to PATH).
     *
     * @return the resolved absolute path, or null if not found
     */
    public static String resolveCommandPath(String command, Map<String, String> env) {
        if (command == null || command.isEmpty()) {
            return null;
        }
        try {
            ProcessBuilder pb;
            if (isWindows()) {
                pb = new ProcessBuilder("cmd", "/c", "where", command);
            } else {
                pb = new ProcessBuilder("/usr/bin/which", command);
            }
            pb.redirectErrorStream(true);
            if (env != null) {
                pb.environment().putAll(env);
            }
            Process process = pb.start();
            String firstLine;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                firstLine = reader.readLine();
            }
            boolean finished = process.waitFor(5, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return null;
            }
            if (process.exitValue() != 0 || firstLine == null) {
                return null;
            }
            return firstLine.trim();
        } catch (Exception e) {
            return null;
        }
    }

    private OSUtils() {
    }
}
