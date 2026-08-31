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
package org.eclipse.mcp.ade.admin;

import org.eclipse.mcp.ade.Application;
import org.eclipse.mcp.ade.language.DocumentFilter;
import org.eclipse.mcp.ade.language.DocumentSelector;
import org.eclipse.mcp.ade.language.LanguageDefinition;
import org.eclipse.mcp.ade.language.LanguageRegistry;
import org.eclipse.mcp.ade.server.ServerConfigBase;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.*;

/**
 * REST endpoint for language admin operations.
 * Lists all registered languages (from languages.json and server documentSelectors)
 * with their associated servers.
 */
@Path("/api/admin/languages")
@Produces(MediaType.APPLICATION_JSON)
public class LanguageAdminResource {

    @Inject
    LanguageRegistry languageRegistry;

    @Inject
    Application application;

    @GET
    public List<Map<String, Object>> listLanguages() {
        Map<String, Map<String, Object>> fullMap = buildFullLanguageMap();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> lang : fullMap.values()) {
            result.add(buildLanguageSummary(lang));
        }
        return result;
    }

    @GET
    @Path("/{languageId}")
    public Response getLanguage(@PathParam("languageId") String languageId) {
        Map<String, Map<String, Object>> fullMap = buildFullLanguageMap();
        Map<String, Object> lang = fullMap.get(languageId);
        if (lang == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return Response.ok(lang).build();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> buildLanguageSummary(Map<String, Object> full) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("id", full.get("id"));
        List<?> aliases = (List<?>) full.get("aliases");
        if (aliases != null && !aliases.isEmpty()) {
            summary.put("aliases", aliases);
        }
        List<?> extensions = (List<?>) full.get("extensions");
        if (extensions != null && !extensions.isEmpty()) {
            summary.put("extensions", extensions);
        }
        summary.put("source", full.get("source"));
        Map<String, ?> servers = (Map<String, ?>) full.get("servers");
        if (servers != null && !servers.isEmpty()) {
            int count = 0;
            for (Object list : servers.values()) {
                if (list instanceof List<?> l) count += l.size();
            }
            if (count > 0) summary.put("serverCount", count);
        }
        return summary;
    }

    private Map<String, Map<String, Object>> buildFullLanguageMap() {
        Map<String, Map<String, Object>> languageMap = new LinkedHashMap<>();

        for (LanguageDefinition lang : languageRegistry.getAllLanguages()) {
            languageMap.put(lang.id(), buildLanguageDTO(lang));
        }

        for (var config : application.getLspServerConfigs()) {
            addServerAssociation(languageMap, config, "lsp");
        }
        for (var config : application.getDapServerConfigs()) {
            addServerAssociation(languageMap, config, "dap");
        }
        for (var config : application.getBspServerConfigs()) {
            addServerAssociation(languageMap, config, "bsp");
        }

        return languageMap;
    }

    @SuppressWarnings("unchecked")
    private void addServerAssociation(Map<String, Map<String, Object>> languageMap,
                                      ServerConfigBase config, String serverType) {
        DocumentSelector ds = config.getDocumentSelector();
        if (ds == null || ds.isEmpty()) return;

        for (DocumentFilter filter : ds.getFilters()) {
            String langId = filter.getLanguage();
            if (langId == null) continue;

            Map<String, Object> dto = languageMap.get(langId);
            if (dto == null) {
                dto = new LinkedHashMap<>();
                dto.put("id", langId);
                dto.put("aliases", List.of());
                dto.put("extensions", List.of());
                dto.put("filenames", List.of());
                dto.put("filenamePatterns", List.of());
                dto.put("source", "server");
                dto.put("servers", new LinkedHashMap<String, List<Map<String, Object>>>());
                languageMap.put(langId, dto);
            }

            Map<String, List<Map<String, Object>>> servers =
                    (Map<String, List<Map<String, Object>>>) dto.get("servers");
            Map<String, Object> serverInfo = new LinkedHashMap<>();
            serverInfo.put("id", config.getServerId());
            serverInfo.put("name", config.getName());
            if (filter.getPattern() != null) {
                serverInfo.put("pattern", filter.getPattern());
            }
            if (filter.getScheme() != null) {
                serverInfo.put("scheme", filter.getScheme());
            }
            servers.computeIfAbsent(serverType, k -> new ArrayList<>()).add(serverInfo);
        }
    }

    private Map<String, Object> buildLanguageDTO(LanguageDefinition lang) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("id", lang.id());
        dto.put("aliases", lang.aliases());
        dto.put("extensions", lang.extensions());
        dto.put("filenames", lang.filenames());
        dto.put("filenamePatterns", lang.filenamePatterns());
        if (lang.firstLine() != null) {
            dto.put("firstLine", lang.firstLine());
        }
        dto.put("source", "global");
        dto.put("servers", new LinkedHashMap<String, List<Map<String, String>>>());
        return dto;
    }
}
