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
package org.eclipse.mcp.jdtls.handlers.framework;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.core.IAnnotation;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMemberValuePair;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.search.IJavaSearchConstants;
import org.eclipse.jdt.core.search.IJavaSearchScope;
import org.eclipse.jdt.core.search.SearchEngine;
import org.eclipse.jdt.core.search.SearchMatch;
import org.eclipse.jdt.core.search.SearchParticipant;
import org.eclipse.jdt.core.search.SearchPattern;
import org.eclipse.jdt.core.search.SearchRequestor;
import org.eclipse.mcp.jdtls.ICommandHandler;
import org.eclipse.mcp.jdtls.JdtUtils;

/**
 * Handler for "mcp.jdtls.getJpaModel" command.
 *
 * <p>Arguments: none (workspace-wide scan)</p>
 *
 * <p>Searches for JPA annotations (@Entity, @Table, @MappedSuperclass, etc.)
 * and extracts entity metadata including fields, column mappings, and
 * relationships.</p>
 *
 * <p>Copied and adapted from
 * <a href="https://github.com/pzalutski-pixel/javalens-mcp/blob/master/org.javalens.mcp/src/org/javalens/mcp/tools/GetJpaModelTool.java">javalens-mcp GetJpaModelTool</a>
 * for JDT.LS delegate command handler architecture.</p>
 */
public class GetJpaModelHandler implements ICommandHandler {

    private static final Set<String> ENTITY_ANNOTATIONS = Set.of(
            "Entity", "Table", "MappedSuperclass", "Embeddable"
    );

    private static final Set<String> RELATIONSHIP_ANNOTATIONS = Set.of(
            "ManyToOne", "OneToMany", "ManyToMany", "OneToOne"
    );

    @Override
    public Object execute(List<Object> arguments, IProgressMonitor monitor) throws Exception {
        List<Map<String, Object>> entities = new ArrayList<>();
        IJavaSearchScope scope = SearchEngine.createWorkspaceScope();

        // Search for @Entity annotation references
        SearchPattern pattern = SearchPattern.createPattern(
                "Entity",
                IJavaSearchConstants.ANNOTATION_TYPE,
                IJavaSearchConstants.ANNOTATION_TYPE_REFERENCE,
                SearchPattern.R_EXACT_MATCH | SearchPattern.R_CASE_SENSITIVE);

        if (pattern == null) {
            return Map.of("entities", entities, "count", 0);
        }

        // Also search for @MappedSuperclass and @Embeddable
        SearchPattern mappedPattern = SearchPattern.createPattern(
                "MappedSuperclass",
                IJavaSearchConstants.ANNOTATION_TYPE,
                IJavaSearchConstants.ANNOTATION_TYPE_REFERENCE,
                SearchPattern.R_EXACT_MATCH | SearchPattern.R_CASE_SENSITIVE);

        SearchPattern embeddablePattern = SearchPattern.createPattern(
                "Embeddable",
                IJavaSearchConstants.ANNOTATION_TYPE,
                IJavaSearchConstants.ANNOTATION_TYPE_REFERENCE,
                SearchPattern.R_EXACT_MATCH | SearchPattern.R_CASE_SENSITIVE);

        SearchPattern combinedPattern = pattern;
        if (mappedPattern != null) {
            combinedPattern = SearchPattern.createOrPattern(combinedPattern, mappedPattern);
        }
        if (embeddablePattern != null) {
            combinedPattern = SearchPattern.createOrPattern(combinedPattern, embeddablePattern);
        }

        SearchEngine engine = new SearchEngine();
        engine.search(
                combinedPattern,
                new SearchParticipant[]{SearchEngine.getDefaultSearchParticipant()},
                scope,
                new SearchRequestor() {
                    @Override
                    public void acceptSearchMatch(SearchMatch match) {
                        if (match.getElement() instanceof IType) {
                            IType type = (IType) match.getElement();
                            try {
                                Map<String, Object> entity = extractEntityInfo(type);
                                if (entity != null) {
                                    entities.add(entity);
                                }
                            } catch (Exception e) {
                                // Skip types that cannot be processed
                            }
                        }
                    }
                },
                monitor);

        Map<String, Object> result = new HashMap<>();
        result.put("entities", entities);
        result.put("count", entities.size());
        return result;
    }

    private Map<String, Object> extractEntityInfo(IType type) throws Exception {
        Map<String, Object> entity = new HashMap<>();
        entity.put("className", type.getFullyQualifiedName());

        // Extract table name from @Table annotation
        String tableName = type.getElementName(); // Default to class name
        IAnnotation tableAnnotation = type.getAnnotation("Table");
        if (tableAnnotation != null && tableAnnotation.exists()) {
            String name = extractAnnotationValue(tableAnnotation, "name");
            if (name != null && !name.isEmpty()) {
                tableName = name;
            }
        }
        entity.put("tableName", tableName);

        // Extract fields
        List<Map<String, Object>> fields = new ArrayList<>();
        List<Map<String, Object>> relationships = new ArrayList<>();

        for (IField field : type.getFields()) {
            Map<String, Object> fieldInfo = extractFieldInfo(field);
            if (fieldInfo != null) {
                fields.add(fieldInfo);
            }

            Map<String, Object> relInfo = extractRelationshipInfo(field);
            if (relInfo != null) {
                relationships.add(relInfo);
            }
        }

        entity.put("fields", fields);
        entity.put("relationships", relationships);

        if (type.getResource() != null) {
            entity.put("uri", JdtUtils.toFileUri(type.getResource()));
        }

        return entity;
    }

    private Map<String, Object> extractFieldInfo(IField field) throws Exception {
        Map<String, Object> fieldInfo = new HashMap<>();
        fieldInfo.put("name", field.getElementName());
        fieldInfo.put("type", field.getTypeSignature());

        // Check for @Id
        IAnnotation idAnnotation = field.getAnnotation("Id");
        if (idAnnotation != null && idAnnotation.exists()) {
            fieldInfo.put("isId", true);
        }

        // Check for @GeneratedValue
        IAnnotation genAnnotation = field.getAnnotation("GeneratedValue");
        if (genAnnotation != null && genAnnotation.exists()) {
            String strategy = extractAnnotationValue(genAnnotation, "strategy");
            if (strategy != null) {
                fieldInfo.put("generationStrategy", strategy);
            }
        }

        // Check for @Column
        IAnnotation columnAnnotation = field.getAnnotation("Column");
        if (columnAnnotation != null && columnAnnotation.exists()) {
            String columnName = extractAnnotationValue(columnAnnotation, "name");
            if (columnName != null) {
                fieldInfo.put("columnName", columnName);
            }
            String nullable = extractAnnotationValue(columnAnnotation, "nullable");
            if (nullable != null) {
                fieldInfo.put("nullable", Boolean.parseBoolean(nullable));
            }
            String length = extractAnnotationValue(columnAnnotation, "length");
            if (length != null) {
                fieldInfo.put("length", Integer.parseInt(length));
            }
        }

        return fieldInfo;
    }

    private Map<String, Object> extractRelationshipInfo(IField field) throws Exception {
        for (String relAnnotation : RELATIONSHIP_ANNOTATIONS) {
            IAnnotation annotation = field.getAnnotation(relAnnotation);
            if (annotation != null && annotation.exists()) {
                Map<String, Object> relInfo = new HashMap<>();
                relInfo.put("type", relAnnotation);
                relInfo.put("fieldName", field.getElementName());
                relInfo.put("targetType", field.getTypeSignature());

                // Check for @JoinColumn
                IAnnotation joinColumn = field.getAnnotation("JoinColumn");
                if (joinColumn != null && joinColumn.exists()) {
                    String joinColumnName = extractAnnotationValue(joinColumn, "name");
                    if (joinColumnName != null) {
                        relInfo.put("joinColumn", joinColumnName);
                    }
                    String referencedColumn = extractAnnotationValue(joinColumn, "referencedColumnName");
                    if (referencedColumn != null) {
                        relInfo.put("referencedColumnName", referencedColumn);
                    }
                }

                // Check for @JoinTable
                IAnnotation joinTable = field.getAnnotation("JoinTable");
                if (joinTable != null && joinTable.exists()) {
                    String joinTableName = extractAnnotationValue(joinTable, "name");
                    if (joinTableName != null) {
                        relInfo.put("joinTable", joinTableName);
                    }
                }

                // Check for fetch type and cascade
                String fetchType = extractAnnotationValue(annotation, "fetch");
                if (fetchType != null) {
                    relInfo.put("fetchType", fetchType);
                }
                String mappedBy = extractAnnotationValue(annotation, "mappedBy");
                if (mappedBy != null) {
                    relInfo.put("mappedBy", mappedBy);
                }

                return relInfo;
            }
        }
        return null;
    }

    private String extractAnnotationValue(IAnnotation annotation, String memberName) throws Exception {
        IMemberValuePair[] pairs = annotation.getMemberValuePairs();
        for (IMemberValuePair pair : pairs) {
            if (memberName.equals(pair.getMemberName())) {
                Object value = pair.getValue();
                return value != null ? String.valueOf(value) : null;
            }
        }
        return null;
    }
}
