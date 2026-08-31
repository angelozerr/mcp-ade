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
package org.eclipse.mcp.ade.language;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * Test with pattern expansion.
 */
class ExpandPatternsTest {

    @Test
    void noExpansion() {
        assertExpandPatterns("foo", "foo");
    }

    @Test
    void oneExpansion() {
        assertExpandPatterns("**/foo", "foo", "**/foo");
    }

    @Test
    void twoExpansion() {
        assertExpandPatterns("**/foo/**", "foo", "**/foo", "foo/**", "**/foo/**");
    }

    @Test
    void sixExpansion() {
        assertExpandPatterns("{**/node_modules/**,**/.git/**,**/bower_components/**}",
                "{node_modules,.git,bower_components}",
                "{node_modules,.git,bower_components/**}",
                "{node_modules,.git,**/bower_components}",
                "{node_modules,.git,**/bower_components/**}",
                "{node_modules,.git/**,bower_components}",
                "{node_modules,.git/**,bower_components/**}",
                "{node_modules,.git/**,**/bower_components}",
                "{node_modules,.git/**,**/bower_components/**}",
                "{node_modules,**/.git,bower_components}",
                "{node_modules,**/.git,bower_components/**}",
                "{node_modules,**/.git,**/bower_components}",
                "{node_modules,**/.git,**/bower_components/**}",
                "{node_modules,**/.git/**,bower_components}",
                "{node_modules,**/.git/**,bower_components/**}",
                "{node_modules,**/.git/**,**/bower_components}",
                "{node_modules,**/.git/**,**/bower_components/**}",
                "{node_modules/**,.git,bower_components}",
                "{node_modules/**,.git,bower_components/**}",
                "{node_modules/**,.git,**/bower_components}",
                "{node_modules/**,.git,**/bower_components/**}",
                "{node_modules/**,.git/**,bower_components}",
                "{node_modules/**,.git/**,bower_components/**}",
                "{node_modules/**,.git/**,**/bower_components}",
                "{node_modules/**,.git/**,**/bower_components/**}",
                "{node_modules/**,**/.git,bower_components}",
                "{node_modules/**,**/.git,bower_components/**}",
                "{node_modules/**,**/.git,**/bower_components}",
                "{node_modules/**,**/.git,**/bower_components/**}",
                "{node_modules/**,**/.git/**,bower_components}",
                "{node_modules/**,**/.git/**,bower_components/**}",
                "{node_modules/**,**/.git/**,**/bower_components}",
                "{node_modules/**,**/.git/**,**/bower_components/**}",
                "{**/node_modules,.git,bower_components}",
                "{**/node_modules,.git,bower_components/**}",
                "{**/node_modules,.git,**/bower_components}",
                "{**/node_modules,.git,**/bower_components/**}",
                "{**/node_modules,.git/**,bower_components}",
                "{**/node_modules,.git/**,bower_components/**}",
                "{**/node_modules,.git/**,**/bower_components}",
                "{**/node_modules,.git/**,**/bower_components/**}",
                "{**/node_modules,**/.git,bower_components}",
                "{**/node_modules,**/.git,bower_components/**}",
                "{**/node_modules,**/.git,**/bower_components}",
                "{**/node_modules,**/.git,**/bower_components/**}",
                "{**/node_modules,**/.git/**,bower_components}",
                "{**/node_modules,**/.git/**,bower_components/**}",
                "{**/node_modules,**/.git/**,**/bower_components}",
                "{**/node_modules,**/.git/**,**/bower_components/**}",
                "{**/node_modules/**,.git,bower_components}",
                "{**/node_modules/**,.git,bower_components/**}",
                "{**/node_modules/**,.git,**/bower_components}",
                "{**/node_modules/**,.git,**/bower_components/**}",
                "{**/node_modules/**,.git/**,bower_components}",
                "{**/node_modules/**,.git/**,bower_components/**}",
                "{**/node_modules/**,.git/**,**/bower_components}",
                "{**/node_modules/**,.git/**,**/bower_components/**}",
                "{**/node_modules/**,**/.git,bower_components}",
                "{**/node_modules/**,**/.git,bower_components/**}",
                "{**/node_modules/**,**/.git,**/bower_components}",
                "{**/node_modules/**,**/.git,**/bower_components/**}",
                "{**/node_modules/**,**/.git/**,bower_components}",
                "{**/node_modules/**,**/.git/**,bower_components/**}",
                "{**/node_modules/**,**/.git/**,**/bower_components}",
                "{**/node_modules/**,**/.git/**,**/bower_components/**}");
    }

    private static void assertExpandPatterns(String pattern, String... expectedPatterns) {
        List<String> actual = PathPatternMatcher.expandPatterns(pattern);
        Collections.sort(actual);
        List<String> expected = Arrays.asList(expectedPatterns);
        Collections.sort(expected);
        assertArrayEquals(expected.toArray(new String[0]), actual.toArray(new String[0]),
                "'" + pattern + "' pattern expansion should match [\"" + String.join("\",\"", actual) + "\"]");
    }
}
