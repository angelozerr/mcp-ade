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
                    <button class="tab-button active" onclick="switchMcpConsoleTab('traces')">Traces</button>
                    <button class="tab-button" onclick="switchMcpConsoleTab('tools')">Tools</button>
                </div>
                <div class="console-controls" id="mcp-traces-controls">
                    ${TraceRenderer.renderTraceControls('mcp-trace', mcpTraceLevel, 'changeMcpTraceLevel(this.value)')}
                </div>
            </div>
            <div class="tab-content">
                <div id="mcp-traces-tab" class="tab-panel active">
                    <div class="placeholder">
                        ← Select an AI client to view MCP traces
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
                    <button class="tab-button active" onclick="switchMcpConsoleTab('traces')">Traces</button>
                    <button class="tab-button" onclick="switchMcpConsoleTab('tools')">Tools</button>
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
                    <div class="details-panel">
                        <p>MCP Tools coming soon...</p>
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

function switchMcpConsoleTab(tab) {
    // Update tab buttons
    document.querySelectorAll('#console-area .tab-button').forEach(btn => {
        btn.classList.remove('active');
    });
    event.target.classList.add('active');

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
