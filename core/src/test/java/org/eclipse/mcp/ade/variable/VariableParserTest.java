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
package org.eclipse.mcp.ade.variable;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class VariableParserTest {

    @Test
    void parseNull() {
        assertTrue(VariableParser.parse(null).isEmpty());
    }

    @Test
    void parseEmpty() {
        assertTrue(VariableParser.parse("").isEmpty());
    }

    @Test
    void parseNoVariables() {
        assertTrue(VariableParser.parse("/some/plain/path").isEmpty());
    }

    @Test
    void parseSingleVariable() {
        List<VariableExpression> result = VariableParser.parse("${serverHome}");
        assertEquals(1, result.size());
        VariableExpression expr = result.get(0);
        assertNull(expr.prefix());
        assertEquals("serverHome", expr.name());
        assertEquals(0, expr.start());
        assertEquals(13, expr.end());
    }

    @Test
    void parsePrefixedVariable() {
        List<VariableExpression> result = VariableParser.parse("${vscodeExtension:jetbrains.intellij-server}");
        assertEquals(1, result.size());
        VariableExpression expr = result.get(0);
        assertEquals("vscodeExtension", expr.prefix());
        assertEquals("jetbrains.intellij-server", expr.name());
        assertEquals(0, expr.start());
        assertEquals(44, expr.end());
    }

    @Test
    void parseMultipleVariables() {
        List<VariableExpression> result = VariableParser.parse("${serverHome}/bin:${serverHome}/lib");
        assertEquals(2, result.size());

        assertEquals("serverHome", result.get(0).name());
        assertEquals(0, result.get(0).start());
        assertEquals(13, result.get(0).end());

        assertEquals("serverHome", result.get(1).name());
        assertEquals(18, result.get(1).start());
        assertEquals(31, result.get(1).end());
    }

    @Test
    void parseMixedVariables() {
        String input = "${vscodeExtension:foo}/bin -d ${serverHome}/data";
        List<VariableExpression> result = VariableParser.parse(input);
        assertEquals(2, result.size());

        assertEquals("vscodeExtension", result.get(0).prefix());
        assertEquals("foo", result.get(0).name());

        assertNull(result.get(1).prefix());
        assertEquals("serverHome", result.get(1).name());
    }

    @Test
    void parseUnclosedVariable() {
        List<VariableExpression> result = VariableParser.parse("${unclosed");
        assertTrue(result.isEmpty());
    }

    @Test
    void parseValidFollowedByUnclosed() {
        List<VariableExpression> result = VariableParser.parse("${serverHome}/bin${");
        assertEquals(1, result.size());
        assertEquals("serverHome", result.get(0).name());
    }

    @Test
    void parseLegacySyntaxNotRecognized() {
        List<VariableExpression> result = VariableParser.parse("$SERVER_HOME$/bin");
        assertTrue(result.isEmpty());
    }

    @Test
    void parseDollarAloneNotRecognized() {
        List<VariableExpression> result = VariableParser.parse("price is $5");
        assertTrue(result.isEmpty());
    }

    @Test
    void parseEmptyVariableName() {
        List<VariableExpression> result = VariableParser.parse("${}");
        assertTrue(result.isEmpty());
    }

    @Test
    void parseVariableAtEnd() {
        List<VariableExpression> result = VariableParser.parse("path/${serverHome}");
        assertEquals(1, result.size());
        assertEquals("serverHome", result.get(0).name());
        assertEquals(5, result.get(0).start());
        assertEquals(18, result.get(0).end());
    }

    @Test
    void parseDottedVariableName() {
        List<VariableExpression> result = VariableParser.parse("${output.file.name}");
        assertEquals(1, result.size());
        assertNull(result.get(0).prefix());
        assertEquals("output.file.name", result.get(0).name());
    }
}
