import { state, getCurrentTheme, setTheme, updateThemeIcon, traceKey,
    formatStatusClass, formatStatusLabel, updateSearchBoxVisibility,
    loadLspConfigs, loadDapConfigs } from './shared-state.js';
import { initModalOverlay, hideConfirmModal } from './shared-ui.js';
import { escapeHtml, updateTraceControls, clearHighlights, closeSearch } from './trace-renderer.js';
import { initEventDelegation, registerActions } from './event-delegation.js';
import { KeyboardShortcuts } from './keyboard-shortcuts.js';
import { setDiagramCallbacks } from './diagram.js';
import { renderProgressBadge } from './progress-renderer.js';
import { handleProgressInit, handleProgressUpdate, setInstallProgressCallback, setTaskCompletedCallback, setTaskStartedCallback } from './progress-manager.js';

import { renderWorkspaces, selectWorkspace, selectServer, selectDapSessionByServerId,
    switchWorkspaceTab, loadConsole, renderConsole, loadServers,
    onWorkspaceTaskStarted, onWorkspaceTaskCompleted,
    setCreateSessionHTMLFn, setInstallerCallbacks, setChangeDapServerTraceLevelFn,
    setRenderDapTracesForSessionFn, setRenderMcpConsoleWithHighlightsFn } from './admin-workspace.js';
import { loadAllLspServers, saveInstallerJson, resetInstallerJson, runInstaller,
    loadInstallerJson, appendInstallTrace, updateInstallProgress } from './admin-lsp.js';
import { loadAllDapServers, onDapSessionUpdate, renderDapTracesForSession,
    createSessionHTML, changeDapServerTraceLevel,
    setSelectDapSessionByServerIdCallback } from './admin-dap.js';
import { loadAllExtensions, showAddExtensionForm, setSwitchTabCallback } from './admin-extensions.js';
import { getMcpClients, getSelectedMcpClient, getMcpTracesByClient,
    setMcpTraceLevel, handleMcpTrace, handleMcpClientsUpdate,
    selectMcpClient, loadMcpConsole, loadMcpTracesConsole,
    renderMcpConsole, renderMcpConsoleWithHighlights } from './admin-mcp.js';
import { handleOperationUpdate, handleActivityState } from './admin-activity.js';

// ========== Init event delegation + modals ==========
initEventDelegation();
initModalOverlay();

// Init theme icon on load
updateThemeIcon(getCurrentTheme());

let workspacesRendered = false;
let adminWebSocket = null;

// ========== Theme ==========

function toggleTheme() {
    const next = getCurrentTheme() === 'dark' ? 'light' : 'dark';
    setTheme(next);
    fetch('/api/admin/settings/admin.theme', {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ value: next })
    }).catch(() => {});
}

// ========== WebSocket ==========

function connectAdminWebSocket() {
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    const wsUrl = `${protocol}//${window.location.host}/api/admin/ws`;

    console.log('Connecting to WebSocket:', wsUrl);
    adminWebSocket = new WebSocket(wsUrl);

    adminWebSocket.onopen = () => {
        console.log('WebSocket connected');
    };

    adminWebSocket.onmessage = (event) => {
        const message = JSON.parse(event.data);
        handleWebSocketMessage(message);
    };

    adminWebSocket.onerror = (error) => {
        console.error('WebSocket error:', error);
    };

    adminWebSocket.onclose = () => {
        console.log('WebSocket closed, reconnecting in 3s...');
        setTimeout(connectAdminWebSocket, 3000);
    };
}

function handleWebSocketMessage(message) {
    console.log('WebSocket message received:', message.type, message);
    switch (message.type) {
        case 'lsp-trace':
            handleLspTrace(message);
            break;
        case 'dap-trace':
            handleDapTrace(message);
            break;
        case 'dap-session-update':
            onDapSessionUpdate(message);
            break;
        case 'progress-init':
            handleProgressInit(message);
            break;
        case 'progress-update':
            handleProgressUpdate(message);
            break;
        case 'mcp-trace':
            handleMcpTrace(message);
            break;
        case 'workspaces-update':
            handleWorkspacesUpdate(message.workspaces);
            break;
        case 'mcp-clients-update':
            handleMcpClientsUpdate(message.clients);
            break;
        case 'server-status-changed':
            handleServerStatusChanged(message);
            break;
        case 'trace-level-update':
            handleTraceLevelUpdate(message);
            break;
        case 'server-enabled-changed':
            handleServerEnabledChanged(message);
            break;
        case 'operation-update':
            handleOperationUpdate(message);
            break;
        case 'activity-state':
            handleActivityState(message);
            break;
        default:
            console.warn('Unknown WebSocket message type:', message.type);
    }
}

// ========== WebSocket message handlers ==========

function handleLspTrace(trace) {
    console.log('handleLspTrace called for server:', trace.serverId, 'current:', state.currentServerId);

    if (state.installOutputServerId === trace.serverId) {
        appendInstallTrace(trace);
    }

    const tk = traceKey(trace.workspaceUri, trace.serverId);
    if (!state.tracesByServer[tk]) {
        state.tracesByServer[tk] = [];
    }

    if (trace.messageType === 'UPDATE') {
        const traces = state.tracesByServer[tk];
        const lastTrace = traces[traces.length - 1];
        if (lastTrace && lastTrace.messageType === 'UPDATE') {
            traces[traces.length - 1] = trace;
        } else {
            traces.push(trace);
        }
    } else {
        state.tracesByServer[tk].push(trace);
    }

    console.log('Stored trace, total for', tk, ':', state.tracesByServer[tk].length);

    if (state.tracesByServer[tk].length > 200) {
        state.tracesByServer[tk] = state.tracesByServer[tk].slice(-200);
    }

    if ((trace.messageType === 'INFO' || trace.messageType === 'UPDATE' || trace.messageType === 'ERROR') &&
        !state.currentServerId) {
        console.log('Auto-selecting server for installation:', trace.serverId);

        const workspace = trace.workspaceUri
            ? state.workspaces.find(w => w.rootUri === trace.workspaceUri)
            : state.workspaces.find(w => w.lspServers && w.lspServers.some(s => s.id === trace.serverId));
        if (workspace && workspace.lspServers) {
            const server = workspace.lspServers.find(s => s.id === trace.serverId);
            if (server) {
                state.selectedWorkspace = workspace.rootUri;
                selectServer(server);
            }
        }
    }

    if (tk === traceKey(state.selectedWorkspace, state.currentServerId) ||
        (trace.workspaceUri == null && trace.serverId === state.currentServerId)) {
        console.log('Refreshing console for current server');
        renderConsole();
    }
}

function handleDapTrace(trace) {
    if (trace.sessionId) {
        if (!state.dapTracesBySession[trace.sessionId]) {
            state.dapTracesBySession[trace.sessionId] = [];
        }

        if (trace.messageType === 'UPDATE') {
            const traces = state.dapTracesBySession[trace.sessionId];
            const lastTrace = traces[traces.length - 1];
            if (lastTrace && lastTrace.messageType === 'UPDATE') {
                traces[traces.length - 1] = trace;
            } else {
                traces.push(trace);
            }
        } else {
            state.dapTracesBySession[trace.sessionId].push(trace);
        }

        if (state.dapTracesBySession[trace.sessionId].length > 200) {
            state.dapTracesBySession[trace.sessionId] = state.dapTracesBySession[trace.sessionId].slice(-200);
        }

        if (state.currentDapSessionId === trace.sessionId) {
            renderDapTracesForSession(trace.sessionId);
        }
    } else if (trace.serverId) {
        if (!state.dapTracesByServer[trace.serverId]) {
            state.dapTracesByServer[trace.serverId] = [];
        }

        if (trace.messageType === 'UPDATE') {
            const traces = state.dapTracesByServer[trace.serverId];
            const lastTrace = traces[traces.length - 1];
            if (lastTrace && lastTrace.messageType === 'UPDATE') {
                traces[traces.length - 1] = trace;
            } else {
                traces.push(trace);
            }
        } else {
            state.dapTracesByServer[trace.serverId].push(trace);
        }

        if (state.dapTracesByServer[trace.serverId].length > 200) {
            state.dapTracesByServer[trace.serverId] = state.dapTracesByServer[trace.serverId].slice(-200);
        }

        if (state.currentDapSessionId && state.currentDapServerId === trace.serverId) {
            renderDapTracesForSession(state.currentDapSessionId);
        }
    }
}

function handleWorkspacesUpdate(newWorkspaces) {
    console.log('WebSocket workspaces update:', newWorkspaces);

    const mergedWorkspaces = newWorkspaces;

    if (!workspacesRendered || JSON.stringify(mergedWorkspaces) !== JSON.stringify(state.workspaces)) {
        // Preserve lazily-loaded lspServers from previous workspace objects
        for (const newWs of mergedWorkspaces) {
            const oldWs = state.workspaces.find(w => w.rootUri === newWs.rootUri);
            if (oldWs?.lspServers) {
                newWs.lspServers = oldWs.lspServers;
            }
        }
        state.workspaces = mergedWorkspaces;
        workspacesRendered = true;
        console.log('Workspaces updated, rendering...');
        renderWorkspaces();

        if (state.selectedWorkspace) {
            console.log('Selected workspace:', state.selectedWorkspace);
            const workspace = state.workspaces.find(w => w.rootUri === state.selectedWorkspace);
            console.log('Found workspace:', workspace);
            if (workspace) {
                switchWorkspaceTab(state.currentWorkspaceTab || 'servers');
            } else {
                state.selectedWorkspace = null;
                document.getElementById('servers-list').innerHTML = '<div class="servers-placeholder">No workspaces selected</div>';
            }
        } else if (state.workspaces.length > 0 && state.currentTab === 'workspaces') {
            console.log('Auto-selecting first workspace');
            selectWorkspace(state.workspaces[0].rootUri);
        }
    }
}

function handleTraceLevelUpdate(message) {
    const key = message.serverId
        ? message.serverType + '.' + message.serverId
        : message.serverType;
    state.traceLevels[key] = message.traceLevel;

    if (message.serverType === 'lsp') {
        if (state.currentServerId === message.serverId) {
            updateTraceControls('trace', message.traceLevel);
            renderConsole();
        }
        updateTraceControls('lsp-server-trace', message.traceLevel);
    } else if (message.serverType === 'mcp') {
        setMcpTraceLevel(message.traceLevel);
        updateTraceControls('mcp-trace', message.traceLevel);
        renderMcpConsole();
    } else if (message.serverType === 'dap' && state.currentDapServerId === message.serverId) {
        updateTraceControls('dap-trace', message.traceLevel);
        if (state.currentDapSessionId) {
            renderDapTracesForSession(state.currentDapSessionId);
        }
    }
}

function handleServerStatusChanged(event) {
    console.log('Server status changed:', event);

    const workspace = state.workspaces.find(w => w.rootUri === event.workspaceUri);
    if (!workspace || !workspace.lspServers) return;

    const changedServer = workspace.lspServers.find(s => s.id === event.serverId);
    if (!changedServer) return;

    changedServer.status = event.newStatus;
    changedServer.statusMessage = event.statusMessage;
    changedServer.installProgress = event.installProgress;
    changedServer.isReady = event.isReady;

    const extensions = workspace.lspServers.filter(s => s.parentServerId === event.serverId);
    for (const ext of extensions) {
        ext.status = event.newStatus;
        ext.isReady = event.isReady;
        ext.statusMessage = event.statusMessage;
        ext.installProgress = event.installProgress;
        ext.pid = changedServer.pid;
        ext.command = changedServer.command;
    }

    if (state.selectedWorkspace === event.workspaceUri) {
        updateServerStatusBadge(event.serverId, changedServer);

        for (const ext of extensions) {
            updateServerStatusBadge(ext.id, ext);
        }

        if (state.selectedServer && state.selectedServer.id === event.serverId) {
            updateDetailPanelStatusBadge(changedServer);
        }
    }
}

function handleServerEnabledChanged(event) {
    const enabled = event.enabled;
    const serverId = event.serverId;

    if (state.lspConfigs && state.lspConfigs[serverId]) {
        state.lspConfigs[serverId].enabled = enabled;
    }
    if (state.dapConfigs && state.dapConfigs[serverId]) {
        state.dapConfigs[serverId].enabled = enabled;
    }

    for (const ws of state.workspaces) {
        if (ws.lspServers) {
            const srv = ws.lspServers.find(s => s.id === serverId);
            if (srv) srv.enabled = enabled;
        }
    }

    const serverElement = document.querySelector(`.server-item[data-server-id="${serverId}"]`) ||
                          document.querySelector(`.server-item[data-dap-server="${serverId}"]`);
    if (serverElement) {
        if (enabled) {
            serverElement.classList.remove('server-disabled');
        } else {
            serverElement.classList.add('server-disabled');
        }
        const checkbox = serverElement.querySelector('.toggle-switch input[type="checkbox"]');
        if (checkbox) {
            checkbox.checked = enabled;
        }
    }
}

// ========== Status badge updates ==========

function updateServerStatusBadge(serverId, server) {
    const serverElement = document.querySelector(`.server-item[data-server-id="${serverId}"]`);
    if (!serverElement) return;

    const statusBadgeContainer = serverElement.querySelector('.server-status-badge-container');
    if (statusBadgeContainer) {
        const statusClass = formatStatusClass(server.status);
        const label = formatStatusLabel(server.status, server.externalInstance);
        const statusMessageHTML = server.statusMessage
            ? `<span class="server-status-message text-secondary font-md ml-sm">${escapeHtml(server.statusMessage)}</span>`
            : '';
        statusBadgeContainer.innerHTML = `<span class="status-badge ${statusClass}">${label}</span>${statusMessageHTML}`;
    }

    const actionsContainer = serverElement.querySelector('.server-actions');
    if (actionsContainer && !server.isExtension) {
        const isExternal = server.externalInstance != null &&
                           (server.status === 'CONNECTED_TO_IDE' || server.status === 'CONNECTING_TO_IDE');
        let actions = '';
        if (isExternal) {
            actions = `<button class="server-action-btn server-action-disconnect"
                              data-action="disconnectFromIdeAction" data-server-id="${serverId}" data-stop-propagation
                              title="Disconnect from IDE">⏏</button>`;
        } else if (server.status === 'RUNNING' || server.status === 'STARTING' || server.status === 'INDEXING') {
            actions = `<button class="server-action-btn" data-action="restartServerAction" data-server-id="${serverId}" data-stop-propagation title="Restart">↻</button>
                       <button class="server-action-btn" data-action="stopServerAction" data-server-id="${serverId}" data-stop-propagation title="Stop">■</button>`;
        } else if (server.status === 'STOPPED' || server.status === 'START_FAILED' || server.status === 'INSTALL_FAILED' || server.status === 'ERROR') {
            actions = `<button class="server-action-btn" data-action="startManagedServerAction" data-server-id="${serverId}" data-stop-propagation title="Start MCP-managed server">▶</button>
                       <button class="server-action-btn" data-action="connectToIdeAction" data-server-id="${serverId}" data-stop-propagation title="Try to connect to IDE instance">🔗</button>`;
        }
        actionsContainer.innerHTML = actions;
    }
}

function updateDetailPanelStatusBadge(server) {
    const progressElement = document.getElementById('server-detail-progress');
    if (!progressElement) return;

    const statusClass = formatStatusClass(server.status);
    const label = formatStatusLabel(server.status, server.externalInstance);

    if ((server.status === 'INSTALLING' || server.status === 'STARTING') && server.installProgress != null) {
        const progressPercent = server.installProgress * 100;
        const message = server.statusMessage || null;
        progressElement.innerHTML = renderProgressBadge(label, statusClass, progressPercent, message);
        progressElement.style.display = 'block';
        progressElement.style.padding = '0.5rem 1rem';
        progressElement.style.background = 'var(--bg-card)';
        progressElement.style.borderBottom = '1px solid var(--border-progress)';
    } else {
        progressElement.innerHTML = '';
        progressElement.style.display = 'none';
    }
}

// ========== Tab switching ==========

function switchTab(tab, element, options = {}) {
    state.currentTab = tab;

    if (tab !== 'dap-servers') {
        state.currentDapSessionId = null;
    }

    const searchBox = document.getElementById('search-box');
    if (searchBox) {
        searchBox.classList.remove('visible');
        clearHighlights();
    }

    document.querySelectorAll('.tab').forEach(t => t.classList.remove('active'));
    if (element) {
        element.classList.add('active');
    } else {
        document.querySelectorAll('.tab').forEach(t => {
            if (t.dataset.tab === tab) {
                t.classList.add('active');
            }
        });
    }

    const appContainer = document.querySelector('.app-container');
    const serversColumn = document.querySelector('.servers-sidebar');
    const consoleColumn = document.querySelector('.console-container');

    function showSidebarPanel(activeId) {
        const panels = ['workspaces-list', 'lsp-servers-list', 'dap-servers-list', 'extensions-container', 'mcp-traces-list'];
        panels.forEach(id => {
            document.getElementById(id).classList.toggle('d-none', id !== activeId);
        });
    }

    if (tab === 'workspaces') {
        showSidebarPanel('workspaces-list');
        serversColumn.style.display = 'flex';
        consoleColumn.style.display = 'flex';
        appContainer.style.gridTemplateColumns = '400px 300px 1fr';
        consoleColumn.style.gridColumn = '3';

        if (state.selectedWorkspace) {
            loadServers(state.selectedWorkspace);
            if (state.selectedServer) {
                loadConsole(state.selectedServer);
            } else {
                document.getElementById('console-area').innerHTML = `
                    <div class="placeholder">
                        &#8592; Select a workspace and LSP server to view console
                    </div>
                `;
                updateSearchBoxVisibility(false);
            }
        } else {
            document.getElementById('servers-list').innerHTML = '<div class="servers-placeholder">No workspaces selected</div>';
            document.getElementById('console-area').innerHTML = `
                <div class="placeholder">
                    &#8592; Select a workspace and LSP server to view console
                </div>
            `;
            updateSearchBoxVisibility(false);
        }
    } else if (tab === 'lsp-servers') {
        showSidebarPanel('lsp-servers-list');
        serversColumn.style.display = 'none';
        consoleColumn.style.display = 'flex';
        appContainer.style.gridTemplateColumns = '400px 1fr';
        consoleColumn.style.gridColumn = '2';

        loadAllLspServers(options.serverId);
    } else if (tab === 'dap-servers') {
        showSidebarPanel('dap-servers-list');
        serversColumn.style.display = 'none';
        consoleColumn.style.display = 'flex';
        appContainer.style.gridTemplateColumns = '400px 1fr';
        consoleColumn.style.gridColumn = '2';

        loadAllDapServers(options.serverId);
    } else if (tab === 'extensions') {
        showSidebarPanel('extensions-container');
        serversColumn.style.display = 'none';
        consoleColumn.style.display = 'flex';
        appContainer.style.gridTemplateColumns = '400px 1fr';
        consoleColumn.style.gridColumn = '2';

        loadAllExtensions();
        updateSearchBoxVisibility(false);
    } else if (tab === 'mcp-traces') {
        showSidebarPanel('mcp-traces-list');
        serversColumn.style.display = 'none';
        consoleColumn.style.display = 'flex';
        appContainer.style.gridTemplateColumns = '400px 1fr';
        consoleColumn.style.gridColumn = '2';

        const mcpClients = getMcpClients();
        const selectedClient = getSelectedMcpClient();
        if (mcpClients.length > 0) {
            if (selectedClient && mcpClients.find(c => c.id === selectedClient)) {
                loadMcpConsole(selectedClient);
                updateSearchBoxVisibility(true);
            } else {
                selectMcpClient(mcpClients[0].id);
                updateSearchBoxVisibility(true);
            }
        } else {
            loadMcpTracesConsole();
            updateSearchBoxVisibility(false);
        }
    }
}

// ========== Wire up cross-module callbacks ==========

setSwitchTabCallback(switchTab);
setDiagramCallbacks({
    switchTab,
    switchWorkspaceTab,
    selectServer,
    selectDapSessionByServerId,
});
setInstallProgressCallback(updateInstallProgress);
setTaskStartedCallback((taskId, workspaceUri) => onWorkspaceTaskStarted(taskId, workspaceUri));
setTaskCompletedCallback((taskId) => onWorkspaceTaskCompleted(taskId));
setSelectDapSessionByServerIdCallback(selectDapSessionByServerId);
setCreateSessionHTMLFn(createSessionHTML);
setInstallerCallbacks({
    saveInstallerJson,
    resetInstallerJson,
    runInstaller,
    loadInstallerJson,
});
setChangeDapServerTraceLevelFn(changeDapServerTraceLevel);
setRenderDapTracesForSessionFn(renderDapTracesForSession);
setRenderMcpConsoleWithHighlightsFn(renderMcpConsoleWithHighlights);

// ========== Register actions ==========

registerActions('click', {
    switchTab: (el) => switchTab(el.dataset.tab, el),
    toggleTheme: () => toggleTheme(),
    showAddExtensionForm: () => showAddExtensionForm(),
    hideConfirmModal: () => hideConfirmModal(),
    hideConfirmModalOverlay: (el, e) => {
        if (e.target === el) hideConfirmModal();
    },
});

// ========== Init ==========

(async function init() {
    await loadLspConfigs();
    await loadDapConfigs();
    connectAdminWebSocket();

    KeyboardShortcuts.register({
        getActiveConsole: () => {
            const dapTracesContainer = document.getElementById(`dap-traces-container-${state.currentDapSessionId}`);
            if (dapTracesContainer && state.currentDapSessionId) {
                return {
                    type: 'dap',
                    containerId: `dap-traces-container-${state.currentDapSessionId}`,
                    data: [
                        ...(state.currentDapServerId && state.dapTracesByServer?.[state.currentDapServerId] || []),
                        ...(state.dapTracesBySession?.[state.currentDapSessionId] || [])
                    ]
                };
            }

            const consoleOutput = document.getElementById('console-output');
            if (consoleOutput && state.selectedServer) {
                return {
                    type: 'lsp',
                    containerId: 'console-output',
                    data: state.tracesByServer[traceKey(state.selectedWorkspace, state.currentServerId)] || []
                };
            }

            const mcpConsoleOutput = document.getElementById('mcp-console-output');
            if (mcpConsoleOutput && state.currentTab === 'mcp-traces' && getSelectedMcpClient()) {
                return {
                    type: 'mcp',
                    containerId: 'mcp-console-output',
                    data: (getMcpTracesByClient()[getSelectedMcpClient()]) || []
                };
            }

            return null;
        },
        onSearch: () => {
            const consoleOutput = document.getElementById('console-output');
            const mcpConsoleOutput = document.getElementById('mcp-console-output');
            const dapTracesContainer = document.getElementById(`dap-traces-container-${state.currentDapSessionId}`);

            const hasLspConsole = consoleOutput && state.selectedServer;
            const hasMcpConsole = mcpConsoleOutput && state.currentTab === 'mcp-traces' && getSelectedMcpClient();
            const hasDapConsole = dapTracesContainer && state.currentDapSessionId;

            if (hasLspConsole || hasMcpConsole || hasDapConsole) {
                const searchBox = document.getElementById('search-box');
                const searchInput = document.getElementById('search-input');
                if (searchBox && searchInput) {
                    searchBox.classList.add('visible');
                    searchInput.focus();
                    searchInput.select();
                }
            }
        },
        onCloseSearch: () => {
            closeSearch();
        }
    });
})();
