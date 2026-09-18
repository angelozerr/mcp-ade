import { state, isOnMcpTab, updateSearchBoxVisibility } from './shared-state.js';
import {
    renderTraceControls, updateTraceControls, renderTracesInContainer,
    getCurrentSearchQuery, escapeHtml, initTraceContainer, toggleAllTraces
} from './trace-renderer.js';
import { registerActions } from './event-delegation.js';
import { renderActivity, updateActivityToggleUI } from './admin-activity.js';
import { showToast } from './toast.js';
import { ensureToolsLoaded, renderToolsPanel } from './shared-tools.js';

let mcpTraces = [];
let mcpTraceLevel = 'off';
let mcpClients = [];
let mcpAllFolded = true;
let selectedMcpClient = null;
let mcpTracesByClient = {};
let mcpTracesLoaded = false;
let currentMcpConsoleTab = 'traces';

export async function loadMcpClients() {
    try {
        const response = await fetch('/api/admin/mcp/clients');
        const newClients = await response.json();
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

    if (isOnMcpTab()) {
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
    if (isOnMcpTab()) {
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
    const tab = currentMcpConsoleTab;

    consoleArea.innerHTML = `
        <div class="console-wrapper">
            <div class="console-header">
                <div class="console-tabs">
                    <button class="tab-button${tab === 'traces' ? ' active' : ''}" data-action="switchMcpConsoleTab" data-tab="traces">Traces</button>
                    <button class="tab-button${tab === 'tools' ? ' active' : ''}" data-action="switchMcpConsoleTab" data-tab="tools">Tools</button>
                    <button class="tab-button${tab === 'activity' ? ' active' : ''}" data-action="switchMcpConsoleTab" data-tab="activity">Activity</button>
                </div>
                <div class="console-controls" id="mcp-traces-controls"${tab !== 'traces' ? ' style="display: none;"' : ''}>
                    ${renderTraceControls('mcp-trace', mcpTraceLevel, 'changeMcpTraceLevel')}
                </div>
                <div class="console-controls" id="mcp-activity-controls"${tab !== 'activity' ? ' style="display: none;"' : ''}>
                    <label class="toggle-switch">
                        <input type="checkbox" id="activity-toggle-checkbox" data-action="toggleActivity">
                        <span class="toggle-slider"></span>
                    </label>
                    <button data-action="foldAllActivity">Fold All</button>
                    <button data-action="clearActivity">Clear</button>
                </div>
            </div>
            <div class="tab-content">
                <div id="mcp-traces-tab" class="tab-panel${tab === 'traces' ? ' active' : ''}">
                    <div class="placeholder">
                        &#8592; Select an AI client to view MCP traces
                    </div>
                </div>
                <div id="tools-tab" class="tab-panel${tab === 'tools' ? ' active' : ''}">
                    <div id="mcp-tools-container"></div>
                </div>
                <div id="mcp-activity-tab" class="tab-panel${tab === 'activity' ? ' active' : ''}">
                    <div class="activity-list" id="mcp-activity-content">
                        <div class="text-secondary p-lg">No operations recorded yet</div>
                    </div>
                </div>
            </div>
        </div>
    `;

    if (tab === 'activity') {
        updateActivityToggleUI();
        renderActivity();
    } else if (tab === 'tools') {
        loadMcpTools();
    }
}

export function loadMcpConsole(clientId) {
    const savedMcpLevel2 = state.traceLevels['mcp'];
    mcpTraceLevel = savedMcpLevel2 || 'off';

    const consoleArea = document.getElementById('console-area');

    const client = mcpClients.find(c => c.id === clientId);
    const clientName = client ? client.name : 'MCP Client';
    const tab = currentMcpConsoleTab;

    consoleArea.innerHTML = `
        <div class="console-wrapper">
            <div class="console-header">
                <div class="console-tabs">
                    <button class="tab-button${tab === 'traces' ? ' active' : ''}" data-action="switchMcpConsoleTab" data-tab="traces">Traces</button>
                    <button class="tab-button${tab === 'tools' ? ' active' : ''}" data-action="switchMcpConsoleTab" data-tab="tools">Tools</button>
                    <button class="tab-button${tab === 'activity' ? ' active' : ''}" data-action="switchMcpConsoleTab" data-tab="activity">Activity</button>
                </div>
                <div class="console-controls" id="mcp-traces-controls"${tab !== 'traces' ? ' style="display: none;"' : ''}>
                    ${renderTraceControls('mcp-trace', mcpTraceLevel, 'changeMcpTraceLevel', {
                        foldAction: 'toggleAllMcpTraces',
                        clearAction: 'clearMcpConsole'
                    })}
                </div>
                <div class="console-controls" id="mcp-activity-controls"${tab !== 'activity' ? ' style="display: none;"' : ''}>
                    <label class="toggle-switch">
                        <input type="checkbox" id="activity-toggle-checkbox" data-action="toggleActivity">
                        <span class="toggle-slider"></span>
                    </label>
                    <button data-action="foldAllActivity">Fold All</button>
                    <button data-action="clearActivity">Clear</button>
                </div>
            </div>
            <div class="tab-content">
                <div id="mcp-traces-tab" class="tab-panel${tab === 'traces' ? ' active' : ''}">
                    <div class="console" id="mcp-console-output" tabindex="0"></div>
                </div>
                <div id="tools-tab" class="tab-panel${tab === 'tools' ? ' active' : ''}">
                    <div id="mcp-tools-container"></div>
                </div>
                <div id="mcp-activity-tab" class="tab-panel${tab === 'activity' ? ' active' : ''}">
                    <div class="activity-list" id="mcp-activity-content">
                        <div class="text-secondary p-lg">No operations recorded yet</div>
                    </div>
                </div>
            </div>
        </div>
    `;

    renderMcpConsole();
    initTraceContainer('mcp-console-output');

    if (tab === 'activity') {
        updateActivityToggleUI();
        renderActivity();
    } else if (tab === 'tools') {
        loadMcpTools();
    }
}

async function changeMcpTraceLevel(newLevel) {
    mcpTraceLevel = newLevel;
    state.traceLevels['mcp'] = newLevel;
    updateTraceControls('mcp-trace', newLevel);
    renderMcpConsole();

    try {
        const response = await fetch('/api/admin/traces/mcp', {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ traceLevel: newLevel })
        });
        if (response.ok) showToast('Settings saved');
    } catch (err) {
        console.error('Failed to set MCP trace level:', err);
    }
}

function switchMcpConsoleTab(tab, clickedBtn) {
    currentMcpConsoleTab = tab;
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
        document.getElementById('tools-tab').classList.add('active');
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
    renderTracesInContainer('mcp-console-output', clientTraces, mcpTraceLevel, '', undefined, 'mcp-trace');
}

export function renderMcpConsoleWithHighlights() {
    const clientTraces = mcpTracesByClient[selectedMcpClient] || [];
    renderTracesInContainer('mcp-console-output', clientTraces, mcpTraceLevel, getCurrentSearchQuery(), undefined, 'mcp-trace');
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
            } else if (isOnMcpTab()) {
                loadMcpTracesConsole();
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
    try {
        const tools = await ensureToolsLoaded();
        renderToolsPanel('mcp-tools-container', tools, { showGroupExtensionBadge: true });
    } catch (e) {
        console.error('Failed to load MCP tools:', e);
        const container = document.getElementById('mcp-tools-container');
        if (container) {
            container.innerHTML = '<div class="text-secondary p-lg">Failed to load tools</div>';
        }
    }
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
});

registerActions('change', {
    changeMcpTraceLevel: (el) => changeMcpTraceLevel(el.value),
});

