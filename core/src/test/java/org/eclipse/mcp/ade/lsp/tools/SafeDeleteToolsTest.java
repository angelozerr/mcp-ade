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
package org.eclipse.mcp.ade.lsp.tools;

import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SafeDeleteToolsTest {

    // Symbol range: lines 5-10, characters 4-20
    private static final Range SYMBOL_RANGE = new Range(
            new Position(5, 4),
            new Position(10, 20)
    );

    @Test
    void referenceInsideSymbolRange() {
        Location ref = location(7, 10);
        assertTrue(SafeDeleteTools.isInsideRange(ref, SYMBOL_RANGE));
    }

    @Test
    void referenceBeforeSymbolRange() {
        Location ref = location(2, 5);
        assertFalse(SafeDeleteTools.isInsideRange(ref, SYMBOL_RANGE));
    }

    @Test
    void referenceAfterSymbolRange() {
        Location ref = location(15, 5);
        assertFalse(SafeDeleteTools.isInsideRange(ref, SYMBOL_RANGE));
    }

    @Test
    void referenceAtExactStartOfSymbolRange() {
        Location ref = location(5, 4);
        assertTrue(SafeDeleteTools.isInsideRange(ref, SYMBOL_RANGE));
    }

    @Test
    void referenceAtExactEndOfSymbolRange() {
        Location ref = location(10, 20);
        assertTrue(SafeDeleteTools.isInsideRange(ref, SYMBOL_RANGE));
    }

    @Test
    void referenceOnSameStartLineButBeforeStartCharacter() {
        Location ref = location(5, 2);
        assertFalse(SafeDeleteTools.isInsideRange(ref, SYMBOL_RANGE));
    }

    @Test
    void referenceOnSameEndLineButAfterEndCharacter() {
        Location ref = location(10, 25);
        assertFalse(SafeDeleteTools.isInsideRange(ref, SYMBOL_RANGE));
    }

    // --- helpers ---

    private static Location location(int line, int character) {
        Location loc = new Location();
        loc.setUri("file:///test/Test.java");
        loc.setRange(new Range(new Position(line, character), new Position(line, character + 5)));
        return loc;
    }
}
