/**
 * Shared tool rendering functions used by both the MCP Tools tab
 * and the Extension detail Tools tab.
 */

import { escapeHtml } from './trace-renderer.js';
import { registerActions } from './event-delegation.js';

let toolsCache = null;
let toolsLoading = null;

export async function ensureToolsLoaded() {
    if (toolsCache) return toolsCache;
    if (toolsLoading) return toolsLoading;
    toolsLoading = fetch('/api/admin/mcp/tools')
        .then(r => r.json())
        .then(tools => { toolsCache = tools; toolsLoading = null; return tools; })
        .catch(e => { toolsLoading = null; throw e; });
    return toolsLoading;
}

export function invalidateToolsCache() {
    toolsCache = null;
}

export function getToolsForExtension(extensionId) {
    if (!toolsCache) return [];
    return toolsCache.filter(t => t.extensionId === extensionId);
}

export function renderToolItem(tool, options) {
    const esc = escapeHtml;
    const argCount = tool.args ? tool.args.length : 0;
    const argsHtml = argCount > 0
        ? tool.args.map(arg =>
            `<span class="tool-arg ${arg.required ? 'tool-arg-required' : 'tool-arg-optional'}" title="${esc(arg.description || '')}&#10;Type: ${esc(arg.type)}${arg.required ? '' : ' (optional)'}">${esc(arg.name)}</span>`
        ).join('')
        : '<span class="text-dimmed font-sm">No arguments</span>';

    return `
        <div class="tool-item" data-action="toggleToolDetail">
            <div class="tool-header">
                <div class="tool-name">${esc(tool.name)}</div>
                <div class="tool-arg-count">${argCount === 0 ? 'No args' : argCount === 1 ? '1 arg' : argCount + ' args'}</div>
            </div>
            <div class="tool-description">${esc(tool.description || '')}</div>
            <div class="tool-args">${argsHtml}</div>
            <div class="tool-detail" style="display: none;">
                ${renderToolDetail(tool)}
            </div>
        </div>
    `;
}

function renderToolDetail(tool) {
    const esc = escapeHtml;
    if (!tool.args || tool.args.length === 0) {
        return '<div class="text-dimmed py-sm">No arguments</div>';
    }
    return `
        <table class="tool-args-table">
            <thead>
                <tr>
                    <th>Argument</th>
                    <th>Type</th>
                    <th>Required</th>
                    <th>Description</th>
                </tr>
            </thead>
            <tbody>
                ${tool.args.map(arg => `
                    <tr>
                        <td class="text-code">${esc(arg.name)}</td>
                        <td><span class="tool-type-badge">${esc(arg.type)}</span></td>
                        <td>${arg.required ? '<span class="text-success">Yes</span>' : '<span class="text-dimmed">No</span>'}</td>
                        <td class="text-secondary">${esc(arg.description || '')}</td>
                    </tr>
                `).join('')}
            </tbody>
        </table>
    `;
}

export function renderToolsGrouped(tools, filter, options) {
    const filtered = tools.filter(tool => {
        if (!filter) return true;
        const q = filter.toLowerCase();
        return tool.name.toLowerCase().includes(q)
            || (tool.description && tool.description.toLowerCase().includes(q))
            || (tool.group && tool.group.toLowerCase().includes(q))
            || (tool.subGroup && tool.subGroup.toLowerCase().includes(q));
    });

    const hierarchy = {};
    for (const tool of filtered) {
        const g = tool.group || 'Other';
        const sg = tool.subGroup || null;
        if (!hierarchy[g]) hierarchy[g] = {};
        const subKey = sg || '_ungrouped';
        if (!hierarchy[g][subKey]) hierarchy[g][subKey] = [];
        hierarchy[g][subKey].push(tool);
    }

    const esc = escapeHtml;
    const expanded = !!filter;
    const toggleIcon = expanded ? '&#9660;' : '&#9654;';
    const collapsedClass = expanded ? '' : ' collapsed';
    const bodyDisplay = expanded ? '' : ' style="display: none;"';

    const html = Object.entries(hierarchy).map(([group, subGroups]) => {
        const allGroupTools = Object.values(subGroups).flat();
        const groupToolCount = allGroupTools.length;
        const subGroupEntries = Object.entries(subGroups);
        const hasSubGroups = !(subGroupEntries.length === 1 && subGroupEntries[0][0] === '_ungrouped');

        let groupExtBadge = '';
        if (options?.showGroupExtensionBadge) {
            const extId = allGroupTools[0]?.extensionId;
            if (extId && allGroupTools.every(t => t.extensionId === extId)) {
                groupExtBadge = `<span class="tool-extension-badge" data-action="navigateToExtension" data-extension-id="${esc(extId)}" data-stop-propagation title="Open extension '${esc(extId)}'">🧩</span>`;
            }
        }

        let bodyHtml;
        if (hasSubGroups) {
            bodyHtml = subGroupEntries.map(([subKey, subTools]) => {
                const subName = subKey === '_ungrouped' ? 'Other' : subKey;
                const toolsHtml = subTools.map(tool => renderToolItem(tool, options)).join('');
                return `
                    <div class="tool-subgroup${collapsedClass}">
                        <div class="tool-subgroup-header" data-action="toggleToolGroup">
                            <span class="tool-group-toggle">${toggleIcon}</span>
                            <span class="tool-subgroup-name">${esc(subName)}</span>
                            <span class="tool-subgroup-count">${subTools.length}</span>
                        </div>
                        <div class="tool-group-body"${bodyDisplay}>
                            ${toolsHtml}
                        </div>
                    </div>
                `;
            }).join('');
        } else {
            bodyHtml = subGroupEntries[0][1].map(tool => renderToolItem(tool, options)).join('');
        }

        return `
            <div class="tool-group${collapsedClass}">
                <div class="tool-group-header" data-action="toggleToolGroup">
                    <span class="tool-group-toggle">${toggleIcon}</span>
                    <span class="tool-group-name">${esc(group)}</span>
                    <span class="tool-group-count">${groupToolCount}</span>
                    ${groupExtBadge}
                </div>
                <div class="tool-group-body"${bodyDisplay}>
                    ${bodyHtml}
                </div>
            </div>
        `;
    }).join('');

    return { html, total: tools.length, filtered: filtered.length };
}

function toggleToolDetail(el) {
    const item = el.closest('.tool-item');
    if (!item) return;
    const detail = item.querySelector('.tool-detail');
    if (!detail) return;
    const isVisible = detail.style.display !== 'none';
    detail.style.display = isVisible ? 'none' : 'block';
    item.classList.toggle('expanded', !isVisible);
}

function toggleToolGroup(headerEl) {
    const group = headerEl.closest('.tool-group, .tool-subgroup');
    if (!group) return;
    const body = group.querySelector('.tool-group-body');
    const toggle = headerEl.querySelector('.tool-group-toggle');
    if (!body) return;
    const isCollapsed = body.style.display === 'none';
    body.style.display = isCollapsed ? '' : 'none';
    toggle.innerHTML = isCollapsed ? '&#9660;' : '&#9654;';
    group.classList.toggle('collapsed', !isCollapsed);
}

const panelStates = {};

export function renderToolsPanel(containerId, tools, options) {
    if (!panelStates[containerId]) {
        panelStates[containerId] = { filter: '' };
    }
    const ps = panelStates[containerId];
    ps.tools = tools;
    ps.options = options;

    const container = document.getElementById(containerId);
    if (!container) return;

    container.innerHTML = `
        <div class="tools-panel" style="height: auto;">
            <div class="tools-toolbar">
                <input type="text" class="input-field tools-search" placeholder="Filter tools..."
                       data-action="filterToolsPanel" data-panel-id="${containerId}" />
                <span class="tools-count" id="${containerId}-count"></span>
            </div>
            <div class="tools-list" id="${containerId}-list"></div>
        </div>
    `;

    refreshToolsPanel(containerId);
}

function refreshToolsPanel(containerId) {
    const ps = panelStates[containerId];
    if (!ps || !ps.tools) return;

    const list = document.getElementById(`${containerId}-list`);
    const countEl = document.getElementById(`${containerId}-count`);
    if (!list) return;

    const { html, total, filtered } = renderToolsGrouped(ps.tools, ps.filter, ps.options);

    if (countEl) {
        countEl.textContent = ps.filter
            ? `${filtered} / ${total} tools`
            : `${total} tools`;
    }

    if (filtered === 0) {
        list.innerHTML = ps.filter
            ? '<div class="text-secondary p-lg">No tools matching filter</div>'
            : '<div class="text-secondary p-lg">No tools registered</div>';
    } else {
        list.innerHTML = html;
    }
}

registerActions('click', {
    toggleToolDetail: (el) => toggleToolDetail(el),
    toggleToolGroup: (el) => toggleToolGroup(el),
});

registerActions('input', {
    filterToolsPanel: (el) => {
        const panelId = el.dataset.panelId;
        if (panelStates[panelId]) {
            panelStates[panelId].filter = el.value;
            refreshToolsPanel(panelId);
        }
    },
});
