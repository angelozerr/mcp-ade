/**
 * Admin UI - MCP (Model Context Protocol) Traces Management
 *
 * Handles MCP client listing and trace visualization
 */

let mcpTraces = [];
let mcpTraceLevel = 'off';
let mcpClients = [];
let mcpAllFolded = true;
let selectedMcpClient = null;
let mcpTracesByClient = {}; // Store traces per client: {connectionId: [...traces]}
let mcpTracesLoaded = false; // Track if MCP traces have been loaded
let mcpTools = []; // Registered MCP tools
let mcpToolsLoaded = false;
let mcpToolsFilter = '';

/**
 * Load MCP clients.
 */
async function loadMcpClients() {
    try {
        const response = await fetch('/api/admin/mcp/clients');
        const newClients = await response.json();

        // Check if data actually changed to avoid unnecessary re-renders
        if (JSON.stringify(newClients) !== JSON.stringify(mcpClients)) {
            mcpClients = newClients;
            renderMcpClients();

            // Auto-select first client if none selected
            if (mcpClients.length > 0 && !selectedMcpClient) {
                selectMcpClient(mcpClients[0].id);
            }

            // Check if previously selected client still exists
            if (selectedMcpClient) {
                const stillExists = mcpClients.find(c => c.id === selectedMcpClient);
                if (!stillExists) {
                    // Previously selected client disconnected
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
            '<div class="text-secondary" style="padding: 1rem;">Failed to load clients</div>';
    }
}

function renderMcpClients() {
    const list = document.getElementById('mcp-clients-list');
    if (!list) return;

    if (mcpClients.length === 0) {
        list.innerHTML = '<div class="text-secondary" style="padding: 1rem;">No clients connected</div>';
        return;
    }

    list.innerHTML = mcpClients.map(client => {
        // Shorten connection ID for display (first 8 chars)
        const shortId = client.id.substring(0, 8) + '...';

        return `
            <div class="workspace-item ${client.id === selectedMcpClient ? 'active' : ''}"
                 onclick="selectMcpClient('${client.id}')"
                 style="cursor: pointer;"
                 title="${window.escapeHtml ? window.escapeHtml(client.id) : client.id}">
                <div style="font-weight: 600; margin-bottom: 0.25rem;">
                    📱 ${window.escapeHtml ? window.escapeHtml(client.name) : client.name}
                </div>
                <div class="text-dimmed" style="font-size: 0.75rem; padding-left: 1.5rem;">
                    Session: ${window.escapeHtml ? window.escapeHtml(shortId) : shortId}
                </div>
            </div>
        `;
    }).join('');

    // Auto-select first client if none selected and clients exist
    // BUT only if we're on the MCP tab
    if (window.currentTab === 'mcp-traces') {
        if (!selectedMcpClient && mcpClients.length > 0) {
            selectMcpClient(mcpClients[0].id);
        } else if (selectedMcpClient) {
            // Verify selected client still exists
            const stillExists = mcpClients.find(c => c.id === selectedMcpClient);
            if (!stillExists) {
                // Selected client disconnected, select first available or show placeholder
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

function selectMcpClient(clientId) {
    selectedMcpClient = clientId;
    renderMcpClients();

    // Initialize traces if not already done
    loadInitialMcpTraces();

    loadMcpConsole(clientId);
}

/**
 * Initialize MCP traces (called once when accessing MCP tab).
 * Trace history is received via WebSocket on connect.
 */
function loadInitialMcpTraces() {
    if (mcpTracesLoaded) return;
    mcpTracesLoaded = true;
}

function loadMcpTracesConsole() {
    // Initialize trace level from WebSocket-provided data
    const savedMcpLevel = window.traceLevels && window.traceLevels['mcp'];
    mcpTraceLevel = savedMcpLevel || 'off';
    const consoleArea = document.getElementById('console-area');

    consoleArea.innerHTML = `
        <div class="console-wrapper">
            <div class="console-header">
                <div class="console-tabs">
                    <button class="tab-button active" onclick="switchMcpConsoleTab('traces', this)">Traces</button>
                    <button class="tab-button" onclick="switchMcpConsoleTab('tools', this)">Tools</button>
                </div>
                <div class="console-controls" id="mcp-traces-controls">
                    ${TraceRenderer.renderTraceControls('mcp-trace', mcpTraceLevel, 'changeMcpTraceLevel(this.value)')}
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
                                   oninput="filterMcpTools(this.value)" />
                            <span class="mcp-tools-count" id="mcp-tools-count"></span>
                        </div>
                        <div class="mcp-tools-list" id="mcp-tools-list"></div>
                    </div>
                </div>
            </div>
        </div>
    `;
}

function loadMcpConsole(clientId) {
    // Initialize trace level from WebSocket-provided data
    const savedMcpLevel2 = window.traceLevels && window.traceLevels['mcp'];
    mcpTraceLevel = savedMcpLevel2 || 'off';

    const consoleArea = document.getElementById('console-area');

    // Find client info
    const client = mcpClients.find(c => c.id === clientId);
    const clientName = client ? client.name : 'MCP Client';

    // Render console with tabs (exact same structure as LSP)
    consoleArea.innerHTML = `
        <div class="console-wrapper">
            <div class="console-header">
                <div class="console-tabs">
                    <button class="tab-button active" onclick="switchMcpConsoleTab('traces', this)">Traces</button>
                    <button class="tab-button" onclick="switchMcpConsoleTab('tools', this)">Tools</button>
                </div>
                <div class="console-controls" id="mcp-traces-controls">
                    ${TraceRenderer.renderTraceControls('mcp-trace', mcpTraceLevel, 'changeMcpTraceLevel(this.value)', {
                        onFold: 'toggleAllMcpTraces()',
                        onClear: 'clearMcpConsole()'
                    })}
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
                                   oninput="filterMcpTools(this.value)" />
                            <span class="mcp-tools-count" id="mcp-tools-count"></span>
                        </div>
                        <div class="mcp-tools-list" id="mcp-tools-list"></div>
                    </div>
                </div>
            </div>
        </div>
    `;

    renderMcpConsole();
}

async function changeMcpTraceLevel(newLevel) {
    mcpTraceLevel = newLevel;
    if (window.traceLevels) {
        window.traceLevels['mcp'] = newLevel;
    }
    TraceRenderer.updateTraceControls('mcp-trace', newLevel);
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
    // Update tab buttons
    document.querySelectorAll('#console-area .tab-button').forEach(btn => {
        btn.classList.remove('active');
    });
    if (clickedBtn) {
        clickedBtn.classList.add('active');
    }

    // Update tab panels
    document.querySelectorAll('#console-area .tab-panel').forEach(panel => {
        panel.classList.remove('active');
    });

    // Show selected tab
    if (tab === 'traces') {
        document.getElementById('mcp-traces-tab').classList.add('active');
        document.getElementById('mcp-traces-controls').style.display = 'flex';
        // Show search box for traces
        if (window.updateSearchBoxVisibility) {
            window.updateSearchBoxVisibility(true);
        }
    } else if (tab === 'tools') {
        document.getElementById('mcp-tools-tab').classList.add('active');
        document.getElementById('mcp-traces-controls').style.display = 'none';
        // Hide search box for tools
        if (window.updateSearchBoxVisibility) {
            window.updateSearchBoxVisibility(false);
        }
        loadMcpTools();
    }
}

function renderMcpConsole() {
    const output = document.getElementById('mcp-console-output');
    if (!output) return;

    if (mcpTraceLevel === 'off') {
        output.innerHTML = '<div class="text-secondary" style="padding: 1rem;">Traces disabled (level: off)</div>';
        return;
    }

    // Get traces for the selected client
    const clientTraces = mcpTracesByClient[selectedMcpClient] || [];

    if (clientTraces.length === 0) {
        output.innerHTML = '<div class="text-secondary" style="padding: 1rem;">No MCP traces yet...</div>';
        return;
    }

    const wasAtBottom = TraceRenderer.isScrolledToBottom(output);
    const expandedIds = TraceRenderer.saveExpandedState(output);

    const html = clientTraces.map((trace, index) => formatMcpTrace(trace, index, '')).join('');
    output.innerHTML = html;

    TraceRenderer.restoreExpandedState(output, expandedIds);

    if (wasAtBottom) {
        output.scrollTop = output.scrollHeight;
    }
}

function renderMcpConsoleWithHighlights() {
    const output = document.getElementById('mcp-console-output');
    if (!output) return;

    if (mcpTraceLevel === 'off') {
        output.innerHTML = '<div class="text-secondary" style="padding: 1rem;">Traces disabled (level: off)</div>';
        return;
    }

    // Get traces for the selected client
    const clientTraces = mcpTracesByClient[selectedMcpClient] || [];

    if (clientTraces.length === 0) {
        output.innerHTML = '<div class="text-secondary" style="padding: 1rem;">No MCP traces yet...</div>';
        return;
    }

    const expandedIds = TraceRenderer.saveExpandedState(output);

    const html = clientTraces.map((trace, index) => formatMcpTrace(trace, index, TraceRenderer.getCurrentSearchQuery())).join('');
    output.innerHTML = html;

    TraceRenderer.restoreExpandedState(output, expandedIds);
}

function formatMcpTrace(trace, index, searchQuery = '') {
    // Delegate to TraceRenderer for consistent rendering
    return TraceRenderer.renderTrace(trace, index, mcpTraceLevel, searchQuery);
}

// Tooltip, toggle, and toggleAll functions now provided by TraceRenderer (via window.*)

function toggleAllMcpTraces() {
    // Use TraceRenderer's toggleAllTraces with MCP console container
    const expand = mcpAllFolded;
    TraceRenderer.toggleAllTraces('mcp-console-output', expand);
    mcpAllFolded = !mcpAllFolded;

    // Update button text
    const foldButton = document.getElementById('mcp-trace-fold-button');
    if (foldButton) {
        foldButton.textContent = mcpAllFolded ? 'Unfold All' : 'Fold All';
    }
}

async function clearMcpConsole() {
    try {
        await fetch('/api/admin/traces/mcp', { method: 'DELETE' });

        // Clear traces for current client only
        if (selectedMcpClient) {
            mcpTracesByClient[selectedMcpClient] = [];
        }

        renderMcpConsole();
    } catch (error) {
        console.error('Failed to clear MCP traces:', error);
    }
}

/**
 * Handle incoming MCP trace from WebSocket.
 */
function handleMcpTrace(trace) {
    const connectionId = trace.connectionId;

    // Store trace
    if (!mcpTracesByClient[connectionId]) {
        mcpTracesByClient[connectionId] = [];
    }

    // Check if this is an UPDATE message (replaces previous line)
    if (trace.messageType === 'UPDATE') {
        const traces = mcpTracesByClient[connectionId];
        const lastTrace = traces[traces.length - 1];
        // Replace last trace if it was also an UPDATE
        if (lastTrace && lastTrace.messageType === 'UPDATE') {
            traces[traces.length - 1] = trace;
        } else {
            traces.push(trace);
        }
    } else {
        mcpTracesByClient[connectionId].push(trace);
    }

    // Re-render console if this trace is for the currently selected client
    if (selectedMcpClient === connectionId) {
        renderMcpConsole();
    }
}

/**
 * Handle MCP clients update from WebSocket.
 */
function handleMcpClientsUpdate(newClients) {
    // Check if data actually changed
    if (JSON.stringify(newClients) !== JSON.stringify(mcpClients)) {
        mcpClients = newClients;
        renderMcpClients();

        // Auto-select first client if none selected
        if (mcpClients.length > 0 && !selectedMcpClient) {
            selectMcpClient(mcpClients[0].id);
        }

        // Check if previously selected client still exists
        if (selectedMcpClient) {
            const stillExists = mcpClients.find(c => c.id === selectedMcpClient);
            if (!stillExists) {
                // Previously selected client disconnected
                selectedMcpClient = null;
                if (mcpClients.length > 0) {
                    selectMcpClient(mcpClients[0].id);
                } else {
                    loadMcpTracesConsole();
                }
            }
        }
    }
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
            list.innerHTML = '<div class="text-secondary" style="padding: 1rem;">Failed to load tools</div>';
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
            ? '<div class="text-secondary" style="padding: 1rem;">No tools matching filter</div>'
            : '<div class="text-secondary" style="padding: 1rem;">No MCP tools registered</div>';
        return;
    }

    // Build hierarchy: group -> subGroup -> tools
    const hierarchy = {};
    for (const tool of filtered) {
        const g = tool.group || 'Other';
        const sg = tool.subGroup || null;
        if (!hierarchy[g]) hierarchy[g] = {};
        const subKey = sg || '_ungrouped';
        if (!hierarchy[g][subKey]) hierarchy[g][subKey] = [];
        hierarchy[g][subKey].push(tool);
    }

    const esc = window.escapeHtml || (s => s);
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
                        <div class="mcp-tool-subgroup-header" onclick="toggleMcpToolGroup(this)">
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
                <div class="mcp-tool-group-header" onclick="toggleMcpToolGroup(this)">
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
    const esc = window.escapeHtml || (s => s);
    const argCount = tool.args ? tool.args.length : 0;
    const argsHtml = argCount > 0
        ? tool.args.map(arg =>
            `<span class="mcp-tool-arg ${arg.required ? 'mcp-tool-arg-required' : 'mcp-tool-arg-optional'}" title="${esc(arg.description || '')}&#10;Type: ${esc(arg.type)}${arg.required ? '' : ' (optional)'}">${esc(arg.name)}</span>`
        ).join('')
        : '<span class="text-dimmed" style="font-size: 0.75rem;">No arguments</span>';

    return `
        <div class="mcp-tool-item" onclick="toggleMcpToolDetail(this)">
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
    const esc = window.escapeHtml || (s => s);
    if (!tool.args || tool.args.length === 0) {
        return '<div class="text-dimmed" style="padding: 0.5rem 0;">No arguments</div>';
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
    const detail = el.querySelector('.mcp-tool-detail');
    if (!detail) return;
    const isVisible = detail.style.display !== 'none';
    detail.style.display = isVisible ? 'none' : 'block';
    el.classList.toggle('expanded', !isVisible);
}

function toggleMcpToolGroup(headerEl) {
    const group = headerEl.parentElement;
    const body = group.querySelector('.mcp-tool-group-body');
    const toggle = headerEl.querySelector('.mcp-tool-group-toggle');
    if (!body) return;
    const isCollapsed = body.style.display === 'none';
    body.style.display = isCollapsed ? '' : 'none';
    toggle.innerHTML = isCollapsed ? '&#9660;' : '&#9654;';
    group.classList.toggle('collapsed', !isCollapsed);
}

// Expose functions globally
window.loadMcpClients = loadMcpClients;
window.selectMcpClient = selectMcpClient;
window.loadMcpTracesConsole = loadMcpTracesConsole;
window.changeMcpTraceLevel = changeMcpTraceLevel;
window.switchMcpConsoleTab = switchMcpConsoleTab;
// toggleMcpTrace, showMcpTooltip, hideMcpTooltip now provided by TraceRenderer
window.toggleAllMcpTraces = toggleAllMcpTraces;
window.clearMcpConsole = clearMcpConsole;
window.handleMcpTrace = handleMcpTrace;
window.handleMcpClientsUpdate = handleMcpClientsUpdate;
window.renderMcpConsoleWithHighlights = renderMcpConsoleWithHighlights;
window.loadMcpTools = loadMcpTools;
window.filterMcpTools = filterMcpTools;
window.toggleMcpToolDetail = toggleMcpToolDetail;
window.toggleMcpToolGroup = toggleMcpToolGroup;
