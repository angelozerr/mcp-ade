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

/**
 * Centralized descriptions for MCP tool arguments.
 * Avoids duplication across multiple @ToolArg annotations.
 */
public final class ToolArgDescriptions {

    private ToolArgDescriptions() {
    }

    // Workspace location arguments
    public static final String CWD = "Project root path";

    // File URI arguments
    public static final String URI = "URI of the source file (e.g. file:// or ssh://)";

    // Position arguments
    public static final String POSITION_LINE = "Line number (0-based)";
    public static final String POSITION_CHARACTER = "Character position in the line (0-based)";

    public static final String CANCELLATION = "Cancellation operation";

    public static final String OPEN_DOCUMENT_HINT =
        " For multiple operations on the same file, use open_document first to avoid repeated open/close cycles, then close_document when done.";

    // Symbol name (alternative to uri + line + character)
    public static final String SYMBOL_NAME =
        "Symbol name to resolve (e.g., 'myMethod', 'MyClass.myMethod'). " +
        "Alternative to uri+line+character: when provided, the symbol is located via workspace/symbol search. " +
        "If both symbolName and uri+line+character are provided, symbolName takes precedence.";

    // Symbol filtering arguments
    public static final String SYMBOL_KIND = "Filter results by symbol kind (e.g., 'Class', 'Method', 'Field', 'Function', 'Variable', 'Interface', 'Enum', 'Constructor'). Case-insensitive.";
    public static final String PATH_PATTERN = "Glob pattern to filter results by file path (e.g., '*.java', 'src/main/**'). Applied to the file path portion of the URI.";
    public static final String CONTAINER_NAME = "Filter results by container name (e.g., class name for methods). Matches if the symbol's container name contains this value.";
    public static final String MAX_RESULTS = "Maximum number of results to return.";

    // Reference enrichment
    public static final String INCLUDE_ENCLOSING_SYMBOL =
        "When true, each reference includes the name and kind of its enclosing symbol (e.g., 'processOrder' Method). " +
        "Requires an extra documentSymbol request per file. Default: false.";

    // Refactoring arguments
    public static final String APPLY =
        "Whether to apply the changes to disk (true) or just return a preview (false, default)";
}
