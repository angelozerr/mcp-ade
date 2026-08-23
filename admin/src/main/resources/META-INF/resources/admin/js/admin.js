import { state, getCurrentTheme, setTheme, updateThemeIcon, traceKey,
    formatStatusClass, formatStatusLabel, updateSearchBoxVisibility,
    loadLspConfigs, loadDapConfigs, loadBspConfigs, loadRuntimeConfigs } from './shared-state.js';
import { initModalOverlay, hideConfirmModal, appendInstallTrace, updateInstallProgress, renderServerActions } from './shared-ui.js';
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
    setRenderDapTracesForSessionFn, setRenderMcpConsoleWithHighlightsFn,
    setCurrentTraceLevel } from './admin-workspace.js';
import { loadAllLspServers, saveInstallerJson, resetInstallerJson, runInstaller,
    loadInstallerJson } from './admin-lsp.js';
import { loadAllDapServers, onDapSessionUpdate, renderDapTracesForSession,
    createSessionHTML, changeDapServerTraceLevel,
    setSelectDapSessionByServerIdCallback } from './admin-dap.js';
import { loadAllBspServers } from './admin-bsp.js';
import { loadAllRuntimes, updateRuntimeStatus, appendRuntimeTrace, setSwitchTabCallback as setRuntimeSwitchTabCallback } from './admin-runtimes.js';
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
        let message;
        try {
            message = JSON.parse(event.data);
        } catch (e) {
            console.error('Failed to parse WebSocket message:', e);
            return;
        }
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
    switch (message.type) {
        case 'lsp-trace':
            handleLspTrace(message);
            break;
        case 'dap-trace':
            handleDapTrace(message);
            break;
        case 'bsp-trace':
            handleBspTrace(message);
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
        case 'runtime-status-changed':
            handleRuntimeStatusChanged(message);
            break;
        case 'runtime-trace':
            appendRuntimeTrace(message);
            break;
        default:
            console.warn('Unknown WebSocket message type:', message.type);
    }
}

// ========== WebSocket message handlers ==========

function pushTrace(container, key, trace, maxSize = 200) {
    if (!container[key]) container[key] = [];
    if (trace.messageType === 'UPDATE') {
        const traces = container[key];
        const lastTrace = traces[traces.length - 1];
        if (lastTrace && lastTrace.messageType === 'UPDATE') {
            traces[traces.length - 1] = trace;
        } else {
            traces.push(trace);
        }
    } else {
        container[key].push(trace);
    }
    if (container[key].length > maxSize) {
        container[key] = container[key].slice(-maxSize);
    }
}

function handleLspTrace(trace) {
    if (state.installOutputServerId === trace.serverId) {
        appendInstallTrace(trace);
    }

    const tk = traceKey(trace.workspaceUri, trace.serverId);
    pushTrace(state.tracesByServer, tk, trace);

    if ((trace.messageType === 'INFO' || trace.messageType === 'UPDATE' || trace.messageType === 'ERROR') &&
        !state.currentServerId && state.currentTab === 'workspaces') {
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
    if (state.installOutputServerId === trace.serverId) {
        appendInstallTrace(trace);
    }

    if (trace.sessionId) {
        pushTrace(state.dapTracesBySession, trace.sessionId, trace);

        if (state.currentDapSessionId === trace.sessionId) {
            renderDapTracesForSession(trace.sessionId);
        }
    } else if (trace.serverId) {
        pushTrace(state.dapTracesByServer, trace.serverId, trace);

        if (state.currentDapSessionId && state.currentDapServerId === trace.serverId) {
            renderDapTracesForSession(state.currentDapSessionId);
        }
    }
}

function handleBspTrace(trace) {
    if (!trace.serverId) return;

    if (state.installOutputServerId === trace.serverId) {
        appendInstallTrace(trace);
    }

    const tk = traceKey(trace.workspaceUri, trace.serverId);
    pushTrace(state.tracesByServer, tk, trace);

    if (tk === traceKey(state.selectedWorkspace, state.currentServerId) ||
        (trace.workspaceUri == null && trace.serverId === state.currentServerId)) {
        renderConsole();
    }

    if (state.currentWorkspaceTab === 'build') {
        const workspace = trace.workspaceUri
            ? state.workspaces.find(w => w.rootUri === trace.workspaceUri)
            : state.workspaces.find(w => w.bspServers && w.bspServers.some(s => s.id === trace.serverId));
        if (workspace) {
            loadServers(state.selectedWorkspace);
        }
    }
}

function handleWorkspacesUpdate(newWorkspaces) {
    // Preserve lazily-loaded servers from previous workspace objects
    for (const newWs of newWorkspaces) {
        const oldWs = state.workspaces.find(w => w.rootUri === newWs.rootUri);
        if (oldWs?.lspServers) {
            newWs.lspServers = oldWs.lspServers;
        }
        if (oldWs?.bspServers) {
            newWs.bspServers = oldWs.bspServers;
        }
    }
    state.workspaces = newWorkspaces;
    workspacesRendered = true;
    renderWorkspaces();

    if (state.selectedWorkspace) {
        const workspace = state.workspaces.find(w => w.rootUri === state.selectedWorkspace);
        if (workspace) {
            switchWorkspaceTab(state.currentWorkspaceTab || 'servers');
        } else {
            state.selectedWorkspace = null;
            document.getElementById('servers-list').innerHTML = '<div class="servers-placeholder">No workspaces selected</div>';
        }
    } else if (state.workspaces.length > 0 && state.currentTab === 'workspaces') {
        selectWorkspace(state.workspaces[0].rootUri);
    }
}

function handleTraceLevelUpdate(message) {
    const isWorkspaceScoped = !!message.workspaceUri;

    if (!isWorkspaceScoped) {
        // Global trace level change — update state.traceLevels
        const key = message.serverId
            ? message.serverType + '.' + message.serverId
            : message.serverType;
        state.traceLevels[key] = message.traceLevel;
    }

    if (message.serverType === 'lsp') {
        // Update in-memory server DTO if this is for the matching workspace
        if (isWorkspaceScoped) {
            const ws = (state.workspaces || []).find(w => w.rootUri === message.workspaceUri);
            const srv = ws?.lspServers?.find(s => s.id === message.serverId);
            if (srv) srv.traceLevel = message.traceLevel;
        }
        // Update workspace view if this server is currently selected in the matching workspace
        const isCurrentServer = state.currentServerId === message.serverId;
        const isCurrentWorkspace = !isWorkspaceScoped || state.selectedWorkspace === message.workspaceUri;
        if (isCurrentServer && isCurrentWorkspace) {
            setCurrentTraceLevel(message.traceLevel);
            updateTraceControls('trace', message.traceLevel);
            renderConsole();
        }
    } else if (message.serverType === 'mcp') {
        setMcpTraceLevel(message.traceLevel);
        updateTraceControls('mcp-trace', message.traceLevel);
        renderMcpConsole();
    } else if (message.serverType === 'bsp') {
        if (state.selectedServer?.isBsp && state.selectedServer?.id === message.serverId) {
            const isCurrentWorkspace = !isWorkspaceScoped || state.selectedWorkspace === message.workspaceUri;
            if (isCurrentWorkspace) {
                state.selectedServer.traceLevel = message.traceLevel;
                setCurrentTraceLevel(message.traceLevel);
                updateTraceControls('trace', message.traceLevel);
                renderConsole();
            }
        }
    } else if (message.serverType === 'dap') {
        // Update workspace view selected server
        if (state.selectedServer?.isDap && state.selectedServer?.id === message.serverId) {
            const isCurrentWorkspace = !isWorkspaceScoped || state.selectedWorkspace === message.workspaceUri;
            if (isCurrentWorkspace) {
                state.selectedServer.traceLevel = message.traceLevel;
                setCurrentTraceLevel(message.traceLevel);
                updateTraceControls('trace', message.traceLevel);
                renderConsole();
            }
        }
        if (state.currentDapServerId === message.serverId) {
            updateTraceControls('dap-trace', message.traceLevel);
            if (state.currentDapSessionId) {
                renderDapTracesForSession(state.currentDapSessionId);
            }
        }
    }
}

function handleServerStatusChanged(event) {
    console.log('Server status changed:', event);

    const workspace = state.workspaces.find(w => w.rootUri === event.workspaceUri);
    if (!workspace) {
        console.warn('Badge: workspace not found. event.workspaceUri=', event.workspaceUri, 'state.workspaces rootUris=', state.workspaces.map(w => w.rootUri));
        return;
    }

    const serverType = event.serverType || 'LSP';
    const servers = serverType === 'BSP' ? workspace.bspServers : workspace.lspServers;
    if (!servers) {
        console.warn('Badge: servers array is null for type', serverType);
        if (state.selectedWorkspace === event.workspaceUri) {
            loadServers(state.selectedWorkspace);
        }
        return;
    }

    const changedServer = servers.find(s => s.id === event.serverId);
    if (!changedServer) {
        console.warn('Badge: server not found:', event.serverId, 'in', servers.map(s => s.id));
        if (state.selectedWorkspace === event.workspaceUri) {
            loadServers(state.selectedWorkspace);
        }
        return;
    }

    changedServer.status = event.newStatus;
    changedServer.statusMessage = event.statusMessage;
    changedServer.installProgress = event.installProgress;
    changedServer.isReady = event.isReady;

    if (serverType === 'LSP') {
        const extensions = servers.filter(s => s.parentServerId === event.serverId);
        for (const ext of extensions) {
            ext.status = event.newStatus;
            ext.isReady = event.isReady;
            ext.statusMessage = event.statusMessage;
            ext.installProgress = event.installProgress;
            ext.pid = changedServer.pid;
            ext.command = changedServer.command;
        }

        if (state.selectedWorkspace === event.workspaceUri) {
            for (const ext of extensions) {
                updateServerStatusBadge(ext.id, ext);
            }
        }
    }

    if (state.selectedWorkspace === event.workspaceUri) {
        console.log('Badge: updating badge for', event.serverId, 'status=', changedServer.status);
        updateServerStatusBadge(event.serverId, changedServer);

        if (state.selectedServer && state.selectedServer.id === event.serverId) {
            updateDetailPanelStatusBadge(changedServer);
        }
    } else {
        console.warn('Badge: workspace mismatch. selectedWorkspace=', state.selectedWorkspace, 'event.workspaceUri=', event.workspaceUri);
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
    if (state.bspConfigs && state.bspConfigs[serverId]) {
        state.bspConfigs[serverId].enabled = enabled;
    }

    for (const ws of state.workspaces) {
        if (ws.lspServers) {
            const srv = ws.lspServers.find(s => s.id === serverId);
            if (srv) srv.enabled = enabled;
        }
    }

    const serverElements = document.querySelectorAll(
        `.server-item[data-server-id="${serverId}"], .server-item[data-dap-server="${serverId}"]`
    );
    for (const serverElement of serverElements) {
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

function handleRuntimeStatusChanged(message) {
    updateRuntimeStatus(message.runtimeId, message.status, message.error);
}

// ========== Status badge updates ==========

function findWorkspaceServerElement(serverId) {
    const container = document.getElementById('servers-list');
    if (!container) return null;
    return container.querySelector(`.server-item[data-server-id="${serverId}"]`);
}

function updateServerStatusBadge(serverId, server) {
    const serverElement = findWorkspaceServerElement(serverId);
    if (!serverElement) {
        console.warn('Badge: serverElement not found for', serverId);
        return;
    }

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
        actionsContainer.innerHTML = renderServerActions(serverId, server);
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
        const panels = ['workspaces-list', 'lsp-servers-list', 'dap-servers-list', 'bsp-servers-list', 'runtimes-list', 'extensions-container', 'mcp-traces-list'];
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
    } else if (tab === 'bsp-servers') {
        showSidebarPanel('bsp-servers-list');
        serversColumn.style.display = 'none';
        consoleColumn.style.display = 'flex';
        appContainer.style.gridTemplateColumns = '400px 1fr';
        consoleColumn.style.gridColumn = '2';

        loadAllBspServers(options.serverId);
    } else if (tab === 'runtimes') {
        showSidebarPanel('runtimes-list');
        serversColumn.style.display = 'none';
        consoleColumn.style.display = 'flex';
        appContainer.style.gridTemplateColumns = '400px 1fr';
        consoleColumn.style.gridColumn = '2';

        loadAllRuntimes(options.runtimeId);
        updateSearchBoxVisibility(false);
    } else if (tab === 'extensions') {
        showSidebarPanel('extensions-container');
        serversColumn.style.display = 'none';
        consoleColumn.style.display = 'flex';
        appContainer.style.gridTemplateColumns = '400px 1fr';
        consoleColumn.style.gridColumn = '2';

        loadAllExtensions(options.extensionId);
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
setRuntimeSwitchTabCallback(switchTab);
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
    navigateToRuntime: (el) => {
        const runtimeId = el.dataset.runtimeId;
        switchTab('runtimes', null, { runtimeId });
    },
    navigateToExtension: (el) => {
        const extensionId = el.dataset.extensionId;
        switchTab('extensions', null, { extensionId });
    },
});

// ========== Init ==========

(async function init() {
    await loadLspConfigs();
    await loadDapConfigs();
    await loadBspConfigs();
    await loadRuntimeConfigs();
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
                const globalTraces = state.tracesByServer[traceKey(null, state.currentServerId)] || [];
                const workspaceTraces = state.tracesByServer[traceKey(state.selectedWorkspace, state.currentServerId)] || [];
                return {
                    type: 'lsp',
                    containerId: 'console-output',
                    data: globalTraces.length > 0 ? [...globalTraces, ...workspaceTraces] : workspaceTraces
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
