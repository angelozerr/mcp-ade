import { state, formatStatusClass, formatStatusLabel, formatWorkspaceContributeInfo, buildWorkspaceContributedByMap, traceKey, getServerApiBase, mergeServerData, mergeBspServerData, updateSearchBoxVisibility } from './shared-state.js';
import { confirmAction, showAlert, showConfirmModal, hideConfirmModal, renderDocumentSelector, runServerInstaller, renderServerActions } from './shared-ui.js';
import { formatContributionsSection } from './shared-contributions.js';
import { renderWorkspaceDiagram, renderServerDiagram } from './diagram.js';
import { renderProgressBadge } from './progress-renderer.js';
import { getWorkspaceDisplayName } from './trace-renderer.js';
import {
    renderTraceControls, updateTraceControls, renderTracesInContainer,
    getCurrentSearchQuery, toggleAllTraces, highlightText, escapeHtml,
    initSearchListeners, initTraceContainer, toggleTrace, showTooltip, hideTooltip
} from './trace-renderer.js';
import { registerActions } from './event-delegation.js';
import { renderSettingsPanel, renderToggleSetting, renderServerSetting, renderActionItem, resetWorkspaceSetting, setWorkspaceSetting } from './admin-settings.js';
import { selectDapSession as selectDapSessionImpl, createNewTestSession as createNewTestSessionImpl } from './admin-dap.js';

        // Global variable to store DAP sessions
        let dapSessions = [];

        // Track if user explicitly selected a server (to prevent auto-switching)
        let userExplicitlySelectedServer = false;

        // Filter to show only active (non-STOPPED) servers
        let showOnlyActiveServers = false;

        // Cross-module callbacks
        let createSessionHTMLFn = null;
        export function setCreateSessionHTMLFn(fn) { createSessionHTMLFn = fn; }

        let installerCallbacks = {};
        export function setInstallerCallbacks(cbs) { installerCallbacks = cbs; }

        let changeDapServerTraceLevelFn = null;
        export function setChangeDapServerTraceLevelFn(fn) { changeDapServerTraceLevelFn = fn; }

        // Callbacks for search re-rendering
        let renderDapTracesForSessionFn = null;
        export function setRenderDapTracesForSessionFn(fn) { renderDapTracesForSessionFn = fn; }

        let renderMcpConsoleWithHighlightsFn = null;
        export function setRenderMcpConsoleWithHighlightsFn(fn) { renderMcpConsoleWithHighlightsFn = fn; }

        async function toggleWorkspaceLspServerEnabled(serverId, enabled) {
            const action = enabled ? 'enable' : 'disable';
            try {
                const response = await fetch(`/api/admin/extensions/lsp/servers/${serverId}/${action}`, { method: 'POST' });
                if (response.ok) {
                    if (state.lspConfigs[serverId]) {
                        state.lspConfigs[serverId].enabled = enabled;
                    }
                    const workspace = state.workspaces.find(w => w.rootUri === state.selectedWorkspace);
                    if (workspace && workspace.lspServers) {
                        const srv = workspace.lspServers.find(s => s.id === serverId);
                        if (srv) srv.enabled = enabled;
                    }
                    const serverElement = document.querySelector(`.server-item[data-server-id="${serverId}"]`);
                    if (serverElement) {
                        if (enabled) {
                            serverElement.classList.remove('server-disabled');
                        } else {
                            serverElement.classList.add('server-disabled');
                        }
                    }
                }
            } catch (error) {
                console.error(`Failed to ${action} LSP server:`, error);
            }
        }

        async function toggleWorkspaceDapServerEnabled(serverId, enabled) {
            const action = enabled ? 'enable' : 'disable';
            try {
                const response = await fetch(`/api/admin/extensions/dap/servers/${serverId}/${action}`, { method: 'POST' });
                if (response.ok) {
                    if (state.dapConfigs && state.dapConfigs[serverId]) {
                        state.dapConfigs[serverId].enabled = enabled;
                    }
                    const serverElement = document.querySelector(`.server-item[data-server-id="${serverId}"]`);
                    if (serverElement) {
                        if (enabled) {
                            serverElement.classList.remove('server-disabled');
                        } else {
                            serverElement.classList.add('server-disabled');
                        }
                    }
                }
            } catch (error) {
                console.error(`Failed to ${action} DAP server:`, error);
            }
        }

        function toggleShowActiveServers() {
            showOnlyActiveServers = !showOnlyActiveServers;
            const workspace = state.workspaces.find(w => w.rootUri === state.selectedWorkspace);
            if (!workspace) return;

            if (state.currentWorkspaceTab === 'debuggers') {
                const sessions = state.dapSessions || [];
                renderServers([], sessions, workspace);
            } else {
                renderServers(workspace.lspServers || [], [], workspace);
            }
        }

        // Load DAP sessions from API
        async function loadDapSessions() {
            try {
                const response = await fetch('/api/admin/dap/sessions');
                if (response.ok) {
                    dapSessions = await response.json();
                    console.log('Loaded DAP sessions:', dapSessions);
                }
            } catch (error) {
                console.error('Failed to load DAP sessions:', error);
            }
        }

        export function renderWorkspaces() {
            const container = document.getElementById('workspaces-list');

            if (state.workspaces.length === 0) {
                container.innerHTML = `
                    <div class="empty-workspaces">
                        <div class="empty-workspaces-title">No Workspaces Yet</div>
                        <div class="empty-workspaces-text">
                            Workspaces appear when an MCP client (Claude Desktop, Bob Shell, etc.)
                            calls an MCP tool with a <code>cwd</code> parameter.
                        </div>
                        <div class="empty-workspaces-text">
                            The <code>cwd</code> (current working directory) identifies the project/workspace
                            and triggers LSP server initialization.
                        </div>
                        <div class="empty-workspaces-hint">
                            💡 Try calling: get_diagnostics(cwd: "/path/to/project", fileUri: "file://...")
                        </div>
                    </div>
                `;

                // Clear servers and console
                state.selectedWorkspace = null;
                state.selectedServer = null;
                document.getElementById('servers-list').innerHTML = '<div class="servers-placeholder">No workspaces selected</div>';
                document.getElementById('console-area').innerHTML = `
                    <div class="placeholder">
                        ← Select a workspace and LSP server to view console
                    </div>
                `;

                return;
            }

            container.innerHTML = state.workspaces.map(ws => {
                // Extract folder name from URI
                const folderName = getWorkspaceDisplayName(ws.rootUri);

                return `
                <div class="workspace-item ${ws.rootUri === state.selectedWorkspace ? 'active' : ''}" data-action="selectWorkspace" data-uri="${ws.rootUri}">
                    <div class="d-flex justify-between align-center">
                        <div class="workspace-uri flex-1" title="${ws.rootUri}">📂 ${folderName}</div>
                        <button class="close-workspace-btn" data-action="openWorkspaceSettings" data-uri="${ws.rootUri}" data-stop-propagation title="Workspace settings" style="font-size:0.9rem">⚙</button>
                        <button class="close-workspace-btn" data-action="buildWorkspaceFromList" data-uri="${ws.rootUri}" data-stop-propagation title="Build workspace" style="font-size:0.9rem">🔨</button>
                        <button class="close-workspace-btn" data-action="refreshWorkspaceFromList" data-uri="${ws.rootUri}" data-stop-propagation title="Refresh workspace" style="font-size:1.1rem">↻</button>
                        <button class="close-workspace-btn" data-action="closeWorkspace" data-uri="${ws.rootUri}" data-stop-propagation title="Close workspace and stop all servers">×</button>
                    </div>
                    ${ws.mcpClients && ws.mcpClients.length > 0 ? `
                        <div class="workspace-section">
                            <div class="workspace-section-title">AI Agents</div>
                            ${ws.mcpClients.map(client => {
                                let timeStr = '';
                                if (client.connectedAt) {
                                    try {
                                        const date = new Date(client.connectedAt);
                                        timeStr = date.toLocaleTimeString('en-US', { hour: '2-digit', minute: '2-digit' });
                                    } catch (e) {
                                        timeStr = '';
                                    }
                                }
                                const shortId = client.connectionId ? client.connectionId.substring(0, 8) + '...' : '';
                                return `
                                    <div class="workspace-section-item" title="Session: ${client.connectionId || 'N/A'}">
                                        <div>📱 ${client.name}${timeStr ? ` <span class="client-time">@ ${timeStr}</span>` : ''}</div>
                                        ${shortId ? `<div class="text-dimmed font-sm ml-xl mt-xs">Session: ${shortId}</div>` : ''}
                                    </div>
                                `;
                            }).join('')}
                        </div>
                    ` : ''}
                </div>
                `;
            }).join('');
        }

        export function selectWorkspace(uri) {
            // Only reset server selection if we're changing workspace
            if (state.selectedWorkspace !== uri) {
                state.selectedServer = null;
                userExplicitlySelectedServer = false; // Reset explicit selection when changing workspace
            }

            state.selectedWorkspace = uri;

            // Clear DAP session when selecting a workspace (we're now in LSP context)
            state.currentDapSessionId = null;

            renderWorkspaces();

            // Find workspace in local data (already received via WebSocket)
            const workspace = state.workspaces.find(w => w.rootUri === uri);
            console.log('Found workspace in selectWorkspace:', workspace);
            if (workspace) {
                // Render the current tab (will lazy load servers/sessions as needed)
                switchWorkspaceTab(state.currentWorkspaceTab || 'servers');
            } else {
                console.log('Workspace not found, showing placeholder');
                document.getElementById('servers-list').innerHTML = '<div class="servers-placeholder">No LSP servers</div>';
                showPlaceholder();
            }
        }

        async function closeWorkspace(uri) {
            const folderName = getWorkspaceDisplayName(uri);

            showConfirmModal(
                'Close Workspace',
                `
                    <div class="mb-lg font-xl"><strong>⚠️ This will shut down all LSP servers for this workspace</strong></div>
                    <div class="mb-md">Specifically:</div>
                    <ul class="mt-0 mb-lg ml-xl text-left line-height-relaxed">
                        <li><strong>Stop all running language servers</strong></li>
                        <li>Disconnect any IDE connections</li>
                        <li>Remove the workspace from memory</li>
                        <li>Clear all cached data</li>
                    </ul>
                    <div class="callout-info mt-lg">
                        <div><strong>Workspace:</strong> ${folderName}</div>
                        <div class="text-primary mt-sm font-md">💡 The workspace will automatically reappear when an MCP client accesses it again.</div>
                    </div>
                `,
                async () => {
                    try {
                        const response = await fetch(`/api/admin/workspaces/${encodeURIComponent(uri)}`, {
                            method: 'DELETE'
                        });

                        if (!response.ok) {
                            throw new Error('Failed to close workspace');
                        }

                        // Remove workspace from local list and re-render
                        const idx = state.workspaces.findIndex(w => w.rootUri === uri);
                        if (idx !== -1) {
                            state.workspaces.splice(idx, 1);
                        }
                        if (state.selectedWorkspace === uri) {
                            state.selectedWorkspace = state.workspaces.length > 0 ? state.workspaces[0].rootUri : null;
                        }
                        renderWorkspaces();

                    } catch (error) {
                        console.error('Failed to close workspace:', error);
                        alert('Failed to close workspace: ' + error.message);
                    }
                }
            );
        }

        let lastServersData = null;

        export async function loadServers(uri) {
            // Find workspace in local data (already received via WebSocket)
            const workspace = state.workspaces.find(w => w.rootUri === uri);
            if (workspace) {
                // Load LSP servers if not already loaded (lazy loading)
                if (!workspace.lspServers) {
                    await loadLspServersForWorkspace(workspace);
                }

                // Only re-render if servers data actually changed
                const newServersData = JSON.stringify(workspace.lspServers);
                if (newServersData !== lastServersData) {
                    lastServersData = newServersData;
                    // Don't pass dapSessions here - they're loaded separately when clicking "Debuggers"
                    renderServers(workspace.lspServers || [], [], workspace);
                }
            } else {
                console.warn('Workspace not found:', uri);
                document.getElementById('servers-list').innerHTML = '<div class="servers-placeholder">Workspace not found</div>';
            }
        }

        function renderStatusBadge(server) {
            const statusClass = formatStatusClass(server.status);
            const label = formatStatusLabel(server.status, server.externalInstance);

            // In server list, show simple badge (progress bar is shown in detail panel only)
            return `<span class="status-badge ${statusClass}">${label}</span>`;
        }

        export async function renderServers(lspServers, dapSessions = [], workspace = null) {
            const container = document.getElementById('servers-list');
            if (!container) {
                console.error('servers-list element not found!');
                return;
            }

            // Build workspace header (compact, same level as left sidebar)
            const workspaceName = workspace ? getWorkspaceDisplayName(workspace.rootUri) : '';
            const headerHTML = `
                <div class="d-flex align-center detail-section-header">
                    <span class="text-primary font-bold tab-label-sm">📂 ${workspaceName}</span>
                </div>
            `;

            // Build tabs header
            const tabsHTML = `
                <div class="tabs bg-panel" style="border-bottom: 1px solid var(--bg-card);">
                    <div class="tab flex-1 text-center ${state.currentWorkspaceTab === 'servers' ? 'active' : ''}" data-action="switchWorkspaceTab" data-tab="servers">Servers</div>
                    <div class="tab flex-1 text-center ${state.currentWorkspaceTab === 'debuggers' ? 'active' : ''}" data-action="switchWorkspaceTab" data-tab="debuggers">Debuggers</div>
                    <div class="tab flex-1 text-center ${state.currentWorkspaceTab === 'build' ? 'active' : ''}" data-action="switchWorkspaceTab" data-tab="build">Build</div>
                </div>
            `;

            const appContainer = document.querySelector('.app-container');

            const consoleContainer = document.getElementById('console-container');

            // Settings mode: servers-sidebar spans full width
            if (state.currentWorkspaceTab === 'settings') {
                appContainer.classList.add('settings-mode');
                consoleContainer.style.display = 'none';
                const settingsHeaderHTML = `
                    <div class="d-flex align-center detail-section-header">
                        <button class="editor-btn" data-action="switchWorkspaceTab" data-tab="servers" style="margin-right:0.5rem" title="Back to servers">←</button>
                        <span class="text-primary font-bold tab-label-sm">⚙ Settings</span>
                    </div>
                `;
                container.innerHTML =
                    '<div class="workspace-servers-header">' + headerHTML + settingsHeaderHTML + '</div>' +
                    '<div class="workspace-servers-content">' + renderWorkspaceSettings(workspace) + '</div>';
                return;
            }

            // Normal mode: show tabs + servers/debuggers
            appContainer.classList.remove('settings-mode');
            consoleContainer.style.display = '';

            const filterHTML = `
                <div class="d-flex align-center bg-panel console-line">
                    <label class="text-secondary d-flex align-center cursor-pointer gap-sm user-select-none">
                        <input type="checkbox" data-action="toggleShowActiveServers" ${showOnlyActiveServers ? 'checked' : ''}>
                        Show active only
                    </label>
                </div>
            `;

            let contentHTML = '';
            if (state.currentWorkspaceTab === 'servers') {
                contentHTML = (lspServers && lspServers.length > 0) ? renderLspServers(lspServers) : '<div class="servers-placeholder">No LSP servers</div>';
            } else if (state.currentWorkspaceTab === 'debuggers') {
                const dapServers = Object.values(state.dapConfigs || {});
                contentHTML = (dapServers.length > 0 || dapSessions.length > 0)
                    ? renderDapServers(dapServers, dapSessions)
                    : '<div class="servers-placeholder">No debug adapters</div>';
            } else if (state.currentWorkspaceTab === 'build') {
                const workspace = state.workspaces.find(w => w.rootUri === state.selectedWorkspace);
                if (workspace && !workspace.bspServers) {
                    await loadBspServersForWorkspace(workspace);
                }
                const bspServers = workspace?.bspServers || [];
                contentHTML = bspServers.length > 0
                    ? renderBspServersInWorkspace(bspServers)
                    : '<div class="servers-placeholder">No build servers</div>';
            }

            container.innerHTML =
                '<div class="workspace-servers-header">' + headerHTML + tabsHTML + filterHTML + '</div>' +
                '<div class="workspace-servers-content">' + contentHTML + '</div>';

            // Auto-select first BSP server after rendering
            if (state.currentWorkspaceTab === 'build') {
                const workspace = state.workspaces.find(w => w.rootUri === state.selectedWorkspace);
                const bspServers = workspace?.bspServers || [];
                if (bspServers.length > 0) {
                    const isBspServerSelected = state.selectedServer && state.selectedServer.isBsp && bspServers.find(s => s.id === state.selectedServer.id);
                    if (!isBspServerSelected) {
                        selectBspServer(bspServers[0]);
                    }
                }
            }

            if (state.currentWorkspaceTab === 'debuggers' && Object.values(state.dapConfigs || {}) && Object.values(state.dapConfigs || {}).length > 0) {
                const isDapServerSelected = state.selectedServer && Object.values(state.dapConfigs || {}).find(s => s.id === state.selectedServer.id);

                if (!isDapServerSelected) {
                    // Select first DAP server
                    const firstDapServer = Object.values(state.dapConfigs || {})[0];
                    selectDapServer(firstDapServer);
                }
            }
        }

        function renderWorkspaceSettings(workspace) {
            if (!workspace) return '<div class="servers-placeholder">No workspace selected</div>';

            const fwEnabled = workspace.fileWatcherEnabled || false;
            const fwSource = workspace.fileWatcherEnabledSource || 'DEFAULT';
            const fwRunning = workspace.fileWatcherRunning || false;
            const fwStatusHtml = fwRunning
                ? '<span class="status-badge status-running" style="font-size:0.7rem;padding:0.1rem 0.4rem">watching</span>'
                : (fwEnabled ? '<span class="status-badge status-stopped" style="font-size:0.7rem;padding:0.1rem 0.4rem">stopped</span>' : '');

            const uri = workspace.rootUri;

            const actionsItems = [
                renderActionItem({
                    label: 'Build',
                    description: 'Build workspace sources (auto full/incremental)',
                    buttonLabel: '▶ Build',
                    buttonAction: 'buildWorkspaceFromSettings',
                    dataAttrs: { uri },
                    buttonClass: 'install-run-btn'
                }),
                renderActionItem({
                    label: 'Refresh',
                    description: 'Sync file system changes with the workspace',
                    buttonLabel: '↻ Refresh',
                    buttonAction: 'refreshWorkspaceFromSettings',
                    dataAttrs: { uri }
                })
            ];

            const settingsItems = [
                renderToggleSetting({
                    label: 'File Watcher',
                    description: 'Watch for file changes and notify language servers',
                    value: fwEnabled,
                    source: fwSource,
                    toggleAction: 'toggleFileWatcherFromSettings',
                    resetAction: 'resetFileWatcherSetting',
                    dataAttrs: { uri },
                    statusHtml: fwStatusHtml
                })
            ];

            return `
                <div class="p-sm">
                    ${renderSettingsPanel({ title: 'Actions', itemsHtml: actionsItems })}
                    ${renderSettingsPanel({ title: 'Settings', itemsHtml: settingsItems })}
                </div>
            `;
        }

        export async function switchWorkspaceTab(tab) {
            state.currentWorkspaceTab = tab;
            const workspace = state.workspaces.find(w => w.rootUri === state.selectedWorkspace);
            if (!workspace) return;

            if (tab === 'settings') {
                state.selectedServer = null;
                renderServers(workspace.lspServers || [], [], workspace);
            } else if (tab === 'servers') {
                // Load LSP servers lazy
                if (!workspace.lspServers) {
                    await loadLspServersForWorkspace(workspace);
                }
                state.selectedServer = null;
                renderWorkspaces();
                showPlaceholder();
                renderServers(workspace.lspServers || [], [], workspace);
            } else if (tab === 'debuggers') {
                // Load DAP sessions lazy
                await loadDapSessionsForWorkspace();
                renderServers([], dapSessions, workspace);
            } else if (tab === 'build') {
                state.selectedServer = null;
                renderServers([], [], workspace);
            }
        }

        async function loadLspServersForWorkspace(workspace) {
            try {
                const response = await fetch(`/api/admin/workspaces/${encodeURIComponent(workspace.rootUri)}/lsp-servers`);
                if (response.ok) {
                    const servers = await response.json();
                    // Merge runtime data with configs (for name, description, etc.)
                    workspace.lspServers = servers.map(s => mergeServerData(s));
                }
            } catch (error) {
                console.error('Failed to load LSP servers:', error);
                workspace.lspServers = [];
            }
        }

        async function loadBspServersForWorkspace(workspace) {
            try {
                const response = await fetch(`/api/admin/workspaces/${encodeURIComponent(workspace.rootUri)}/bsp-servers`);
                if (response.ok) {
                    const servers = await response.json();
                    workspace.bspServers = servers.map(s => mergeBspServerData(s));
                }
            } catch (error) {
                console.error('Failed to load BSP servers:', error);
                workspace.bspServers = [];
            }
        }

        export async function loadDapSessionsForWorkspace() {
            if (!state.selectedWorkspace) {
                dapSessions = [];
                state.dapSessions = [];
                return;
            }

            try {
                const response = await fetch(`/api/admin/workspaces/${encodeURIComponent(state.selectedWorkspace)}/dap-sessions`);
                if (response.ok) {
                    dapSessions = await response.json();
                    state.dapSessions = dapSessions; // Sync with state for admin-dap.js
                    console.log('Loaded DAP sessions for workspace:', state.selectedWorkspace, 'count:', dapSessions.length);
                }
            } catch (error) {
                console.error('Failed to load DAP sessions:', error);
                dapSessions = [];
                state.dapSessions = [];
            }
        }

        function renderLspServers(serversRuntime) {
            if (serversRuntime.length === 0) {
                return '';
            }

            // Merge runtime with configs
            // Servers are already merged in handleWorkspacesUpdate()
            let servers = serversRuntime.sort((a, b) => (a.name || '').localeCompare(b.name || ''));

            // Filter to active servers only if toggle is on
            if (showOnlyActiveServers) {
                servers = servers.filter(s => s.status !== 'STOPPED');
            }

            if (servers.length === 0) {
                return '<div class="servers-placeholder">No active servers</div>';
            }

            // Calculate contributedBy for all servers
            const contributedByMap = buildWorkspaceContributedByMap(servers);

            // Auto-select server logic:
            // ONLY auto-switch if user has NOT explicitly selected a server
            // 1. If there's a server with status != STOPPED, auto-select it (prefer RUNNING over others)
            // 2. If a server is selected and still exists, keep it if it's != STOPPED
            // 3. Otherwise, select first non-STOPPED server
            // 4. Otherwise, select first server

            if (state.selectedServer) {
                const currentServer = servers.find(s => s.id === state.selectedServer.id);
                if (currentServer) {
                    // Only auto-switch if user has NOT explicitly selected
                    if (!userExplicitlySelectedServer && currentServer.status === 'STOPPED') {
                        // Prefer RUNNING, then any non-STOPPED status
                        const runningServer = servers.find(s => s.status === 'RUNNING');
                        const activeServer = runningServer || servers.find(s => s.status !== 'STOPPED');
                        if (activeServer) {
                            console.log('Auto-switching from', state.selectedServer.id, '(STOPPED) to active server:', activeServer.id, '(status:', activeServer.status, ')');
                            selectServer(activeServer, false); // false = not a user action
                        }
                    } else {
                        console.log('Keeping selected server:', state.selectedServer.id, '(status:', currentServer.status, ', userExplicit:', userExplicitlySelectedServer, ')');
                    }
                } else {
                    console.log('Selected server no longer exists, auto-selecting...');
                    state.selectedServer = null;
                    userExplicitlySelectedServer = false; // Reset since server disappeared
                }
            }

            // If no server selected, auto-select first non-STOPPED server
            if (!state.selectedServer && servers.length > 0) {
                console.log('Auto-selecting server - selectedServer is null, servers:', servers.length);
                const runningServer = servers.find(s => s.status === 'RUNNING');
                const activeServer = runningServer || servers.find(s => s.status !== 'STOPPED');
                const serverToSelect = activeServer || servers[0];
                console.log('Server to auto-select:', serverToSelect.id, '(status:', serverToSelect.status, ')');
                selectServer(serverToSelect, false); // false = not a user action
            }

            return `
                ${servers.map(server => {
                    // Same HTML as before
                    const isExternal = server.externalInstance != null &&
                                       (server.status === 'CONNECTED_TO_IDE' || server.status === 'CONNECTING_TO_IDE');
                    const serverClass = isExternal ? 'server-item-external' : 'server-item-managed';
                    const extensionClass = server.isExtension ? 'server-extension' : '';
                    const disabledClass = server.enabled === false ? 'server-disabled' : '';
                    const extensionBadge = server.isExtension ? ' <span class="text-secondary font-md">(Extension)</span>' : '';

                    const actions = server.isExtension ? '' : renderServerActions(server.id, server);

                    const sourceIcon = isExternal ? '🔗' : (server.isExtension ? '🧩' : '🚀');
                    const sourceLabel = isExternal
                        ? `Connected to IDE (port ${server.externalInstance.port}, PID ${server.externalInstance.pid})`
                        : (server.isExtension ? 'Extension' : 'Managed by MCP');

                    let ideInfo = '';
                    if (isExternal && server.externalInstance) {
                        ideInfo = `
                            <span class="server-ide-info">
                                <span title="Port">:${server.externalInstance.port}</span>
                                <span title="Process ID">PID ${server.externalInstance.pid}</span>
                            </span>
                        `;
                    }

                    const tooltipText = server.command ? `Command: ${server.command}` : '';
                    const contributedInfo = formatWorkspaceContributeInfo(server, contributedByMap);

                    return `
                        <div class="server-item ${serverClass} ${extensionClass} ${disabledClass} ${state.selectedServer?.id === server.id ? 'active' : ''}"
                             data-server-id="${server.id}"
                             data-action="selectServerItem"
                             ${tooltipText ? `title="${tooltipText.replace(/"/g, '&quot;')}"` : ''}>
                            <div class="server-name d-flex align-center justify-between">
                                <span>
                                    <span class="server-source-icon" title="${sourceLabel}">${sourceIcon}</span>
                                    ${server.name}${extensionBadge}
                                </span>
                                <label class="toggle-switch" onclick="event.stopPropagation()">
                                    <input type="checkbox" ${server.enabled !== false ? 'checked' : ''} data-action="toggleWorkspaceLspServerEnabled" data-server-id="${server.id}">
                                    <span class="toggle-slider"></span>
                                </label>
                            </div>
                            <div class="server-id" ${contributedInfo.tooltip ? `title="${contributedInfo.tooltip}"` : ''}>${server.id}${contributedInfo.text}</div>
                            <div class="server-status-badge-container">
                                ${renderStatusBadge(server)}
                                ${server.statusMessage ? `<span class="server-status-message text-secondary font-md ml-sm">${escapeHtml(server.statusMessage)}</span>` : ''}
                                ${!server.isExtension ? ideInfo : ''}
                                ${!server.isExtension && server.pid ? `<span class="server-ide-info"><span title="Process ID">${server.pid}</span></span>` : ''}
                            </div>
                            <div class="server-actions">
                                ${actions}
                            </div>
                        </div>
                    `;
                }).join('')}
            `;
        }

        function renderDapServers(dapConfigs, dapSessions) {
            // Merge global DAP configs with workspace sessions
            const sessionsByServerId = {};
            dapSessions.forEach(session => {
                if (!sessionsByServerId[session.serverId]) {
                    sessionsByServerId[session.serverId] = [];
                }
                sessionsByServerId[session.serverId].push(session);
            });

            // Use global dapConfigs, sorted by name
            let configs = Object.values(dapConfigs || {}).sort((a, b) => (a.name || '').localeCompare(b.name || ''));

            // Filter to only DAP configs with active sessions if toggle is on
            if (showOnlyActiveServers) {
                configs = configs.filter(c => (sessionsByServerId[c.id] || []).length > 0);
            }

            if (configs.length === 0 && dapSessions.length === 0) {
                return showOnlyActiveServers ? '<div class="servers-placeholder">No active debug adapters</div>' : '';
            }

            return `
                ${configs.map(server => {
                    const sessions = sessionsByServerId[server.id] || [];
                    const isInstalled = server.installed;

                    // Actions for debugger (like LSP servers)
                    let actions = `
                        <button class="server-action-btn" data-action="createNewTestSession" data-server-id="${server.id}" data-stop-propagation title="New Test Launch">+</button>
                    `;

                    const disabledClass = server.enabled === false ? 'server-disabled' : '';

                    return `
                        <div class="server-item ${disabledClass} ${state.selectedServer?.id === server.id ? 'active' : ''} cursor-pointer" data-dap-server="${server.id}" data-action="selectDapServerItem" data-server-id="${server.id}">
                            <div class="server-name d-flex align-center justify-between">
                                <span>
                                    <span class="server-source-icon">🐛</span>
                                    ${server.name}
                                </span>
                                <label class="toggle-switch" onclick="event.stopPropagation()">
                                    <input type="checkbox" ${server.enabled !== false ? 'checked' : ''} data-action="toggleWorkspaceDapServerEnabled" data-server-id="${server.id}">
                                    <span class="toggle-slider"></span>
                                </label>
                            </div>
                            <div class="server-id">${server.id}</div>
                            <div class="server-actions">
                                ${actions}
                            </div>
                        </div>
                        ${sessions.map(session => {
                            // Use createSessionHTMLFn callback if available, otherwise fallback
                            if (createSessionHTMLFn) {
                                return createSessionHTMLFn(session);
                            }
                            // Fallback (should not happen if admin-dap.js is loaded)
                            return `<div data-session-id="${session.sessionId}" class="dap-session-item">${session.sessionName}</div>`;
                        }).join('')}
                    `;
                }).join('')}
            `;
        }

        function renderBspServersInWorkspace(bspServers) {
            let servers = bspServers.sort((a, b) => (a.name || '').localeCompare(b.name || ''));

            if (showOnlyActiveServers) {
                servers = servers.filter(s => s.status !== 'STOPPED');
            }

            if (servers.length === 0) {
                return showOnlyActiveServers ? '<div class="servers-placeholder">No active build servers</div>' : '<div class="servers-placeholder">No build servers</div>';
            }

            return servers.map(server => {
                const disabledClass = server.enabled === false ? 'server-disabled' : '';
                const isSelected = state.selectedServer?.id === server.id && state.selectedServer?.isBsp;

                const actions = renderServerActions(server.id, server);

                return `
                    <div class="server-item ${disabledClass} ${isSelected ? 'active' : ''} cursor-pointer" data-action="selectBspServerItem" data-server-id="${server.id}">
                        <div class="server-name d-flex align-center justify-between">
                            <span>
                                <span class="server-source-icon">🔨</span>
                                ${server.name}
                            </span>
                            <label class="toggle-switch" onclick="event.stopPropagation()">
                                <input type="checkbox" ${server.enabled !== false ? 'checked' : ''} data-action="toggleWorkspaceBspServerEnabled" data-server-id="${server.id}">
                                <span class="toggle-slider"></span>
                            </label>
                        </div>
                        <div class="server-id">${server.id}</div>
                        <div class="server-status-badge-container">
                            ${renderStatusBadge(server)}
                            ${server.statusMessage ? `<span class="server-status-message text-secondary font-md ml-sm">${escapeHtml(server.statusMessage)}</span>` : ''}
                            ${server.pid ? `<span class="server-ide-info"><span title="Process ID">${server.pid}</span></span>` : ''}
                        </div>
                        <div class="server-actions">
                            ${actions}
                        </div>
                    </div>
                `;
            }).join('');
        }

        function selectBspServer(bspServer) {
            state.selectedServer = {...bspServer, isBsp: true};
            const workspace = state.workspaces.find(w => w.rootUri === state.selectedWorkspace);
            renderServers([], [], workspace);
            loadConsole(state.selectedServer);
        }

        async function toggleWorkspaceBspServerEnabled(serverId, enabled) {
            const action = enabled ? 'enable' : 'disable';
            try {
                const response = await fetch(`/api/admin/extensions/bsp/servers/${serverId}/${action}`, { method: 'POST' });
                if (response.ok) {
                    if (state.bspConfigs && state.bspConfigs[serverId]) {
                        state.bspConfigs[serverId].enabled = enabled;
                    }
                    const serverElement = document.querySelector(`.server-item[data-server-id="${serverId}"]`);
                    if (serverElement) {
                        if (enabled) {
                            serverElement.classList.remove('server-disabled');
                        } else {
                            serverElement.classList.add('server-disabled');
                        }
                    }
                }
            } catch (error) {
                console.error(`Failed to ${action} BSP server:`, error);
            }
        }

        async function startBspServerAction(serverId) {
            if (!state.selectedWorkspace) return;
            try {
                await fetch(
                    `/api/admin/bsp/servers/${encodeURIComponent(state.selectedWorkspace)}/${serverId}/start-managed`,
                    { method: 'POST' }
                );
            } catch (error) {
                console.error('Failed to start BSP server:', error);
                showAlert('Error', 'Failed to start build server: ' + error.message);
            }
        }

        async function restartBspServerAction(serverId) {
            if (!state.selectedWorkspace) return;
            const confirmed = await confirmAction(
                'Restart Build Server',
                `Restart "${serverId}"?\n\nThe server will be stopped and restarted.`,
                'Restart',
                false
            );
            if (!confirmed) return;
            try {
                await fetch(
                    `/api/admin/bsp/servers/${encodeURIComponent(state.selectedWorkspace)}/${serverId}/restart`,
                    { method: 'POST' }
                );
            } catch (error) {
                console.error('Failed to restart BSP server:', error);
                showAlert('Error', 'Failed to restart build server: ' + error.message);
            }
        }

        async function stopBspServerAction(serverId) {
            if (!state.selectedWorkspace) return;
            const confirmed = await confirmAction(
                'Stop Build Server',
                `Stop "${serverId}"?\n\nThe build server process will be terminated.`,
                'Stop',
                true
            );
            if (!confirmed) return;
            try {
                await fetch(
                    `/api/admin/bsp/servers/${encodeURIComponent(state.selectedWorkspace)}/${serverId}/stop`,
                    { method: 'POST' }
                );
            } catch (error) {
                console.error('Failed to stop BSP server:', error);
                showAlert('Error', 'Failed to stop build server: ' + error.message);
            }
        }

        function selectDapSession(sessionId) {
            state.selectedServer = null;
            selectDapSessionImpl(sessionId);
        }

        export function selectDapServer(dapServer) {
            const dapTraceLevel = (state.traceLevels && state.traceLevels['dap.' + dapServer.id]) || 'off';
            state.selectedServer = {...dapServer, isDap: true, traceLevel: dapTraceLevel};
            // Sync local variable with state (may have been updated by DELETED handler)
            dapSessions = state.dapSessions || [];
            const workspace = state.workspaces.find(w => w.rootUri === state.selectedWorkspace);
            renderServers([], dapSessions, workspace);
            loadConsole(state.selectedServer);
        }

        export function selectDapSessionByServerId(serverId) {
            // Find the DAP server from workspace
            const workspace = state.workspaces.find(w => w.rootUri === state.selectedWorkspace);
            const dapServer = Object.values(state.dapConfigs || {})?.find(s => s.id === serverId);

            if (dapServer) {
                // Select the DAP server
                selectDapServer(dapServer);
            } else {
                // Server not found in workspace
                console.log('DAP server not found in workspace:', serverId);
            }
        }

        export function selectServer(server, isUserAction = false) {
            const wasAlreadySelected = state.selectedServer && state.selectedServer.id === server.id;
            state.selectedServer = server;

            // Track if this is an explicit user action
            if (isUserAction) {
                userExplicitlySelectedServer = true;
                console.log('User explicitly selected server:', server.id);
            }

            // Clear DAP session when selecting an LSP server
            state.currentDapSessionId = null;

            // Update active class without full re-render to preserve scroll position
            document.querySelectorAll('.server-item').forEach(el => {
                if (el.dataset.serverId === server.id) {
                    el.classList.add('active');
                } else {
                    el.classList.remove('active');
                }
            });

            // Only reload console if switching to a different server
            if (!wasAlreadySelected) {
                loadConsole(server);
            }
        }

        function showPlaceholder() {
            document.getElementById('console-area').innerHTML = `
                <div class="placeholder">
                    ← Select an LSP server to view console
                </div>
            `;
        }

        let currentTraceLevel = 'off';
        export function setCurrentTraceLevel(level) { currentTraceLevel = level; }

        async function changeTraceLevel(level) {
            currentTraceLevel = level;
            updateTracesButtonsState(level);
            renderConsole();

            // Update in-memory server DTO so re-selecting this server keeps the correct level
            if (state.selectedServer) {
                state.selectedServer.traceLevel = level;
            }

            const uri = state.selectedWorkspace;
            if (state.selectedServer && state.selectedServer.isBsp) {
                if (uri) {
                    try {
                        await fetch(`/api/admin/workspaces/${encodeURIComponent(uri)}/traces/bsp/${encodeURIComponent(state.selectedServer.id)}`, {
                            method: 'PUT',
                            headers: { 'Content-Type': 'application/json' },
                            body: JSON.stringify({ traceLevel: level })
                        });
                        reloadServerSettingsTab(state.selectedServer.id);
                    } catch (error) {
                        console.error('Failed to change BSP trace level:', error);
                    }
                }
            } else if (state.selectedServer && state.selectedServer.isDap) {
                if (uri) {
                    try {
                        await fetch(`/api/admin/workspaces/${encodeURIComponent(uri)}/traces/dap/${encodeURIComponent(state.selectedServer.id)}`, {
                            method: 'PUT',
                            headers: { 'Content-Type': 'application/json' },
                            body: JSON.stringify({ traceLevel: level })
                        });
                        reloadServerSettingsTab(state.selectedServer.id);
                    } catch (error) {
                        console.error('Failed to change DAP trace level:', error);
                    }
                } else if (changeDapServerTraceLevelFn) {
                    changeDapServerTraceLevelFn(state.selectedServer.id, level);
                }
            } else if (state.currentServerId) {
                if (uri) {
                    try {
                        await fetch(`/api/admin/workspaces/${encodeURIComponent(uri)}/traces/lsp/${encodeURIComponent(state.currentServerId)}`, {
                            method: 'PUT',
                            headers: { 'Content-Type': 'application/json' },
                            body: JSON.stringify({ traceLevel: level })
                        });
                        reloadServerSettingsTab(state.currentServerId);
                    } catch (error) {
                        console.error('Failed to change trace level:', error);
                    }
                }
            }
        }

        function updateTracesButtonsState(level) {
            updateTraceControls('trace', level);
        }


        export async function loadConsole(server) {
            // Check if server has contributions
            const workspace = state.workspaces.find(w => w.rootUri === state.selectedWorkspace);
            // Include both LSP and DAP servers for contribution detection (mark DAP servers)
            const dapServersWithFlag = (Object.values(state.dapConfigs || {}) || []).map(s => ({...s, isDap: true}));
            const allServers = workspace ? [...(workspace.lspServers || []), ...dapServersWithFlag] : [];
            const hasContributions = (server.contributions && Object.keys(server.contributions).length > 0) ||
                                    buildWorkspaceContributedByMap(allServers)[server.id]?.length > 0;

            // Extensions and DAP servers don't have traces tab - default to overview
            if ((server.isExtension || server.isDap) && state.currentConsoleTab === 'traces') {
                state.currentConsoleTab = 'overview';
            }

            // If current tab is contributions but there are none, switch to appropriate default
            if (!hasContributions && state.currentConsoleTab === 'contributions') {
                state.currentConsoleTab = (server.isExtension || server.isDap) ? 'overview' : 'traces';
            }

            // Build icon for console title
            const isExternal = server.externalInstance != null &&
                              (server.status === 'CONNECTED_TO_IDE' || server.status === 'CONNECTING_TO_IDE');
            const titleIcon = isExternal ? '🔗' : (server.isExtension ? '🧩' : (server.isDap ? '🐛' : (server.isBsp ? '🔨' : '🚀')));


            // Setup console UI with tabs
            document.getElementById('console-area').innerHTML = `
                <div class="console-wrapper">
                    <div class="console-header">
                        <div class="console-title">
                            <span class="server-source-icon">${titleIcon}</span>
                            ${server.name}
                            <span class="status-indicator" id="sse-status"></span>
                        </div>
                        <div class="console-tabs">
                            ${!server.isExtension && !server.isDap ? `<button class="tab-button ${state.currentConsoleTab === 'traces' ? 'active' : ''}" data-action="switchConsoleTab" data-tab="traces">Traces</button>` : ''}
                            <button class="tab-button ${state.currentConsoleTab === 'overview' ? 'active' : ''}" data-action="switchConsoleTab" data-tab="overview">Overview</button>
                            ${hasContributions ? `<button class="tab-button ${state.currentConsoleTab === 'contributions' ? 'active' : ''}" data-action="switchConsoleTab" data-tab="contributions">Contributions</button>` : ''}
                            <button class="tab-button ${state.currentConsoleTab === 'settings' ? 'active' : ''}" data-action="switchConsoleTab" data-tab="settings">Settings</button>
                            <button class="tab-button ${state.currentConsoleTab === 'install' ? 'active' : ''}" data-action="switchConsoleTab" data-tab="install">Install</button>
                        </div>
                        <div class="console-controls">
                            ${renderTraceControls('trace', currentTraceLevel, 'changeTraceLevel', {
                                foldAction: 'toggleAllTracesWorkspace',
                                clearAction: 'clearConsole',
                                wrapperId: 'traces-controls',
                                wrapperDisplay: state.currentConsoleTab === 'traces' ? 'contents' : 'none'
                            })}
                        </div>
                    </div>
                    <div class="tab-content">
                        <div id="traces-tab" class="tab-panel ${state.currentConsoleTab === 'traces' ? 'active' : ''}">
                            <div class="console" id="console-output" tabindex="0"></div>
                        </div>
                        <div id="overview-tab" class="tab-panel ${state.currentConsoleTab === 'overview' ? 'active' : ''}">
                            <div class="details-panel" id="overview-content">
                                <p>Loading...</p>
                            </div>
                        </div>
                        ${hasContributions ? `
                        <div id="contributions-tab" class="tab-panel ${state.currentConsoleTab === 'contributions' ? 'active' : ''}">
                            <div id="workspace-diagram-container" class="w-100 bg-card diagram-container"></div>
                            <div class="diagram-resizer"></div>
                            <div class="details-panel text-primary flex-1 min-h-0 detail-content" id="contributions-content">
                                <p>Loading...</p>
                            </div>
                        </div>
                        ` : ''}
                        <div id="settings-tab" class="tab-panel ${state.currentConsoleTab === 'settings' ? 'active' : ''}">
                            <div class="details-panel" id="settings-content">
                                <p>Loading...</p>
                            </div>
                        </div>
                        <div id="install-tab" class="tab-panel ${state.currentConsoleTab === 'install' ? 'active' : ''}">
                            <div class="install-panel">
                                <h3>Global Install</h3>
                                <div class="install-info">
                                    <p><strong>Server:</strong> ${server.name}</p>
                                    <p><strong>ID:</strong> ${server.id}</p>
                                </div>
                                <div class="installer-editor">
                                    <div class="editor-header">
                                        <span>installer.json</span>
                                        <div class="editor-actions">
                                            <button class="editor-btn" data-action="saveInstallerJson" data-server-id="${server.id}" title="Save">💾 Save</button>
                                            <button class="editor-btn" data-action="resetInstallerJson" data-server-id="${server.id}" title="Reset">↻ Reset</button>
                                            <span class="editor-separator"></span>
                                            <button class="editor-btn install-run-btn" data-action="runInstaller" data-server-id="${server.id}" data-force="false" title="Install (check first, skip if already installed)">▶ Install</button>
                                            <button class="editor-btn install-force-btn" data-action="runInstaller" data-server-id="${server.id}" data-force="true" title="Force Install (skip check, always re-install)">⟳ Force Install</button>
                                        </div>
                                    </div>
                                    <textarea id="installer-json-editor" class="json-editor" spellcheck="false"></textarea>
                                </div>
                                <div id="install-output" class="install-output"></div>
                            </div>
                        </div>
                    </div>
                </div>
            `;

            state.currentServerId = server.id;

            // Store servers data for diagram rendering (include both LSP and DAP)
            const currentWorkspace = state.workspaces.find(w => w.rootUri === state.selectedWorkspace);
            if (currentWorkspace) {
                state.currentWorkspaceDiagramServers = allServers;
                state.currentWorkspaceDiagramServerId = server.id;
            }

            // If contributions tab is active, render diagram immediately
            if (state.currentConsoleTab === 'contributions' && currentWorkspace) {
                setTimeout(() => renderWorkspaceDiagram(allServers, server.id), 100);
            }

            currentTraceLevel = server.traceLevel || 'off';
            updateTracesButtonsState(currentTraceLevel);

            // Load traces for specific workspace + server
            try {
                // Traces are populated via WebSocket (history on connect + real-time updates)
                if (!state.tracesByServer[traceKey(state.selectedWorkspace, server.id)]) {
                    state.tracesByServer[traceKey(state.selectedWorkspace, server.id)] = [];
                }
                renderConsole();
            } catch (error) {
                console.error('Failed to load traces:', error);
            }

            // Load server details
            loadServerDetails(server.id);

            // Load installer.json
            if (installerCallbacks.loadInstallerJson) {
                installerCallbacks.loadInstallerJson(server.id);
            }

        }


        async function loadServerDetails(serverId) {
            console.log('loadServerDetails called for:', serverId);
            try {
                const detailsContent = document.getElementById('overview-content');
                if (!detailsContent) {
                    console.warn('details-content element not found, skipping load');
                    return;
                }
                console.log('detailsContent found, fetching details...');

                const workspace = state.workspaces.find(w => w.rootUri === state.selectedWorkspace);

                // Check if this is a BSP server
                const bspServer = Object.values(state.bspConfigs || {})?.find(s => s.id === serverId);

                if (bspServer) {
                    detailsContent.innerHTML = `
                        <h3>BSP Server Configuration</h3>
                        ${renderServerDetailsHTML({...bspServer, isBsp: true})}
                    `;

                    const settingsContent = document.getElementById('settings-content');
                    if (settingsContent && workspace) {
                        try {
                            const settingsResponse = await fetch(`/api/admin/workspaces/${encodeURIComponent(workspace.rootUri)}/bsp-servers/${encodeURIComponent(serverId)}/settings`);
                            const settings = settingsResponse.ok ? await settingsResponse.json() : [];
                            settingsContent.innerHTML = renderServerSettingsTab({ id: serverId, settings, isBsp: true }, workspace, []);
                            const bspTraceSetting = settings.find(s => s.key === 'trace');
                            if (bspTraceSetting) {
                                const resolvedBspLevel = bspTraceSetting.currentValue || 'off';
                                if (resolvedBspLevel !== currentTraceLevel) {
                                    currentTraceLevel = resolvedBspLevel;
                                    if (state.selectedServer) state.selectedServer.traceLevel = resolvedBspLevel;
                                    updateTraceControls('trace', currentTraceLevel);
                                    renderConsole();
                                }
                            }
                        } catch (e) {
                            settingsContent.innerHTML = '<p class="text-secondary p-lg">Failed to load settings.</p>';
                        }
                    }
                }

                // Check if this is a DAP server
                const dapServer = !bspServer ? Object.values(state.dapConfigs || {})?.find(s => s.id === serverId) : null;

                if (dapServer) {
                    // DAP server - display its details directly
                    const dapServersWithFlag = (Object.values(state.dapConfigs || {}) || []).map(s => ({...s, isDap: true}));
                    const allServers = [...(workspace.lspServers || []), ...dapServersWithFlag];

                    detailsContent.innerHTML = `
                        <h3>DAP Server Configuration</h3>
                        ${renderServerDetailsHTML({...dapServer, isDap: true})}
                    `;

                    // Update contributions tab
                    const contributionsContent = document.getElementById('contributions-content');
                    if (contributionsContent) {
                        const contributionsHTML = formatContributionsSection({...dapServer, isDap: true}, allServers);
                        contributionsContent.innerHTML = contributionsHTML || '<p class="detail-value">No contributions</p>';
                    }

                    // Update settings tab (trace level)
                    const settingsContent = document.getElementById('settings-content');
                    if (settingsContent && workspace) {
                        try {
                            const settingsResponse = await fetch(`/api/admin/workspaces/${encodeURIComponent(workspace.rootUri)}/dap-servers/${encodeURIComponent(serverId)}/settings`);
                            const settings = settingsResponse.ok ? await settingsResponse.json() : [];
                            settingsContent.innerHTML = renderServerSettingsTab({ id: serverId, settings, isDap: true }, workspace, []);
                            // Sync trace level from workspace-resolved settings (skip re-render if unchanged)
                            const dapTraceSetting = settings.find(s => s.key === 'trace');
                            if (dapTraceSetting) {
                                const resolvedDapLevel = dapTraceSetting.currentValue || 'off';
                                if (resolvedDapLevel !== currentTraceLevel) {
                                    currentTraceLevel = resolvedDapLevel;
                                    if (state.selectedServer) state.selectedServer.traceLevel = resolvedDapLevel;
                                    updateTraceControls('trace', currentTraceLevel);
                                    renderConsole();
                                }
                            }
                        } catch (e) {
                            settingsContent.innerHTML = '<p class="text-secondary p-lg">Failed to load settings.</p>';
                        }
                    }
                } else if (!bspServer) {
                    // LSP server - fetch config, workspace-resolved settings, and IDE settings
                    const [configResponse, settingsResponse, ideSettingsResponse] = await Promise.all([
                        fetch(`/api/admin/lsp/configs/${serverId}`),
                        workspace ? fetch(`/api/admin/workspaces/${encodeURIComponent(workspace.rootUri)}/lsp-servers/${encodeURIComponent(serverId)}/settings`) : null,
                        workspace ? fetch(`/api/admin/workspaces/${encodeURIComponent(workspace.rootUri)}/lsp-servers/${encodeURIComponent(serverId)}/ide-settings`) : null
                    ]);
                    if (!configResponse.ok) {
                        throw new Error('Failed to load server details');
                    }

                    const details = await configResponse.json();
                    if (settingsResponse && settingsResponse.ok) {
                        details.settings = await settingsResponse.json();
                    }
                    const ideSettings = (ideSettingsResponse && ideSettingsResponse.ok)
                        ? await ideSettingsResponse.json()
                        : [];

                    // Sync trace level from workspace-resolved settings (skip re-render if unchanged)
                    const traceSetting = details.settings?.find(s => s.key === 'trace');
                    if (traceSetting) {
                        const resolvedLevel = traceSetting.currentValue || 'off';
                        if (resolvedLevel !== currentTraceLevel) {
                            currentTraceLevel = resolvedLevel;
                            if (state.selectedServer) state.selectedServer.traceLevel = resolvedLevel;
                            updateTraceControls('trace', currentTraceLevel);
                            renderConsole();
                        }
                    }

                    // Get all servers for contributedBy calculation
                    const allServers = workspace?.lspServers || [];

                    // Use shared rendering function
                    detailsContent.innerHTML = `
                        <h3>Server Configuration</h3>
                        ${renderServerDetailsHTML(details)}
                    `;

                    // Update contributions tab
                    const contributionsContent = document.getElementById('contributions-content');
                    if (contributionsContent) {
                        const contributionsHTML = formatContributionsSection(details, allServers);
                        contributionsContent.innerHTML = contributionsHTML || '<p class="detail-value">No contributions</p>';
                    }

                    // Update settings tab
                    const settingsContent = document.getElementById('settings-content');
                    if (settingsContent) {
                        settingsContent.innerHTML = renderServerSettingsTab(details, workspace, ideSettings);
                    }
                }
            } catch (error) {
                console.error('Failed to load server details:', error);
                const detailsContent = document.getElementById('overview-content');
                if (detailsContent) {
                    detailsContent.innerHTML = `<p class="error">Failed to load server details: ${error.message}</p>`;
                }
            }
        }

        /**
         * Render complete server details HTML (shared between Servers and Workspaces tabs).
         * Does NOT include contributions (now in separate tab).
         * @param {Object} server - The server config/details object
         * @returns {string} HTML string
         */
        function renderServerDetailsHTML(server) {
            // Format command (can be string or object)
            let commandStr = '';
            if (server.command) {
                if (typeof server.command === 'string') {
                    commandStr = server.command;
                } else if (typeof server.command === 'object') {
                    commandStr = JSON.stringify(server.command, null, 2);
                }
            }

            return `
                <div class="details-section">
                    <h4>General Information</h4>
                    <div class="detail-item">
                        <span class="detail-label">ID:</span>
                        <span class="detail-value">${server.id}</span>
                    </div>
                    <div class="detail-item">
                        <span class="detail-label">Name:</span>
                        <span class="detail-value">${server.name || 'N/A'}</span>
                    </div>
                    ${server.description ? `
                    <div class="detail-item">
                        <span class="detail-label">Description:</span>
                        <span class="detail-value">${server.description}</span>
                    </div>
                    ` : ''}
                </div>

                <div class="details-section">
                    <h4>Document Selector</h4>
                    ${renderDocumentSelector(server.documentSelector)}
                </div>

                ${commandStr ? `
                <div class="details-section">
                    <h4>Command</h4>
                    <pre class="command-preview">${commandStr}</pre>
                </div>
                ` : ''}

                ${server.initializationOptions && Object.keys(server.initializationOptions).length > 0 ? `
                <div class="details-section">
                    <h4>Initialization Options</h4>
                    <pre class="detail-value">${JSON.stringify(server.initializationOptions, null, 2)}</pre>
                </div>
                ` : ''}
            `;
        }

        function renderServerSettingsTab(server, workspace, ideSettings) {
            const uri = workspace?.rootUri || state.selectedWorkspace || '';
            const serverType = server.isBsp ? 'bsp' : (server.isDap ? 'dap' : 'lsp');
            const hasWorkspaceSettings = server.settings && server.settings.length > 0;
            const hasIdeSettings = ideSettings && ideSettings.length > 0;

            if (!hasWorkspaceSettings && !hasIdeSettings) {
                return '<p class="text-secondary p-lg">No settings declared for this server.</p>';
            }

            let workspacePanel = '';
            if (hasWorkspaceSettings) {
                const wsItems = server.settings.map(setting =>
                    renderServerSetting(setting, 'updateWorkspaceServerSetting', 'resetWorkspaceServerSetting',
                        { uri, 'server-id': server.id, 'server-type': serverType })
                );
                workspacePanel = renderSettingsPanel({
                    title: 'Workspace',
                    itemsHtml: wsItems
                });
            }

            let idePanel = '';
            if (hasIdeSettings) {
                const ideItems = ideSettings.map(s => renderIdeSettingItem(s));
                idePanel = renderSettingsPanel({
                    title: 'IDE',
                    itemsHtml: ideItems
                });
            }

            return `
                <div class="p-sm">
                    ${workspacePanel}
                    ${idePanel}
                </div>
            `;
        }

        function renderIdeSettingItem(setting) {
            return `
                <div class="setting-item">
                    <div class="setting-item-info">
                        <div class="setting-item-label">${setting.key}</div>
                    </div>
                    <div class="setting-item-control">
                        <span class="text-secondary">${setting.value != null ? setting.value : '<em>null</em>'}</span>
                    </div>
                </div>
            `;
        }

        function switchConsoleTab(tabName) {
            state.currentConsoleTab = tabName; // Save current tab

            // Update tab buttons
            document.querySelectorAll('.tab-button').forEach(btn => {
                btn.classList.remove('active');
            });
            // Find the clicked button by data-tab attribute
            const clickedBtn = document.querySelector(`.tab-button[data-tab="${tabName}"]`);
            if (clickedBtn) clickedBtn.classList.add('active');

            // Update tab panels
            document.querySelectorAll('.tab-panel').forEach(panel => {
                panel.classList.remove('active');
            });
            document.getElementById(tabName + '-tab').classList.add('active');

            // Show/hide controls
            const tracesControls = document.getElementById('traces-controls');
            if (tracesControls) {
                tracesControls.style.display = tabName === 'traces' ? 'contents' : 'none';
            }

            // Show/hide search box (only visible in traces tab)
            updateSearchBoxVisibility(tabName === 'traces');

            // Render diagram when switching to contributions tab
            if (tabName === 'contributions' && state.currentWorkspaceDiagramServers) {
                renderWorkspaceDiagram(state.currentWorkspaceDiagramServers, state.currentWorkspaceDiagramServerId);
            }
        }

        function switchServerTab(tabName) {

            // Update tab buttons
            document.querySelectorAll('.tab-button').forEach(btn => {
                btn.classList.remove('active');
            });
            // Find the clicked button
            const clickedBtn = document.querySelector(`.tab-button[data-tab="${tabName}"]`);
            if (clickedBtn) clickedBtn.classList.add('active');

            // Update tab panels
            document.querySelectorAll('.tab-panel').forEach(panel => {
                panel.classList.remove('active');
            });
            document.getElementById('server-' + tabName + '-tab').classList.add('active');

            // Render diagram when switching to contributions tab
            if (tabName === 'contributions' && state.currentDiagramServers) {
                renderServerDiagram(state.currentDiagramServers, state.currentDiagramServerId);
            }
        }


        function getServerTraces() {
            const globalTraces = state.tracesByServer[traceKey(null, state.currentServerId)] || [];
            const workspaceTraces = state.tracesByServer[traceKey(state.selectedWorkspace, state.currentServerId)] || [];
            return globalTraces.length > 0 ? [...globalTraces, ...workspaceTraces] : workspaceTraces;
        }

        export function renderConsole() {
            renderTracesInContainer('console-output', getServerTraces(), currentTraceLevel, getCurrentSearchQuery());
            initTraceContainer('console-output');
        }

        let mouseDownTime = 0;
        let mouseDownIndex = -1;

        function onHeaderMouseDown(index) {
            mouseDownTime = Date.now();
            mouseDownIndex = index;
        }

        function onHeaderMouseUp(index) {
            // Si c'est un click rapide (< 200ms) et au même endroit, toggle
            const timeDiff = Date.now() - mouseDownTime;
            if (timeDiff < 200 && mouseDownIndex === index) {
                // Vérifier si du texte a été sélectionné
                const selection = window.getSelection();
                if (!selection || selection.toString().length === 0) {
                    toggleTrace(index);
                }
            }
            mouseDownIndex = -1;
        }

        let allFolded = true; // Par défaut: tout plié

        // Generic function for toggling all traces (LSP or MCP)
        function toggleAllTracesGeneric(outputId, bodyClass, toggleClass, buttonId, foldedStateRef) {
            const consoleOutput = document.getElementById(outputId);
            if (!consoleOutput) return;

            const bodies = consoleOutput.querySelectorAll(`.${bodyClass}`);
            const toggles = consoleOutput.querySelectorAll(`.${toggleClass}`);
            const foldButton = document.getElementById(buttonId);

            if (foldedStateRef.value) {
                // Unfold all
                bodies.forEach(body => {
                    body.classList.remove('collapsed');
                    body.classList.add('expanded');
                });
                toggles.forEach(toggle => {
                    toggle.textContent = '▼';
                });
                foldButton.textContent = 'Fold All';
                foldedStateRef.value = false;
            } else {
                // Fold all
                bodies.forEach(body => {
                    body.classList.remove('expanded');
                    body.classList.add('collapsed');
                });
                toggles.forEach(toggle => {
                    toggle.textContent = '▶';
                });
                foldButton.textContent = 'Unfold All';
                foldedStateRef.value = true;
            }
        }

        function toggleAllTracesWorkspace() {
            toggleAllTracesGeneric('console-output', 'trace-body', 'trace-toggle', 'trace-fold-button', {
                get value() { return allFolded; },
                set value(v) { allFolded = v; }
            });
        }

        function searchTraces(query) {
            const consoleOutput = document.getElementById('console-output');
            if (!consoleOutput) return;

            const traceElements = consoleOutput.querySelectorAll('.trace-entry');

            if (!query || query.trim() === '') {
                // Reset: show all traces, remove highlights
                traceElements.forEach(el => {
                    el.style.display = '';
                    el.querySelectorAll('.search-highlight').forEach(mark => {
                        const parent = mark.parentNode;
                        parent.replaceChild(document.createTextNode(mark.textContent), mark);
                        parent.normalize();
                    });
                });
                return;
            }

            const lowerQuery = query.toLowerCase();
            let matchCount = 0;

            traceElements.forEach(el => {
                const text = el.textContent.toLowerCase();
                const matches = text.includes(lowerQuery);

                if (matches) {
                    el.style.display = '';
                    matchCount++;

                    // Open details if match is in JSON content
                    const details = el.querySelector('details');
                    if (details) {
                        const detailsText = details.textContent.toLowerCase();
                        if (detailsText.includes(lowerQuery)) {
                            details.open = true;
                        }
                    }

                    // TODO: Highlight matching text (optional enhancement)
                } else {
                    el.style.display = 'none';
                }
            });

        }

        async function clearConsole() {
            try {
                await fetch('/api/admin/traces/lsp', { method: 'DELETE' });

                // Clear traces for current workspace + server only
                if (state.currentServerId) {
                    state.tracesByServer[traceKey(state.selectedWorkspace, state.currentServerId)] = [];
                }

                renderConsole();
            } catch (error) {
                console.error('Failed to clear traces:', error);
            }
        }

        function clearServerActions(serverId) {
            const serverElement = document.querySelector(`.server-item[data-server-id="${serverId}"]`);
            if (serverElement) {
                const actionsContainer = serverElement.querySelector('.server-actions');
                if (actionsContainer) actionsContainer.innerHTML = '';
            }
        }

        async function stopServerAction(serverId) {
            if (!state.selectedWorkspace) return;

            const server = state.workspaces.find(w => w.rootUri === state.selectedWorkspace)?.lspServers.find(s => s.id === serverId);
            if (!server) return;

            const confirmed = await confirmAction(
                'Stop LSP Server',
                `Stop "${server.name}"?\n\nThe server process will be terminated.`,
                'Stop',
                true
            );
            if (!confirmed) return;

            clearServerActions(serverId);
            try {
                const response = await fetch(
                    `/api/admin/lsp/servers/${encodeURIComponent(state.selectedWorkspace)}/${serverId}/stop`,
                    { method: 'POST' }
                );

                if (!response.ok) {
                    const error = await response.text();
                    showAlert('Error', 'Failed to stop server: ' + error);
                }
            } catch (error) {
                console.error('Failed to stop server:', error);
                showAlert('Error', 'Failed to stop server: ' + error.message);
            }
        }

        async function startManagedServerAction(serverId) {
            if (!state.selectedWorkspace) return;

            const server = state.workspaces.find(w => w.rootUri === state.selectedWorkspace)?.lspServers.find(s => s.id === serverId);
            if (!server) return;

            clearServerActions(serverId);
            try {
                const response = await fetch(
                    `/api/admin/lsp/servers/${encodeURIComponent(state.selectedWorkspace)}/${serverId}/start-managed`,
                    { method: 'POST' }
                );

                if (!response.ok) {
                    const error = await response.text();
                    showAlert('Error', 'Failed to start managed server: ' + error);
                }
            } catch (error) {
                console.error('Failed to start managed server:', error);
                showAlert('Error', 'Failed to start managed server: ' + error.message);
            }
        }

        async function restartServerAction(serverId) {
            if (!state.selectedWorkspace) return;

            const server = state.workspaces.find(w => w.rootUri === state.selectedWorkspace)?.lspServers.find(s => s.id === serverId);
            if (!server) return;

            const confirmed = await confirmAction(
                'Restart LSP Server',
                `Restart "${server.name}"?\n\nThe server will be stopped and restarted.`,
                'Restart',
                false
            );
            if (!confirmed) return;

            clearServerActions(serverId);
            try {
                const response = await fetch(
                    `/api/admin/lsp/servers/${encodeURIComponent(state.selectedWorkspace)}/${serverId}/restart`,
                    { method: 'POST' }
                );

                if (!response.ok) {
                    const error = await response.text();
                    showAlert('Error', 'Failed to restart server: ' + error);
                }
            } catch (error) {
                console.error('Failed to restart server:', error);
                showAlert('Error', 'Failed to restart server: ' + error.message);
            }
        }

        async function disconnectFromIdeAction(serverId) {
            if (!state.selectedWorkspace) return;

            const server = state.workspaces.find(w => w.rootUri === state.selectedWorkspace)?.lspServers.find(s => s.id === serverId);
            if (!server) return;

            const confirmed = await confirmAction(
                'Disconnect from IDE',
                `Disconnect "${server.name}" from IDE?\n\nThe connection to the IDE instance will be closed.`,
                'Disconnect',
                true
            );
            if (!confirmed) return;

            clearServerActions(serverId);
            try {
                const response = await fetch(
                    `/api/admin/lsp/servers/${encodeURIComponent(state.selectedWorkspace)}/${serverId}/disconnect`,
                    { method: 'POST' }
                );

                if (!response.ok) {
                    const error = await response.text();
                    showAlert('Error', 'Failed to disconnect: ' + error);
                }
            } catch (error) {
                console.error('Failed to disconnect:', error);
                showAlert('Error', 'Failed to disconnect: ' + error.message);
            }
        }

        async function connectToIdeAction(serverId) {
            if (!state.selectedWorkspace) return;

            const server = state.workspaces.find(w => w.rootUri === state.selectedWorkspace)?.lspServers.find(s => s.id === serverId);
            if (!server) return;

            clearServerActions(serverId);
            try {
                const response = await fetch(
                    `/api/admin/lsp/servers/${encodeURIComponent(state.selectedWorkspace)}/${serverId}/connect-ide`,
                    { method: 'POST' }
                );

                if (!response.ok) {
                    const error = await response.text();
                    showAlert('Error', 'Failed to connect to IDE: ' + error);
                }
            } catch (error) {
                console.error('Failed to connect to IDE:', error);
                showAlert('Error', 'Failed to connect to IDE: ' + error.message);
            }
        }

        // Auto-refresh is no longer needed - SSE handles real-time updates
        // Keep the function for compatibility but make it a no-op
        function autoRefresh() {
            // SSE streams handle all updates in real-time
            // No polling needed anymore
        }

        // Search functionality - delegate to TraceRenderer
        // Initialize search listeners with render callback
        initSearchListeners((query) => {
            // Re-render with highlighting based on active console
            // Check DAP first (by currentDapSessionId presence, not tab name)
            if (state.currentDapSessionId) {
                if (renderDapTracesForSessionFn) {
                    renderDapTracesForSessionFn(state.currentDapSessionId);
                }
            } else if (state.currentTab === 'mcp-traces') {
                if (renderMcpConsoleWithHighlightsFn) {
                    renderMcpConsoleWithHighlightsFn();
                }
            } else {
                // LSP traces
                renderConsoleWithHighlights();
            }
        });

        function renderConsoleWithHighlights() {
            renderTracesInContainer('console-output', getServerTraces(), currentTraceLevel, getCurrentSearchQuery());
        }

        async function toggleFileWatcherFromListAction(uri) {
            const workspace = state.workspaces?.find(w => w.rootUri === uri);
            const newEnabled = !(workspace?.fileWatcherEnabled || false);
            try {
                const response = await fetch(`/api/admin/workspaces/${encodeURIComponent(uri)}/file-watcher`, {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ enabled: newEnabled, scope: 'workspace' })
                });
                if (response.ok) {
                    const result = await response.json();
                    console.log('File watcher toggled:', result);
                    if (workspace) {
                        workspace.fileWatcherEnabled = result.fileWatcherEnabled;
                        workspace.fileWatcherEnabledSource = result.fileWatcherEnabledSource;
                        workspace.fileWatcherRunning = result.fileWatcherRunning;
                    }
                    renderWorkspaces();
                    if (state.currentWorkspaceTab === 'settings') {
                        renderServers(workspace?.lspServers || [], [], workspace);
                    }
                }
            } catch (error) {
                console.error('Failed to toggle file watcher:', error);
            }
        }

        async function resetFileWatcherSettingAction(uri) {
            const result = await resetWorkspaceSetting(uri, 'fileWatchers.enabled');
            if (result) {
                const workspace = state.workspaces?.find(w => w.rootUri === uri);
                if (workspace) {
                    workspace.fileWatcherEnabled = result.value;
                    workspace.fileWatcherEnabledSource = result.source;
                }
                renderWorkspaces();
                if (state.currentWorkspaceTab === 'settings') {
                    renderServers(workspace?.lspServers || [], [], workspace);
                }
            }
        }

        // Maps taskId (from backend) -> { taskType, uri }
        const runningWorkspaceTasks = new Map();

        function setWorkspaceTaskButtons(taskType, uri, disabled) {
            const action = taskType === 'build' ? 'buildWorkspace' : 'refreshWorkspace';
            const actions = [`${action}FromList`, `${action}FromSettings`];
            actions.forEach(act => {
                document.querySelectorAll(`[data-action="${act}"]`).forEach(btn => {
                    if (btn.dataset.uri === uri) {
                        btn.disabled = disabled;
                        if (disabled) btn.classList.add('btn-loading');
                        else btn.classList.remove('btn-loading');
                    }
                });
            });
        }

        function isWorkspaceTaskRunning(taskType, uri) {
            for (const info of runningWorkspaceTasks.values()) {
                if (info.taskType === taskType && info.uri === uri) return true;
            }
            return false;
        }

        async function workspaceTaskAction(taskType, uri) {
            if (isWorkspaceTaskRunning(taskType, uri)) return;

            const taskId = `${taskType}-${crypto.randomUUID().substring(0, 8)}`;
            runningWorkspaceTasks.set(taskId, { taskType, uri });
            setWorkspaceTaskButtons(taskType, uri, true);

            try {
                const endpoint = taskType === 'build' ? 'build' : 'refresh';
                await fetch(`/api/admin/workspaces/${encodeURIComponent(uri)}/${endpoint}`, {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ taskId })
                });
            } catch (error) {
                console.error(`Failed to ${taskType} workspace:`, error);
                runningWorkspaceTasks.delete(taskId);
                setWorkspaceTaskButtons(taskType, uri, false);
            }
        }

        export function onWorkspaceTaskStarted(taskId, workspaceUri) {
            if (runningWorkspaceTasks.has(taskId)) return;
            const taskType = taskId.startsWith('build-') ? 'build' : taskId.startsWith('refresh-') ? 'refresh' : null;
            if (!taskType || !workspaceUri) return;
            runningWorkspaceTasks.set(taskId, { taskType, uri: workspaceUri });
            setWorkspaceTaskButtons(taskType, workspaceUri, true);
        }

        export function onWorkspaceTaskCompleted(taskId) {
            const info = runningWorkspaceTasks.get(taskId);
            if (info) {
                runningWorkspaceTasks.delete(taskId);
                setWorkspaceTaskButtons(info.taskType, info.uri, false);
            }
        }

        async function updateWorkspaceServerSettingAction(uri, serverId, settingKey, value, serverType) {
            if (settingKey === 'trace') {
                const type = serverType || 'lsp';
                await fetch(`/api/admin/workspaces/${encodeURIComponent(uri)}/traces/${type}/${encodeURIComponent(serverId)}`, {
                    method: 'PUT',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ traceLevel: value })
                });
                currentTraceLevel = value;
                if (state.selectedServer) state.selectedServer.traceLevel = value;
                updateTraceControls('trace', value);
                renderConsole();
                await reloadServerSettingsTab(serverId);
                return;
            }
            const persistKey = `lsp.${serverId}.settings.${settingKey}`;
            await setWorkspaceSetting(uri, persistKey, value);
            await reloadServerSettingsTab(serverId);
        }

        async function resetWorkspaceServerSettingAction(uri, serverId, key, serverType) {
            if (key === 'trace') {
                const type = serverType || 'lsp';
                await fetch(`/api/admin/workspaces/${encodeURIComponent(uri)}/traces/${type}/${encodeURIComponent(serverId)}`, {
                    method: 'DELETE'
                });
                await reloadServerSettingsTab(serverId);
                return;
            }
            const persistKey = `lsp.${serverId}.settings.${key}`;
            await resetWorkspaceSetting(uri, persistKey);
            await reloadServerSettingsTab(serverId);
        }

        async function reloadServerSettingsTab(serverId) {
            const workspace = state.workspaces?.find(w => w.rootUri === state.selectedWorkspace);
            if (!workspace) return;
            const isDap = state.selectedServer?.isDap;
            const isBsp = state.selectedServer?.isBsp;
            try {
                if (isBsp) {
                    const settingsResponse = await fetch(`/api/admin/workspaces/${encodeURIComponent(workspace.rootUri)}/bsp-servers/${encodeURIComponent(serverId)}/settings`);
                    const settings = settingsResponse.ok ? await settingsResponse.json() : [];
                    const settingsContent = document.getElementById('settings-content');
                    if (settingsContent) {
                        settingsContent.innerHTML = renderServerSettingsTab({ id: serverId, settings, isBsp: true }, workspace, []);
                    }
                } else if (isDap) {
                    const settingsResponse = await fetch(`/api/admin/workspaces/${encodeURIComponent(workspace.rootUri)}/dap-servers/${encodeURIComponent(serverId)}/settings`);
                    const settings = settingsResponse.ok ? await settingsResponse.json() : [];
                    const settingsContent = document.getElementById('settings-content');
                    if (settingsContent) {
                        settingsContent.innerHTML = renderServerSettingsTab({ id: serverId, settings, isDap: true }, workspace, []);
                    }
                } else {
                    const [configResponse, settingsResponse, ideSettingsResponse] = await Promise.all([
                        fetch(`/api/admin/lsp/configs/${serverId}`),
                        fetch(`/api/admin/workspaces/${encodeURIComponent(workspace.rootUri)}/lsp-servers/${encodeURIComponent(serverId)}/settings`),
                        fetch(`/api/admin/workspaces/${encodeURIComponent(workspace.rootUri)}/lsp-servers/${encodeURIComponent(serverId)}/ide-settings`)
                    ]);
                    const config = configResponse.ok ? await configResponse.json() : {};
                    const settings = settingsResponse.ok ? await settingsResponse.json() : [];
                    const ideSettings = ideSettingsResponse.ok ? await ideSettingsResponse.json() : [];
                    const server = { id: serverId, settings, name: config?.name || serverId };
                    const settingsContent = document.getElementById('settings-content');
                    if (settingsContent) {
                        settingsContent.innerHTML = renderServerSettingsTab(server, workspace, ideSettings);
                    }
                }
            } catch (error) {
                console.error('Failed to reload server settings:', error);
            }
        }

        async function createNewTestSession(serverId) {
            createNewTestSessionImpl(serverId);
        }

        // Register all event delegation actions
        registerActions('click', {
            selectWorkspace: (el) => selectWorkspace(el.dataset.uri),
            closeWorkspace: (el) => closeWorkspace(el.dataset.uri),
            selectServerItem: (el) => {
                const serverId = el.dataset.serverId;
                const workspace = state.workspaces.find(w => w.rootUri === state.selectedWorkspace);
                if (workspace && workspace.lspServers) {
                    const server = workspace.lspServers.find(s => s.id === serverId);
                    if (server) selectServer(server, true);
                }
            },
            selectDapServerItem: (el) => {
                const serverId = el.dataset.serverId;
                const dapServer = Object.values(state.dapConfigs || {}).find(s => s.id === serverId);
                if (dapServer) selectDapServer(dapServer);
            },
            selectBspServerItem: (el) => {
                const serverId = el.dataset.serverId;
                const workspace = state.workspaces.find(w => w.rootUri === state.selectedWorkspace);
                const bspServer = workspace?.bspServers?.find(s => s.id === serverId)
                    || Object.values(state.bspConfigs || {}).find(s => s.id === serverId);
                if (bspServer) selectBspServer(bspServer);
            },
            startBspServerAction: (el) => startBspServerAction(el.dataset.serverId),
            restartBspServerAction: (el) => restartBspServerAction(el.dataset.serverId),
            stopBspServerAction: (el) => stopBspServerAction(el.dataset.serverId),
            switchWorkspaceTab: (el) => switchWorkspaceTab(el.dataset.tab),
            switchConsoleTab: (el) => switchConsoleTab(el.dataset.tab),
            stopServerAction: (el) => stopServerAction(el.dataset.serverId),
            restartServerAction: (el) => restartServerAction(el.dataset.serverId),
            startManagedServerAction: (el) => startManagedServerAction(el.dataset.serverId),
            disconnectFromIdeAction: (el) => disconnectFromIdeAction(el.dataset.serverId),
            connectToIdeAction: (el) => connectToIdeAction(el.dataset.serverId),
            createNewTestSession: (el) => createNewTestSession(el.dataset.serverId),
            refreshWorkspaceFromList: (el) => workspaceTaskAction('refresh', el.dataset.uri),
            buildWorkspaceFromList: (el) => workspaceTaskAction('build', el.dataset.uri),
            openWorkspaceSettings: (el) => {
                selectWorkspace(el.dataset.uri);
                switchWorkspaceTab('settings');
            },
            buildWorkspaceFromSettings: (el) => workspaceTaskAction('build', el.dataset.uri),
            refreshWorkspaceFromSettings: (el) => workspaceTaskAction('refresh', el.dataset.uri),
            resetFileWatcherSetting: (el) => resetFileWatcherSettingAction(el.dataset.uri),
            resetWorkspaceServerSetting: (el) => resetWorkspaceServerSettingAction(el.dataset.uri, el.dataset.serverId, el.dataset.key, el.dataset.serverType),
            toggleAllTracesWorkspace: () => toggleAllTracesWorkspace(),
            clearConsole: () => clearConsole(),
            saveInstallerJson: (el) => {
                if (installerCallbacks.saveInstallerJson) installerCallbacks.saveInstallerJson(el.dataset.serverId);
            },
            resetInstallerJson: (el) => {
                if (installerCallbacks.resetInstallerJson) installerCallbacks.resetInstallerJson(el.dataset.serverId);
            },
            runInstaller: (el) => {
                if (installerCallbacks.runInstaller) installerCallbacks.runInstaller(el.dataset.serverId, el.dataset.force === 'true', state.selectedWorkspace);
            },
        });

        registerActions('change', {
            toggleShowActiveServers: () => toggleShowActiveServers(),
            toggleFileWatcherFromList: (el) => toggleFileWatcherFromListAction(el.dataset.uri),
            toggleFileWatcherFromSettings: (el) => toggleFileWatcherFromListAction(el.dataset.uri),
            toggleWorkspaceLspServerEnabled: (el) => {
                toggleWorkspaceLspServerEnabled(el.dataset.serverId, el.checked);
            },
            toggleWorkspaceDapServerEnabled: (el) => {
                toggleWorkspaceDapServerEnabled(el.dataset.serverId, el.checked);
            },
            toggleWorkspaceBspServerEnabled: (el) => {
                toggleWorkspaceBspServerEnabled(el.dataset.serverId, el.checked);
            },
            changeTraceLevel: (el) => changeTraceLevel(el.value),
            updateWorkspaceServerSetting: (el) => updateWorkspaceServerSettingAction(
                el.dataset.uri, el.dataset.serverId, el.dataset.settingKey,
                el.type === 'checkbox' ? String(el.checked) : el.value,
                el.dataset.serverType
            ),
        });

        registerActions('input', {
        });
