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
package com.ibm.mcp.jdtls.handlers.project;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.internal.core.JavaModelManager;
import org.eclipse.jdt.internal.core.search.indexing.IndexManager;

import com.ibm.mcp.jdtls.ICommandHandler;

/**
 * Handler for "mcp.jdtls.getIndexingStatus" command.
 *
 * <p>Arguments: none</p>
 *
 * <p>Checks whether the JDT IndexManager has pending indexing jobs.
 * Returns the current indexing status and approximate number of pending jobs.</p>
 */
public class GetIndexingStatusHandler implements ICommandHandler {

    @Override
    public Object execute(List<Object> arguments, IProgressMonitor monitor) throws Exception {
        IndexManager indexManager = JavaModelManager.getIndexManager();

        Map<String, Object> result = new HashMap<>();
        // awaitingJobsCount() returns the number of jobs waiting in the queue
        int pendingJobs = indexManager.awaitingJobsCount();
        boolean indexing = pendingJobs > 0;

        result.put("indexing", indexing);
        result.put("pendingJobs", pendingJobs);
        return result;
    }
}
