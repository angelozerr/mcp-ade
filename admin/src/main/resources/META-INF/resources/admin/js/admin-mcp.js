import { state, updateSearchBoxVisibility } from './shared-state.js';
import {
    renderTraceControls, updateTraceControls, renderTracesInContainer,
    getCurrentSearchQuery, escapeHtml, initTraceContainer, toggleAllTraces
} from './trace-renderer.js';
import { registerActions } from './event-delegation.js';
import { renderActivity, updateActivityToggleUI } from './admin-activity.js';

let mcpTraces = [];
let mcpTraceLevel = 'off';
let mcpClients = [];
let mcpAllFolded = true;
let selectedMcpClient = null;
let mcpTracesByClient = {};
let mcpTracesLoaded = false;
let mcpTools = [];
let mcpToolsLoaded = false;
let mcpToolsFilter = '';

export async function loadMcpClients() {
    try {
        const response = await fetch('/api/admin/mcp/clients');
        const newClients = await response.json();

        if (JSON.stringify(newClients) !== JSON.stringify(mcpClients)) {
            mcpClients = newClients;
            renderMcpClients();

            if (mcpClients.length > 0 && !selectedMcpClient) {
                selectMcpClient(mcpClients[0].id);
            }

            if (selectedMcpClient) {
                const stillExists = mcpClients.find(c => c.id === selectedMcpClient);
                if (!stillExists) {
                    selectedMcpClient = null;
                    if (mcpClients.length > 0) {
                        selectMcpClient(mcpClients[0].id);
                    }
                }
            }
        }
    } catch (e) {
        console.error('Failed to load MCP clients:', e);
        document.getElementById('mcp-clients-list').innerHTML =
            '<div class="text-secondary p-lg">Failed to load clients</div>';
    }
}

function renderMcpClients() {
    const list = document.getElementById('mcp-clients-list');
    if (!list) return;

    if (mcpClients.length === 0) {
        list.innerHTML = '<div class="text-secondary p-lg">No clients connected</div>';
        return;
    }

    list.innerHTML = mcpClients.map(client => {
        const shortId = client.id.substring(0, 8) + '...';

        return `
            <div class="workspace-item cursor-pointer ${client.id === selectedMcpClient ? 'active' : ''}"
                 data-action="selectMcpClient" data-client-id="${client.id}"
                 title="${escapeHtml(client.id)}">
                <div class="mb-xs font-bold">
                    📱 ${escapeHtml(client.name)}
                </div>
                <div class="text-dimmed font-sm" style="padding-left: 1.5rem;">
                    Session: ${escapeHtml(shortId)}
                </div>
            </div>
        `;
    }).join('');

    if (state.currentTab === 'mcp-traces') {
        if (!selectedMcpClient && mcpClients.length > 0) {
            selectMcpClient(mcpClients[0].id);
        } else if (selectedMcpClient) {
            const stillExists = mcpClients.find(c => c.id === selectedMcpClient);
            if (!stillExists) {
                if (mcpClients.length > 0) {
                    selectMcpClient(mcpClients[0].id);
                } else {
                    selectedMcpClient = null;
                    loadMcpTracesConsole();
                }
            }
        }
    }
}

export function selectMcpClient(clientId) {
    selectedMcpClient = clientId;
    renderMcpClients();
    loadInitialMcpTraces();
    if (state.currentTab === 'mcp-traces') {
        loadMcpConsole(clientId);
    }
}

function loadInitialMcpTraces() {
    if (mcpTracesLoaded) return;
    mcpTracesLoaded = true;
}

export function loadMcpTracesConsole() {
    const savedMcpLevel = state.traceLevels['mcp'];
    mcpTraceLevel = savedMcpLevel || 'off';
    const consoleArea = document.getElementById('console-area');

    consoleArea.innerHTML = `
        <div class="console-wrapper">
            <div class="console-header">
                <div class="console-tabs">
                    <button class="tab-button active" data-action="switchMcpConsoleTab" data-tab="traces">Traces</button>
                    <button class="tab-button" data-action="switchMcpConsoleTab" data-tab="tools">Tools</button>
                    <button class="tab-button" data-action="switchMcpConsoleTab" data-tab="activity">Activity</button>
                </div>
                <div class="console-controls" id="mcp-traces-controls">
                    ${renderTraceControls('mcp-trace', mcpTraceLevel, 'changeMcpTraceLevel')}
                </div>
                <div class="console-controls" id="mcp-activity-controls" style="display: none;">
                    <label class="toggle-switch">
                        <input type="checkbox" id="activity-toggle-checkbox" data-action="toggleActivity">
                        <span class="toggle-slider"></span>
                    </label>
                    <button data-action="foldAllActivity">Fold All</button>
                    <button data-action="clearActivity">Clear</button>
                </div>
            </div>
            <div class="tab-content">
                <div id="mcp-traces-tab" class="tab-panel active">
                    <div class="placeholder">
                        &#8592; Select an AI client to view MCP traces
                    </div>
                </div>
                <div id="mcp-tools-tab" class="tab-panel">
                    <div class="mcp-tools-panel">
                        <div class="mcp-tools-toolbar">
                            <input type="text" class="input-field mcp-tools-search" placeholder="Filter tools..."
                                   data-action="filterMcpTools" />
                            <span class="mcp-tools-count" id="mcp-tools-count"></span>
                        </div>
                        <div class="mcp-tools-list" id="mcp-tools-list"></div>
                    </div>
                </div>
                <div id="mcp-activity-tab" class="tab-panel">
                    <div class="activity-list" id="mcp-activity-content">
                        <div class="text-secondary p-lg">No operations recorded yet</div>
                    </div>
                </div>
            </div>
        </div>
    `;
}

export function loadMcpConsole(clientId) {
    const savedMcpLevel2 = state.traceLevels['mcp'];
    mcpTraceLevel = savedMcpLevel2 || 'off';

    const consoleArea = document.getElementById('console-area');

    const client = mcpClients.find(c => c.id === clientId);
    const clientName = client ? client.name : 'MCP Client';

    consoleArea.innerHTML = `
        <div class="console-wrapper">
            <div class="console-header">
                <div class="console-tabs">
                    <button class="tab-button active" data-action="switchMcpConsoleTab" data-tab="traces">Traces</button>
                    <button class="tab-button" data-action="switchMcpConsoleTab" data-tab="tools">Tools</button>
                    <button class="tab-button" data-action="switchMcpConsoleTab" data-tab="activity">Activity</button>
                </div>
                <div class="console-controls" id="mcp-traces-controls">
                    ${renderTraceControls('mcp-trace', mcpTraceLevel, 'changeMcpTraceLevel', {
                        foldAction: 'toggleAllMcpTraces',
                        clearAction: 'clearMcpConsole'
                    })}
                </div>
                <div class="console-controls" id="mcp-activity-controls" style="display: none;">
                    <label class="toggle-switch">
                        <input type="checkbox" id="activity-toggle-checkbox" data-action="toggleActivity">
                        <span class="toggle-slider"></span>
                    </label>
                    <button data-action="foldAllActivity">Fold All</button>
                    <button data-action="clearActivity">Clear</button>
                </div>
            </div>
            <div class="tab-content">
                <div id="mcp-traces-tab" class="tab-panel active">
                    <div class="console" id="mcp-console-output" tabindex="0"></div>
                </div>
                <div id="mcp-tools-tab" class="tab-panel">
                    <div class="mcp-tools-panel">
                        <div class="mcp-tools-toolbar">
                            <input type="text" class="input-field mcp-tools-search" placeholder="Filter tools..."
                                   data-action="filterMcpTools" />
                            <span class="mcp-tools-count" id="mcp-tools-count"></span>
                        </div>
                        <div class="mcp-tools-list" id="mcp-tools-list"></div>
                    </div>
                </div>
                <div id="mcp-activity-tab" class="tab-panel">
                    <div class="activity-list" id="mcp-activity-content">
                        <div class="text-secondary p-lg">No operations recorded yet</div>
                    </div>
                </div>
            </div>
        </div>
    `;

    renderMcpConsole();
    initTraceContainer('mcp-console-output');
}

async function changeMcpTraceLevel(newLevel) {
    mcpTraceLevel = newLevel;
    state.traceLevels['mcp'] = newLevel;
    updateTraceControls('mcp-trace', newLevel);
    renderMcpConsole();

    try {
        await fetch('/api/admin/traces/mcp', {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ traceLevel: newLevel })
        });
    } catch (err) {
        console.error('Failed to set MCP trace level:', err);
    }
}

function switchMcpConsoleTab(tab, clickedBtn) {
    document.querySelectorAll('#console-area .tab-button').forEach(btn => {
        btn.classList.remove('active');
    });
    if (clickedBtn) {
        clickedBtn.classList.add('active');
    }

    document.querySelectorAll('#console-area .tab-panel').forEach(panel => {
        panel.classList.remove('active');
    });

    const tracesControls = document.getElementById('mcp-traces-controls');
    const activityControls = document.getElementById('mcp-activity-controls');

    if (tab === 'traces') {
        document.getElementById('mcp-traces-tab').classList.add('active');
        if (tracesControls) tracesControls.style.display = 'flex';
        if (activityControls) activityControls.style.display = 'none';
        updateSearchBoxVisibility(true);
    } else if (tab === 'tools') {
        document.getElementById('mcp-tools-tab').classList.add('active');
        if (tracesControls) tracesControls.style.display = 'none';
        if (activityControls) activityControls.style.display = 'none';
        updateSearchBoxVisibility(false);
        loadMcpTools();
    } else if (tab === 'activity') {
        document.getElementById('mcp-activity-tab').classList.add('active');
        if (tracesControls) tracesControls.style.display = 'none';
        if (activityControls) activityControls.style.display = 'flex';
        updateSearchBoxVisibility(false);
        updateActivityToggleUI();
        renderActivity();
    }
}

export function renderMcpConsole() {
    const clientTraces = mcpTracesByClient[selectedMcpClient] || [];
    renderTracesInContainer('mcp-console-output', clientTraces, mcpTraceLevel, '');
}

export function renderMcpConsoleWithHighlights() {
    const clientTraces = mcpTracesByClient[selectedMcpClient] || [];
    renderTracesInContainer('mcp-console-output', clientTraces, mcpTraceLevel, getCurrentSearchQuery());
}

async function clearMcpConsole() {
    try {
        await fetch('/api/admin/traces/mcp', { method: 'DELETE' });

        if (selectedMcpClient) {
            mcpTracesByClient[selectedMcpClient] = [];
        }

        renderMcpConsole();
    } catch (error) {
        console.error('Failed to clear MCP traces:', error);
    }
}

export function handleMcpTrace(trace) {
    const connectionId = trace.connectionId;

    if (!mcpTracesByClient[connectionId]) {
        mcpTracesByClient[connectionId] = [];
    }

    if (trace.messageType === 'UPDATE') {
        const traces = mcpTracesByClient[connectionId];
        const lastTrace = traces[traces.length - 1];
        if (lastTrace && lastTrace.messageType === 'UPDATE') {
            traces[traces.length - 1] = trace;
        } else {
            traces.push(trace);
        }
    } else {
        mcpTracesByClient[connectionId].push(trace);
    }

    if (selectedMcpClient === connectionId) {
        renderMcpConsole();
    }
}

export function handleMcpClientsUpdate(newClients) {
    if (JSON.stringify(newClients) !== JSON.stringify(mcpClients)) {
        mcpClients = newClients;
        renderMcpClients();

        if (mcpClients.length > 0 && !selectedMcpClient) {
            selectMcpClient(mcpClients[0].id);
        }

        if (selectedMcpClient) {
            const stillExists = mcpClients.find(c => c.id === selectedMcpClient);
            if (!stillExists) {
                selectedMcpClient = null;
                if (mcpClients.length > 0) {
                    selectMcpClient(mcpClients[0].id);
                } else if (state.currentTab === 'mcp-traces') {
                    loadMcpTracesConsole();
                }
            }
        }
    }
}

export function getMcpClients() {
    return mcpClients;
}

export function getSelectedMcpClient() {
    return selectedMcpClient;
}

export function getMcpTracesByClient() {
    return mcpTracesByClient;
}

export function getMcpTraceLevel() {
    return mcpTraceLevel;
}

export function setMcpTraceLevel(level) {
    mcpTraceLevel = level;
}

// ========== MCP Tools ==========

async function loadMcpTools() {
    if (mcpToolsLoaded) {
        renderMcpTools();
        return;
    }
    try {
        const response = await fetch('/api/admin/mcp/tools');
        mcpTools = await response.json();
        mcpToolsLoaded = true;
        renderMcpTools();
    } catch (e) {
        console.error('Failed to load MCP tools:', e);
        const list = document.getElementById('mcp-tools-list');
        if (list) {
            list.innerHTML = '<div class="text-secondary p-lg">Failed to load tools</div>';
        }
    }
}

function filterMcpTools(query) {
    mcpToolsFilter = query.toLowerCase();
    renderMcpTools();
}

function renderMcpTools() {
    const list = document.getElementById('mcp-tools-list');
    const countEl = document.getElementById('mcp-tools-count');
    if (!list) return;

    const filtered = mcpTools.filter(tool => {
        if (!mcpToolsFilter) return true;
        return tool.name.toLowerCase().includes(mcpToolsFilter)
            || (tool.description && tool.description.toLowerCase().includes(mcpToolsFilter))
            || (tool.group && tool.group.toLowerCase().includes(mcpToolsFilter))
            || (tool.subGroup && tool.subGroup.toLowerCase().includes(mcpToolsFilter));
    });

    if (countEl) {
        countEl.textContent = mcpToolsFilter
            ? `${filtered.length} / ${mcpTools.length} tools`
            : `${mcpTools.length} tools`;
    }

    if (filtered.length === 0) {
        list.innerHTML = mcpToolsFilter
            ? '<div class="text-secondary p-lg">No tools matching filter</div>'
            : '<div class="text-secondary p-lg">No MCP tools registered</div>';
        return;
    }

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
    const expanded = !!mcpToolsFilter;
    const toggleIcon = expanded ? '&#9660;' : '&#9654;';
    const collapsedClass = expanded ? '' : ' collapsed';
    const bodyDisplay = expanded ? '' : ' style="display: none;"';

    list.innerHTML = Object.entries(hierarchy).map(([group, subGroups]) => {
        const groupToolCount = Object.values(subGroups).reduce((sum, arr) => sum + arr.length, 0);
        const subGroupEntries = Object.entries(subGroups);
        const hasSubGroups = !(subGroupEntries.length === 1 && subGroupEntries[0][0] === '_ungrouped');

        let bodyHtml;
        if (hasSubGroups) {
            bodyHtml = subGroupEntries.map(([subKey, tools]) => {
                const subName = subKey === '_ungrouped' ? 'Other' : subKey;
                const toolsHtml = tools.map(tool => renderMcpToolItem(tool)).join('');
                return `
                    <div class="mcp-tool-subgroup${collapsedClass}">
                        <div class="mcp-tool-subgroup-header" data-action="toggleMcpToolGroup">
                            <span class="mcp-tool-group-toggle">${toggleIcon}</span>
                            <span class="mcp-tool-subgroup-name">${esc(subName)}</span>
                            <span class="mcp-tool-subgroup-count">${tools.length}</span>
                        </div>
                        <div class="mcp-tool-group-body"${bodyDisplay}>
                            ${toolsHtml}
                        </div>
                    </div>
                `;
            }).join('');
        } else {
            bodyHtml = subGroupEntries[0][1].map(tool => renderMcpToolItem(tool)).join('');
        }

        return `
            <div class="mcp-tool-group${collapsedClass}">
                <div class="mcp-tool-group-header" data-action="toggleMcpToolGroup">
                    <span class="mcp-tool-group-toggle">${toggleIcon}</span>
                    <span class="mcp-tool-group-name">${esc(group)}</span>
                    <span class="mcp-tool-group-count">${groupToolCount}</span>
                </div>
                <div class="mcp-tool-group-body"${bodyDisplay}>
                    ${bodyHtml}
                </div>
            </div>
        `;
    }).join('');
}

function renderMcpToolItem(tool) {
    const esc = escapeHtml;
    const argCount = tool.args ? tool.args.length : 0;
    const argsHtml = argCount > 0
        ? tool.args.map(arg =>
            `<span class="mcp-tool-arg ${arg.required ? 'mcp-tool-arg-required' : 'mcp-tool-arg-optional'}" title="${esc(arg.description || '')}&#10;Type: ${esc(arg.type)}${arg.required ? '' : ' (optional)'}">${esc(arg.name)}</span>`
        ).join('')
        : '<span class="text-dimmed font-sm">No arguments</span>';

    return `
        <div class="mcp-tool-item" data-action="toggleMcpToolDetail">
            <div class="mcp-tool-header">
                <div class="mcp-tool-name">${esc(tool.name)}</div>
                <div class="mcp-tool-arg-count">${argCount === 0 ? 'No args' : argCount === 1 ? '1 arg' : argCount + ' args'}</div>
            </div>
            <div class="mcp-tool-description">${esc(tool.description || '')}</div>
            <div class="mcp-tool-args">${argsHtml}</div>
            <div class="mcp-tool-detail" style="display: none;">
                ${renderMcpToolDetail(tool)}
            </div>
        </div>
    `;
}

function renderMcpToolDetail(tool) {
    const esc = escapeHtml;
    if (!tool.args || tool.args.length === 0) {
        return '<div class="text-dimmed py-sm">No arguments</div>';
    }
    return `
        <table class="mcp-tool-args-table">
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
                        <td><span class="mcp-tool-type-badge">${esc(arg.type)}</span></td>
                        <td>${arg.required ? '<span class="text-success">Yes</span>' : '<span class="text-dimmed">No</span>'}</td>
                        <td class="text-secondary">${esc(arg.description || '')}</td>
                    </tr>
                `).join('')}
            </tbody>
        </table>
    `;
}

function toggleMcpToolDetail(el) {
    const item = el.closest('.mcp-tool-item');
    if (!item) return;
    const detail = item.querySelector('.mcp-tool-detail');
    if (!detail) return;
    const isVisible = detail.style.display !== 'none';
    detail.style.display = isVisible ? 'none' : 'block';
    item.classList.toggle('expanded', !isVisible);
}

function toggleMcpToolGroup(headerEl) {
    const group = headerEl.closest('.mcp-tool-group, .mcp-tool-subgroup');
    if (!group) return;
    const body = group.querySelector('.mcp-tool-group-body');
    const toggle = headerEl.querySelector('.mcp-tool-group-toggle');
    if (!body) return;
    const isCollapsed = body.style.display === 'none';
    body.style.display = isCollapsed ? '' : 'none';
    toggle.innerHTML = isCollapsed ? '&#9660;' : '&#9654;';
    group.classList.toggle('collapsed', !isCollapsed);
}

registerActions('click', {
    selectMcpClient: (el) => selectMcpClient(el.dataset.clientId),
    switchMcpConsoleTab: (el) => switchMcpConsoleTab(el.dataset.tab, el),
    toggleAllMcpTraces: () => {
        const expand = mcpAllFolded;
        toggleAllTraces('mcp-console-output', expand);
        mcpAllFolded = !mcpAllFolded;
        const foldButton = document.getElementById('mcp-trace-fold-button');
        if (foldButton) {
            foldButton.textContent = mcpAllFolded ? 'Unfold All' : 'Fold All';
        }
    },
    clearMcpConsole: () => clearMcpConsole(),
    toggleMcpToolDetail: (el) => toggleMcpToolDetail(el),
    toggleMcpToolGroup: (el) => toggleMcpToolGroup(el),
});

registerActions('change', {
    changeMcpTraceLevel: (el) => changeMcpTraceLevel(el.value),
});

registerActions('input', {
    filterMcpTools: (el) => filterMcpTools(el.value),
});
