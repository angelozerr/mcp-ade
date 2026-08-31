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
package org.eclipse.mcp.ade.dap.configurations;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dynamic segment for ${address} - extracts IP address or hostname.
 */
public class AddressSegment extends DynamicSegment {

    private static final Pattern ADDRESS_PATTERN = Pattern.compile("^([a-zA-Z0-9.-]+)");

    public AddressSegment() {
        super("${address}", DynamicSegmentType.ADDRESS);
    }

    @Override
    public String matches(String input) {
        Matcher matcher = ADDRESS_PATTERN.matcher(input);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }
}
