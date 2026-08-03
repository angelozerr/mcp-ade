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
package com.ibm.mcp.jdtls.handlers.refactoring;

import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.internal.corext.refactoring.reorg.IReorgPolicy;
import org.eclipse.jdt.internal.corext.refactoring.reorg.JavaMoveProcessor;
import org.eclipse.jdt.internal.corext.refactoring.reorg.ReorgDestinationFactory;
import org.eclipse.jdt.internal.corext.refactoring.reorg.ReorgPolicyFactory;
import org.eclipse.ltk.core.refactoring.RefactoringStatus;
import org.eclipse.ltk.core.refactoring.participants.MoveRefactoring;

import com.ibm.mcp.jdtls.JdtUtils;

/**
 * Handler for "mcp.jdtls.moveTypeToPackage" command.
 *
 * <p>Arguments: [{uri, targetPackage, apply (optional)}]</p>
 *
 * <p>Moves a top-level compilation unit to a different package using the JDT LTK
 * move refactoring engine. Correctly handles:
 * <ul>
 *   <li>Moving the file to the target package directory</li>
 *   <li>Updating the package declaration</li>
 *   <li>Updating all import statements across the workspace</li>
 *   <li>Creating the target package if it doesn't exist</li>
 * </ul>
 * </p>
 */
public class MoveTypeToPackageHandler extends AbstractLTKRefactoringHandler {

    @Override
    public Object execute(List<Object> arguments, IProgressMonitor monitor) throws Exception {
        Map<String, Object> params = parseParams(arguments);
        String uri = (String) params.get("uri");

        if (uri == null) {
            return createErrorResult("Missing required argument: uri");
        }

        String targetPackageName = (String) params.get("targetPackage");
        if (targetPackageName == null) {
            return createErrorResult("Missing required argument: targetPackage");
        }

        ICompilationUnit cu = JdtUtils.getCompilationUnit(uri);
        if (cu == null) {
            return createErrorResult("Compilation unit not found");
        }

        String currentPackage = cu.getParent().getElementName();
        if (targetPackageName.equals(currentPackage)) {
            return createErrorResult("Type is already in package: " + targetPackageName);
        }

        IPackageFragmentRoot sourceRoot = (IPackageFragmentRoot) cu.getAncestor(IJavaElement.PACKAGE_FRAGMENT_ROOT);
        if (sourceRoot == null) {
            return createErrorResult("Cannot determine source folder");
        }

        IPackageFragment targetPackage = sourceRoot.getPackageFragment(targetPackageName);
        if (!targetPackage.exists()) {
            targetPackage = sourceRoot.createPackageFragment(targetPackageName, true, monitor);
        }

        try {
            IReorgPolicy.IMovePolicy policy = ReorgPolicyFactory.createMovePolicy(
                    new IResource[0],
                    new IJavaElement[]{cu});

            JavaMoveProcessor processor = new JavaMoveProcessor(policy);
            RefactoringStatus destinationStatus = processor.setDestination(ReorgDestinationFactory.createDestination(targetPackage));
            if (destinationStatus.hasFatalError()) {
                return createErrorResult("Invalid move destination: " + destinationStatus.getMessageMatchingSeverity(RefactoringStatus.FATAL));
            }
            processor.setUpdateReferences(true);

            MoveRefactoring refactoring = new MoveRefactoring(processor);

            return executeRefactoring(refactoring, params, monitor);
        } catch (IllegalArgumentException e) {
            return createErrorResult("Move refactoring setup failed: " + e.getMessage()
                    + ". Ensure the project is fully loaded (not in fast/lightweight mode).");
        }
    }
}
