        // Global variable to store DAP sessions
        let dapSessions = [];

        // Track if user explicitly selected a server (to prevent auto-switching)
        let userExplicitlySelectedServer = false;

        // Filter to show only active (non-STOPPED) servers
        let showOnlyActiveServers = false;

        async function toggleWorkspaceLspServerEnabled(serverId, enabled) {
            const action = enabled ? 'enable' : 'disable';
            try {
                const response = await fetch(`/api/admin/extensions/lsp/servers/${serverId}/${action}`, { method: 'POST' });
                if (response.ok) {
                    if (window.lspConfigs[serverId]) {
                        window.lspConfigs[serverId].enabled = enabled;
                    }
                    const workspace = workspaces.find(w => w.rootUri === selectedWorkspace);
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
                    if (window.dapConfigs && window.dapConfigs[serverId]) {
                        window.dapConfigs[serverId].enabled = enabled;
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
            const workspace = workspaces.find(w => w.rootUri === selectedWorkspace);
            if (!workspace) return;

            if (currentWorkspaceTab === 'debuggers') {
                const sessions = window.dapSessions || [];
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

        function renderWorkspaces() {
            const container = document.getElementById('workspaces-list');

            if (workspaces.length === 0) {
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
                selectedWorkspace = null;
                window.selectedWorkspace = null;
                selectedServer = null;
                document.getElementById('servers-list').innerHTML = '<div class="servers-placeholder">No workspaces selected</div>';
                document.getElementById('console-area').innerHTML = `
                    <div class="placeholder">
                        ← Select a workspace and LSP server to view console
                    </div>
                `;

                return;
            }

            container.innerHTML = workspaces.map(ws => {
                // Extract folder name from URI
                const folderName = ws.rootUri.split('/').filter(p => p).pop() || ws.rootUri;

                return `
                <div class="workspace-item ${ws.rootUri === selectedWorkspace ? 'active' : ''}" onclick="selectWorkspace('${ws.rootUri}')">
                    <div style="display: flex; justify-content: space-between; align-items: center;">
                        <div class="workspace-uri" title="${ws.rootUri}" style="flex: 1;">📂 ${folderName}</div>
                        <button class="close-workspace-btn" onclick="event.stopPropagation(); closeWorkspace('${ws.rootUri}')" title="Close workspace and stop all servers">×</button>
                    </div>
                    ${ws.mcpClients && ws.mcpClients.length > 0 ? `
                        <div class="workspace-section">
                            <div class="workspace-section-title">AI Clients</div>
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
                                        ${shortId ? `<div class="text-dimmed" style="font-size: 0.7rem; margin-left: 1.5rem; margin-top: 0.15rem;">Session: ${shortId}</div>` : ''}
                                    </div>
                                `;
                            }).join('')}
                        </div>
                    ` : ''}
                </div>
                `;
            }).join('');
        }

        // Expose renderWorkspaces globally for use in admin.js
        window.renderWorkspaces = renderWorkspaces;

        function selectWorkspace(uri) {
            // Only reset server selection if we're changing workspace
            if (selectedWorkspace !== uri) {
                selectedServer = null;
                userExplicitlySelectedServer = false; // Reset explicit selection when changing workspace
            }

            selectedWorkspace = uri;
            window.selectedWorkspace = uri;

            // Clear DAP session when selecting a workspace (we're now in LSP context)
            window.currentDapSessionId = null;

            renderWorkspaces();

            // Find workspace in local data (already received via WebSocket)
            const workspace = workspaces.find(w => w.rootUri === uri);
            console.log('Found workspace in selectWorkspace:', workspace);
            if (workspace) {
                // Render the current tab (will lazy load servers/sessions as needed)
                switchWorkspaceTab(currentWorkspaceTab || 'servers');
            } else {
                console.log('Workspace not found, showing placeholder');
                document.getElementById('servers-list').innerHTML = '<div class="servers-placeholder">No LSP servers</div>';
                showPlaceholder();
            }
        }

        function showConfirmModal(title, message, onConfirm) {
            const titleEl = document.getElementById('confirm-modal-title');
            const messageEl = document.getElementById('confirm-modal-message');
            const modalEl = document.getElementById('confirm-modal');

            if (!titleEl || !messageEl || !modalEl) {
                console.error('Confirm modal elements not found!');
                return;
            }

            titleEl.textContent = title;
            messageEl.innerHTML = message;
            modalEl.classList.add('visible');

            const confirmBtn = document.getElementById('modal-confirm-btn');
            confirmBtn.onclick = () => {
                hideConfirmModal();
                onConfirm();
            };
        }

        function hideConfirmModal() {
            document.getElementById('confirm-modal').classList.remove('visible');
        }

        async function closeWorkspace(uri) {
            const folderName = uri.split('/').filter(p => p).pop() || uri;

            showConfirmModal(
                'Close Workspace',
                `
                    <div style="margin-bottom: 1rem; font-size: 1.05rem;"><strong>⚠️ This will shut down all LSP servers for this workspace</strong></div>
                    <div style="margin-bottom: 0.75rem;">Specifically:</div>
                    <ul style="margin: 0 0 1rem 1.5rem; text-align: left; line-height: 1.6;">
                        <li><strong>Stop all running language servers</strong></li>
                        <li>Disconnect any IDE connections</li>
                        <li>Remove the workspace from memory</li>
                        <li>Clear all cached data</li>
                    </ul>
                    <div class="callout-info" style="margin-top: 1rem;">
                        <div><strong>Workspace:</strong> ${folderName}</div>
                        <div class="text-primary" style="margin-top: 0.5rem; font-size: 0.85rem;">💡 The workspace will automatically reappear when an MCP client accesses it again.</div>
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

                        // Reload workspaces list
                        await loadWorkspaces();

                    } catch (error) {
                        console.error('Failed to close workspace:', error);
                        alert('Failed to close workspace: ' + error.message);
                    }
                }
            );
        }

        let lastServersData = null;

        async function loadServers(uri) {
            // Find workspace in local data (already received via WebSocket)
            const workspace = workspaces.find(w => w.rootUri === uri);
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

        function formatStatusLabel(status, externalInstance) {
            const labels = {
                'STARTING': 'Starting',
                'RUNNING': 'Running',
                'STOPPING': 'Stopping',
                'STOPPED': 'Stopped',
                'INSTALLING': 'Installing',
                'INSTALL_FAILED': 'Install Failed',
                'START_FAILED': 'Start Failed',
                'ERROR': 'Error',
                'SWITCHING': 'Switching',
                'CONNECTING_TO_IDE': 'Connecting to IDE',
                'CONNECTED_TO_IDE': 'Connected to IDE',
                'DISCONNECTING': 'Disconnecting'
            };

            // If connected to IDE and we have client info, show it
            if (status === 'CONNECTED_TO_IDE' && externalInstance && externalInstance.clientName) {
                const version = externalInstance.clientVersion ? ` ${externalInstance.clientVersion}` : '';
                return `Connected to ${externalInstance.clientName}${version}`;
            }

            return labels[status] || status;
        }

        function renderStatusBadge(server) {
            const statusClass = server.status === 'RUNNING' && !server.isReady ? 'status-running-not-ready' : 'status-' + server.status.toLowerCase();
            const label = formatStatusLabel(server.status, server.externalInstance);

            // In server list, show simple badge (progress bar is shown in detail panel only)
            return `<span class="status-badge ${statusClass}">${label}</span>`;
        }

        function renderServers(lspServers, dapSessions = [], workspace = null) {
            const container = document.getElementById('servers-list');
            console.log('renderServers called - lspServers:', lspServers?.length, 'dapSessions:', dapSessions?.length, 'workspace:', workspace?.rootUri);
            console.trace('renderServers call stack');

            if (!container) {
                console.error('servers-list element not found!');
                return;
            }

            // Build workspace header (compact, same level as left sidebar)
            const workspaceName = workspace ? (workspace.rootUri.split('/').filter(p => p).pop() || workspace.rootUri) : '';
            const headerHTML = `
                <div style="padding: 0.75rem 1rem calc(0.75rem + 2px); background: var(--bg-card); border-bottom: 1px solid var(--border-primary); display: flex; align-items: center;">
                    <span class="text-primary" style="font-size: 0.7rem; font-weight: 600; text-transform: uppercase; letter-spacing: 0.2px;">📂 ${workspaceName}</span>
                </div>
            `;

            // Build tabs header
            const tabsHTML = `
                <div class="tabs" style="background: var(--bg-panel); border-bottom: 1px solid var(--bg-card);">
                    <div class="tab ${currentWorkspaceTab === 'servers' ? 'active' : ''}" style="flex: 1; text-align: center;" onclick="switchWorkspaceTab('servers')">Servers</div>
                    <div class="tab ${currentWorkspaceTab === 'debuggers' ? 'active' : ''}" style="flex: 1; text-align: center;" onclick="switchWorkspaceTab('debuggers')">Debuggers</div>
                </div>
            `;

            // Filter bar
            const filterHTML = `
                <div style="display: flex; align-items: center; padding: 0.35rem 0.75rem; background: var(--bg-panel); border-bottom: 1px solid var(--border-primary); font-size: 0.8rem;">
                    <label class="text-secondary" style="display: flex; align-items: center; gap: 0.4rem; cursor: pointer; user-select: none;">
                        <input type="checkbox" onchange="toggleShowActiveServers()" ${showOnlyActiveServers ? 'checked' : ''}>
                        Show active only
                    </label>
                </div>
            `;

            // Render content based on active tab
            let contentHTML = '';
            if (currentWorkspaceTab === 'servers') {
                contentHTML = (lspServers && lspServers.length > 0) ? renderLspServers(lspServers) : '<div class="servers-placeholder">No LSP servers</div>';
            } else {
                // Use global DAP configs (like LSP lspConfigs), not per-workspace
                const dapServers = Object.values(window.dapConfigs || {});
                contentHTML = (dapServers.length > 0 || dapSessions.length > 0)
                    ? renderDapServers(dapServers, dapSessions)
                    : '<div class="servers-placeholder">No debug adapters</div>';
            }

            container.innerHTML =
                '<div class="workspace-servers-header">' + headerHTML + tabsHTML + filterHTML + '</div>' +
                '<div class="workspace-servers-content">' + contentHTML + '</div>';

            // Auto-select first DAP server after rendering (not session)
            if (currentWorkspaceTab === 'debuggers' && Object.values(window.dapConfigs || {}) && Object.values(window.dapConfigs || {}).length > 0) {
                const isDapServerSelected = selectedServer && Object.values(window.dapConfigs || {}).find(s => s.id === selectedServer.id);

                if (!isDapServerSelected) {
                    // Select first DAP server
                    const firstDapServer = Object.values(window.dapConfigs || {})[0];
                    selectDapServer(firstDapServer);
                }
            }
        }

        async function switchWorkspaceTab(tab) {
            currentWorkspaceTab = tab;
            window.currentWorkspaceTab = tab; // Update global
            const workspace = workspaces.find(w => w.rootUri === selectedWorkspace);
            if (!workspace) return;

            if (tab === 'servers') {
                // Load LSP servers lazy
                if (!workspace.lspServers) {
                    await loadLspServersForWorkspace(workspace);
                }
                renderServers(workspace.lspServers || [], [], workspace);
            } else if (tab === 'debuggers') {
                // Load DAP sessions lazy
                await loadDapSessionsForWorkspace();
                renderServers([], dapSessions, workspace);
            }
        }

        async function loadLspServersForWorkspace(workspace) {
            try {
                const response = await fetch(`/api/admin/workspaces/${encodeURIComponent(workspace.rootUri)}/lsp-servers`);
                if (response.ok) {
                    const servers = await response.json();
                    // Merge runtime data with configs (for name, description, etc.)
                    workspace.lspServers = servers.map(s => window.mergeServerData(s));
                }
            } catch (error) {
                console.error('Failed to load LSP servers:', error);
                workspace.lspServers = [];
            }
        }

        async function loadDapSessionsForWorkspace() {
            if (!selectedWorkspace) {
                dapSessions = [];
                window.dapSessions = [];
                return;
            }

            try {
                const response = await fetch(`/api/admin/workspaces/${encodeURIComponent(selectedWorkspace)}/dap-sessions`);
                if (response.ok) {
                    dapSessions = await response.json();
                    window.dapSessions = dapSessions; // Sync with global variable for admin-dap.js
                    console.log('Loaded DAP sessions for workspace:', selectedWorkspace, 'count:', dapSessions.length);
                }
            } catch (error) {
                console.error('Failed to load DAP sessions:', error);
                dapSessions = [];
                window.dapSessions = [];
            }
        }

        // Expose for diagram navigation
        window.switchWorkspaceTab = switchWorkspaceTab;

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
            const contributedByMap = buildContributedByMap(servers);

            // Auto-select server logic:
            // ONLY auto-switch if user has NOT explicitly selected a server
            // 1. If there's a server with status != STOPPED, auto-select it (prefer RUNNING over others)
            // 2. If a server is selected and still exists, keep it if it's != STOPPED
            // 3. Otherwise, select first non-STOPPED server
            // 4. Otherwise, select first server

            if (selectedServer) {
                const currentServer = servers.find(s => s.id === selectedServer.id);
                if (currentServer) {
                    // Only auto-switch if user has NOT explicitly selected
                    if (!userExplicitlySelectedServer && currentServer.status === 'STOPPED') {
                        // Prefer RUNNING, then any non-STOPPED status
                        const runningServer = servers.find(s => s.status === 'RUNNING');
                        const activeServer = runningServer || servers.find(s => s.status !== 'STOPPED');
                        if (activeServer) {
                            console.log('Auto-switching from', selectedServer.id, '(STOPPED) to active server:', activeServer.id, '(status:', activeServer.status, ')');
                            selectServer(activeServer, false); // false = not a user action
                        }
                    } else {
                        console.log('Keeping selected server:', selectedServer.id, '(status:', currentServer.status, ', userExplicit:', userExplicitlySelectedServer, ')');
                    }
                } else {
                    console.log('Selected server no longer exists, auto-selecting...');
                    selectedServer = null;
                    userExplicitlySelectedServer = false; // Reset since server disappeared
                }
            }

            // If no server selected, auto-select first non-STOPPED server
            if (!selectedServer && servers.length > 0) {
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
                    const extensionBadge = server.isExtension ? ' <span class="text-secondary" style="font-size: 0.85em;">(Extension)</span>' : '';

                    let actions = '';
                    if (!server.isExtension) {
                        if (isExternal) {
                            actions = `
                                <button class="server-action-btn server-action-disconnect"
                                        onclick='event.stopPropagation(); disconnectFromIdeAction("${server.id}")'
                                        title="Disconnect from IDE">⏏</button>
                            `;
                        } else {
                            if (server.status === 'RUNNING' || server.status === 'STARTING') {
                                actions = `
                                    <button class="server-action-btn" onclick='event.stopPropagation(); restartServerAction("${server.id}")' title="Restart">↻</button>
                                    <button class="server-action-btn" onclick='event.stopPropagation(); stopServerAction("${server.id}")' title="Stop">■</button>
                                `;
                            } else if (server.status === 'STOPPED' || server.status === 'START_FAILED' || server.status === 'INSTALL_FAILED' || server.status === 'ERROR') {
                                actions = `
                                    <button class="server-action-btn" onclick='event.stopPropagation(); startManagedServerAction("${server.id}")' title="Start MCP-managed server">▶</button>
                                    <button class="server-action-btn" onclick='event.stopPropagation(); connectToIdeAction("${server.id}")' title="Try to connect to IDE instance">🔗</button>
                                `;
                            }
                        }
                    }

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
                    const contributedInfo = formatContributeInfo(server, contributedByMap);

                    return `
                        <div class="server-item ${serverClass} ${extensionClass} ${disabledClass} ${selectedServer?.id === server.id ? 'active' : ''}"
                             data-server-id="${server.id}"
                             onclick='selectServer(${JSON.stringify(server)}, true)'
                             ${tooltipText ? `title="${tooltipText.replace(/"/g, '&quot;')}"` : ''}>
                            <div class="server-name" style="display: flex; align-items: center; justify-content: space-between;">
                                <span>
                                    <span class="server-source-icon" title="${sourceLabel}">${sourceIcon}</span>
                                    ${server.name}${extensionBadge}
                                </span>
                                <label class="toggle-switch" onclick="event.stopPropagation()">
                                    <input type="checkbox" ${server.enabled !== false ? 'checked' : ''} onchange="toggleWorkspaceLspServerEnabled('${server.id}', this.checked)">
                                    <span class="toggle-slider"></span>
                                </label>
                            </div>
                            <div class="server-id" ${contributedInfo.tooltip ? `title="${contributedInfo.tooltip}"` : ''}>${server.id}${contributedInfo.text}</div>
                            <div class="server-status-badge-container">
                                ${renderStatusBadge(server)}
                                ${server.statusMessage ? `<span class="server-status-message text-secondary" style="font-size: 0.85rem; margin-left: 0.5rem;">${escapeHtml(server.statusMessage)}</span>` : ''}
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
                        <button class="server-action-btn" onclick='event.stopPropagation(); createNewTestSession("${server.id}")' title="New Test Launch">+</button>
                    `;

                    const disabledClass = server.enabled === false ? 'server-disabled' : '';

                    return `
                        <div class="server-item ${disabledClass} ${selectedServer?.id === server.id ? 'active' : ''}" data-dap-server="${server.id}" onclick='selectDapServer(${JSON.stringify(server)})' style="cursor: pointer;">
                            <div class="server-name" style="display: flex; align-items: center; justify-content: space-between;">
                                <span>
                                    <span class="server-source-icon">🐛</span>
                                    ${server.name}
                                </span>
                                <label class="toggle-switch" onclick="event.stopPropagation()">
                                    <input type="checkbox" ${server.enabled !== false ? 'checked' : ''} onchange="toggleWorkspaceDapServerEnabled('${server.id}', this.checked)">
                                    <span class="toggle-slider"></span>
                                </label>
                            </div>
                            <div class="server-id">${server.id}</div>
                            <div class="server-actions">
                                ${actions}
                            </div>
                        </div>
                        ${sessions.map(session => {
                            // Use createSessionHTML from admin-dap.js if available, otherwise fallback
                            if (typeof window.createSessionHTML === 'function') {
                                return window.createSessionHTML(session);
                            }
                            // Fallback (should not happen if admin-dap.js is loaded)
                            return `<div data-session-id="${session.sessionId}" class="dap-session-item">${session.sessionName}</div>`;
                        }).join('')}
                    `;
                }).join('')}
            `;
        }

        function selectDapSession(sessionId) {
            selectedServer = null;
            // Forward to admin-dap.js
            if (typeof window.selectDapSession === 'function') {
                window.selectDapSession(sessionId);
            }
        }

        function selectDapServer(dapServer) {
            selectedServer = {...dapServer, isDap: true};
            // Sync local variable with global (may have been updated by DELETED handler)
            dapSessions = window.dapSessions || [];
            const workspace = workspaces.find(w => w.rootUri === selectedWorkspace);
            renderServers([], dapSessions, workspace);
            loadConsole({...dapServer, isDap: true});
        }

        function selectDapSessionByServerId(serverId) {
            // Find the DAP server from workspace
            const workspace = workspaces.find(w => w.rootUri === selectedWorkspace);
            const dapServer = Object.values(window.dapConfigs || {})?.find(s => s.id === serverId);

            if (dapServer) {
                // Select the DAP server
                selectDapServer(dapServer);
            } else {
                // Server not found in workspace
                console.log('DAP server not found in workspace:', serverId);
            }
        }

        // Expose for diagram navigation
        window.selectDapSessionByServerId = selectDapSessionByServerId;

        function selectServer(server, isUserAction = false) {
            const wasAlreadySelected = selectedServer && selectedServer.id === server.id;
            selectedServer = server;

            // Track if this is an explicit user action
            if (isUserAction) {
                userExplicitlySelectedServer = true;
                console.log('User explicitly selected server:', server.id);
            }

            // Clear DAP session when selecting an LSP server
            window.currentDapSessionId = null;

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

        // Expose for diagram navigation
        window.selectServer = selectServer;

        function showPlaceholder() {
            document.getElementById('console-area').innerHTML = `
                <div class="placeholder">
                    ← Select an LSP server to view console
                </div>
            `;
        }

        let currentTraceLevel = 'verbose';
        // window.currentServerId is declared in admin.js and used globally

        async function changeTraceLevel(level) {
            currentTraceLevel = level;
            updateTracesButtonsState(level);
            renderConsole();

            if (selectedServer && selectedServer.isDap) {
                changeDapServerTraceLevel(selectedServer.id, level);
            } else if (window.currentServerId) {
                if (window.traceLevels) {
                    window.traceLevels['lsp.' + window.currentServerId] = level;
                }
                try {
                    await fetch(`/api/admin/traces/lsp/${window.currentServerId}`, {
                        method: 'PUT',
                        headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify({ traceLevel: level })
                    });
                } catch (error) {
                    console.error('Failed to change trace level:', error);
                }
            }
        }

        function updateTracesButtonsState(level) {
            TraceRenderer.updateTraceControls('trace', level);
        }

        /**
         * Filter traces based on trace level.
         * - off: show nothing
         * - messages: show all messages (header only, no body)
         * - verbose: show everything (header + body)
         */
        function shouldShowTrace(trace, level) {
            if (level === 'off') {
                return false;
            }

            // Both 'messages' and 'verbose' show all traces
            // The difference is in how they're displayed (header only vs header+body)
            return true;
        }

        async function loadConsole(server) {
            // Check if server has contributions
            const workspace = workspaces.find(w => w.rootUri === selectedWorkspace);
            // Include both LSP and DAP servers for contribution detection (mark DAP servers)
            const dapServersWithFlag = (Object.values(window.dapConfigs || {}) || []).map(s => ({...s, isDap: true}));
            const allServers = workspace ? [...(workspace.lspServers || []), ...dapServersWithFlag] : [];
            const hasContributions = (server.contributions && Object.keys(server.contributions).length > 0) ||
                                    buildContributedByMap(allServers)[server.id]?.length > 0;

            // Extensions and DAP servers don't have LSP traces - default to overview
            if ((server.isExtension || server.isDap) && currentConsoleTab === 'traces') {
                currentConsoleTab = 'overview';
            }

            // If current tab is contributions but there are none, switch to appropriate default
            if (!hasContributions && currentConsoleTab === 'contributions') {
                currentConsoleTab = (server.isExtension || server.isDap) ? 'overview' : 'traces';
            }

            // Build icon for console title
            const isExternal = server.externalInstance != null &&
                              (server.status === 'CONNECTED_TO_IDE' || server.status === 'CONNECTING_TO_IDE');
            const titleIcon = isExternal ? '🔗' : (server.isExtension ? '🧩' : (server.isDap ? '🐛' : '🚀'));


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
                            ${!server.isExtension && !server.isDap ? `<button class="tab-button ${currentConsoleTab === 'traces' ? 'active' : ''}" onclick="switchConsoleTab('traces')">Traces</button>` : ''}
                            <button class="tab-button ${currentConsoleTab === 'overview' ? 'active' : ''}" onclick="switchConsoleTab('overview')">Overview</button>
                            ${hasContributions ? `<button class="tab-button ${currentConsoleTab === 'contributions' ? 'active' : ''}" onclick="switchConsoleTab('contributions')">Contributions</button>` : ''}
                            <button class="tab-button ${currentConsoleTab === 'install' ? 'active' : ''}" onclick="switchConsoleTab('install')">Install</button>
                        </div>
                        <div class="console-controls">
                            ${TraceRenderer.renderTraceControls('trace', currentTraceLevel, 'changeTraceLevel(this.value)', {
                                onFold: 'toggleAllTraces()',
                                onClear: 'clearConsole()',
                                wrapperId: 'traces-controls',
                                wrapperDisplay: currentConsoleTab === 'traces' ? 'contents' : 'none'
                            })}
                        </div>
                    </div>
                    <div class="tab-content">
                        <div id="traces-tab" class="tab-panel ${currentConsoleTab === 'traces' ? 'active' : ''}">
                            <div class="console" id="console-output" tabindex="0"></div>
                        </div>
                        <div id="overview-tab" class="tab-panel ${currentConsoleTab === 'overview' ? 'active' : ''}">
                            <div class="details-panel" id="overview-content">
                                <p>Loading...</p>
                            </div>
                        </div>
                        ${hasContributions ? `
                        <div id="contributions-tab" class="tab-panel ${currentConsoleTab === 'contributions' ? 'active' : ''}">
                            <div id="workspace-diagram-container" style="width: 100%; height: 400px; background: var(--bg-card); flex-shrink: 0;"></div>
                            <div class="diagram-resizer"></div>
                            <div class="details-panel text-primary" id="contributions-content" style="padding: 2rem; overflow-y: auto; flex: 1; min-height: 0;">
                                <p>Loading...</p>
                            </div>
                        </div>
                        ` : ''}
                        <div id="install-tab" class="tab-panel ${currentConsoleTab === 'install' ? 'active' : ''}">
                            <div class="install-panel">
                                <h3>Installer Configuration</h3>
                                <div class="install-info">
                                    <p><strong>Server:</strong> ${server.name}</p>
                                    <p><strong>ID:</strong> ${server.id}</p>
                                </div>
                                <div class="installer-editor">
                                    <div class="editor-header">
                                        <span>installer.json</span>
                                        <div class="editor-actions">
                                            <button class="editor-btn" onclick="saveInstallerJson('${server.id}')" title="Save">💾 Save</button>
                                            <button class="editor-btn" onclick="resetInstallerJson('${server.id}')" title="Reset">↻ Reset</button>
                                            <span class="editor-separator"></span>
                                            <button class="editor-btn install-run-btn" onclick="runInstaller('${server.id}', false)" title="Install (check first, skip if already installed)">▶ Install</button>
                                            <button class="editor-btn install-force-btn" onclick="runInstaller('${server.id}', true)" title="Force Install (skip check, always re-install)">⟳ Force Install</button>
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

            window.currentServerId = server.id;

            // Store servers data for diagram rendering (include both LSP and DAP)
            const currentWorkspace = workspaces.find(w => w.rootUri === selectedWorkspace);
            if (currentWorkspace) {
                window.currentWorkspaceDiagramServers = allServers;
                window.currentWorkspaceDiagramServerId = server.id;
            }

            // If contributions tab is active, render diagram immediately
            if (currentConsoleTab === 'contributions' && currentWorkspace) {
                setTimeout(() => renderWorkspaceDiagram(allServers, server.id), 100);
            }

            // Initialize trace level selector from WebSocket-provided data
            const traceKey = server.isDap ? 'dap.' + server.id : 'lsp.' + server.id;
            const savedTraceLevel = window.traceLevels && window.traceLevels[traceKey];
            currentTraceLevel = savedTraceLevel || 'off';
            const traceLevelSelect = document.getElementById('trace-level');
            if (traceLevelSelect) {
                traceLevelSelect.value = currentTraceLevel;
            }
            updateTracesButtonsState(currentTraceLevel);

            // Load traces for specific workspace + server
            try {
                // Traces are populated via WebSocket (history on connect + real-time updates)
                if (!tracesByServer[server.id]) {
                    tracesByServer[server.id] = [];
                }
                renderConsole();
            } catch (error) {
                console.error('Failed to load traces:', error);
            }

            // Load server details
            loadServerDetails(server.id);

            // Load installer.json
            loadInstallerJson(server.id);
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

                const workspace = workspaces.find(w => w.rootUri === selectedWorkspace);

                // Check if this is a DAP server
                const dapServer = Object.values(window.dapConfigs || {})?.find(s => s.id === serverId);

                if (dapServer) {
                    // DAP server - display its details directly
                    const dapServersWithFlag = (Object.values(window.dapConfigs || {}) || []).map(s => ({...s, isDap: true}));
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
                } else {
                    // LSP server - fetch from API
                    const response = await fetch(`/api/admin/lsp/configs/${serverId}`);
                    if (!response.ok) {
                        throw new Error('Failed to load server details');
                    }

                    const details = await response.json();

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

                ${server.documentSelector && server.documentSelector.length > 0 ? `
                <div class="details-section">
                    <h4>Document Selector</h4>
                    ${server.documentSelector.map(selector => `
                        <div class="selector-item">
                            ${selector.language ? `<span class="selector-tag">language: ${selector.language}</span>` : ''}
                            ${selector.scheme ? `<span class="selector-tag">scheme: ${selector.scheme}</span>` : ''}
                            ${selector.pattern ? `<span class="selector-tag">pattern: ${selector.pattern}</span>` : ''}
                        </div>
                    `).join('')}
                </div>
                ` : ''}

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

        /**
         * Format contributions section for server details view.
         * Shows both "Contributes To" and "Contributed By" with contribution details.
         * @param {Object} server - The server object with contributions
         * @param {Array} allServers - All servers list for calculating contributedBy (optional, will fetch from workspace if not provided)
         */
        function formatContributionsSection(server, allServers = null) {
            console.log('formatContributionsSection - server:', server.id, 'contributions:', server.contributions);
            const contributesTo = server.contributions ? Object.keys(server.contributions) : [];

            // Calculate contributedBy from all servers (include both LSP and DAP, mark DAP servers)
            if (!allServers) {
                const workspace = workspaces.find(w => w.rootUri === selectedWorkspace);
                const dapServersWithFlag = (Object.values(window.dapConfigs || {}) || []).map(s => ({...s, isDap: true}));
                allServers = workspace ? [...(workspace.lspServers || []), ...dapServersWithFlag] : [];
            }
            const contributedByMap = buildContributedByMap(allServers);
            const contributedBy = contributedByMap[server.id] || [];
            console.log('  contributesTo:', contributesTo, 'contributedBy:', contributedBy);

            if (contributesTo.length === 0 && contributedBy.length === 0) {
                return ''; // No contributions section if nothing to show
            }

            let html = '<div class="details-section"><h4>Contributions</h4>';

            // Show "Contributes To" section
            if (contributesTo.length > 0) {
                html += '<div class="contribution-subsection">';
                html += '<h5 class="text-success" style="margin-bottom: 0.5rem;">→ Contributes To</h5>';

                for (const targetServerId of contributesTo) {
                    const contributionData = server.contributions[targetServerId];
                    html += `<div class="contribution-target" style="margin-bottom: 1rem;">`;
                    html += `<div class="text-label-alt" style="font-weight: bold; margin-bottom: 0.25rem;">${targetServerId}</div>`;

                    // Show each contribution type (bundles, classpath, bindRequest, etc.)
                    for (const [type, items] of Object.entries(contributionData)) {
                        if (items && items.length > 0) {
                            html += `<div style="margin-left: 1rem; margin-bottom: 0.5rem;">`;
                            html += `<span class="text-secondary">${type}:</span>`;
                            html += `<ul class="text-secondary" style="margin: 0.25rem 0 0 1.5rem; padding: 0; font-size: 0.9rem;">`;
                            items.forEach(item => {
                                const displayValue = typeof item === 'string' ? item : JSON.stringify(item);
                                const isError = displayValue.startsWith('ERROR:');
                                const cleanValue = isError ? displayValue.substring(6) : displayValue;
                                const errorClass = isError ? 'text-error-light' : '';
                                const style = isError
                                    ? 'font-weight: bold; cursor: help;'
                                    : '';
                                const title = isError ? 'File not found or pattern did not match any files' : '';
                                html += `<li class="${errorClass}" style="margin-bottom: 0.2rem; word-break: break-all; ${style}" ${title ? `title="${title}"` : ''}>${escapeHtml(cleanValue)}</li>`;
                            });
                            html += `</ul></div>`;
                        }
                    }
                    html += `</div>`;
                }
                html += '</div>';
            }

            // Show "Contributed By" section grouped by contribution type
            if (contributedBy.length > 0) {
                html += '<div class="contribution-subsection" style="margin-top: 1rem;">';
                html += '<h5 class="text-string" style="margin-bottom: 0.5rem;">← Contributed By</h5>';

                // Group contributions by type (bundles, bindRequest, classpath, etc.)
                const contributionsByType = {};

                contributedBy.forEach(contributorServerId => {
                    // Find the contributor server in allServers to get its contributions
                    const contributorServer = allServers.find(s => s.id === contributorServerId);
                    if (!contributorServer || !contributorServer.contributions) {
                        return;
                    }

                    // Get what this contributor gives to the current server
                    const contributionData = contributorServer.contributions[server.id];
                    if (!contributionData) {
                        return;
                    }

                    // Group by type
                    for (const [type, items] of Object.entries(contributionData)) {
                        if (items && items.length > 0) {
                            if (!contributionsByType[type]) {
                                contributionsByType[type] = [];
                            }
                            items.forEach(item => {
                                contributionsByType[type].push({
                                    server: contributorServerId,
                                    value: item
                                });
                            });
                        }
                    }
                });

                // Display grouped by type
                for (const [type, contributions] of Object.entries(contributionsByType)) {
                    html += `<div style="margin-bottom: 1rem;">`;
                    html += `<div class="text-secondary" style="font-weight: bold; margin-bottom: 0.5rem;">${type} <span class="text-dimmed">(Total: ${contributions.length})</span></div>`;
                    html += `<div style="margin-left: 1rem;">`;

                    contributions.forEach(contrib => {
                        const displayValue = typeof contrib.value === 'string' ? contrib.value : JSON.stringify(contrib.value);
                        const isError = displayValue.startsWith('ERROR:');
                        const cleanValue = isError ? displayValue.substring(6) : displayValue;
                        const valueClass = isError ? 'text-error-light' : '';
                        const valueStyle = isError
                            ? 'word-break: break-all; font-weight: bold; cursor: help;'
                            : 'word-break: break-all;';
                        const title = isError ? 'File not found or pattern did not match any files' : '';
                        html += `<div class="text-secondary" style="margin-bottom: 0.3rem; font-size: 0.9rem;">`;
                        html += `<span class="text-label-alt" style="display: inline-block; min-width: 120px;">${contrib.server}</span>`;
                        html += `<span class="text-label">•</span> `;
                        html += `<span class="${valueClass}" style="${valueStyle}" ${title ? `title="${title}"` : ''}>${escapeHtml(cleanValue)}</span>`;
                        html += `</div>`;
                    });

                    html += `</div></div>`;
                }

                html += '</div>';
            }

            html += '</div>';
            return html;
        }

        function switchConsoleTab(tabName) {
            currentConsoleTab = tabName; // Save current tab

            // Update tab buttons
            document.querySelectorAll('.tab-button').forEach(btn => {
                btn.classList.remove('active');
            });
            event.target.classList.add('active');

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
            if (typeof updateSearchBoxVisibility === 'function') {
                updateSearchBoxVisibility(tabName === 'traces');
            }

            // Render diagram when switching to contributions tab
            if (tabName === 'contributions' && window.currentWorkspaceDiagramServers) {
                renderWorkspaceDiagram(window.currentWorkspaceDiagramServers, window.currentWorkspaceDiagramServerId);
            }
        }

        function switchServerTab(tabName) {
            currentServerTab = tabName; // Save current tab

            // Update tab buttons
            document.querySelectorAll('.tab-button').forEach(btn => {
                btn.classList.remove('active');
            });
            event.target.classList.add('active');

            // Update tab panels
            document.querySelectorAll('.tab-panel').forEach(panel => {
                panel.classList.remove('active');
            });
            document.getElementById('server-' + tabName + '-tab').classList.add('active');

            // Render diagram when switching to contributions tab
            if (tabName === 'contributions' && window.currentDiagramServers) {
                renderServerDiagram(window.currentDiagramServers, window.currentDiagramServerId);
            }
        }


        function renderConsole() {
            const container = document.getElementById('console-output');
            if (!container) return;

            // Get traces for current server
            const traces = tracesByServer[window.currentServerId] || [];

            // Filter traces based on current level
            const filteredTraces = traces.filter(trace => shouldShowTrace(trace, currentTraceLevel));

            if (filteredTraces.length === 0) {
                const message = currentTraceLevel === 'off'
                    ? 'Traces are disabled (level: off)'
                    : 'No LSP trace messages yet.';
                container.innerHTML = `<div class="text-secondary" style="text-align: center; padding: 2rem;">${message}</div>`;
                return;
            }

            const wasAtBottom = TraceRenderer.isScrolledToBottom(container);
            const expandedIds = TraceRenderer.saveExpandedState(container);

            container.innerHTML = filteredTraces.map((trace, index) => {
                const content = trace.content;

                // Parse the trace: first line is header, rest is body
                const lines = content.split('\n');
                const headerLine = lines[0]; // [Trace - HH:mm:ss] ...

                // Detect trace type for coloring
                const isError = headerLine.startsWith('[Error') || trace.messageType === 'ERROR';
                const isInfo = trace.messageType === 'INFO';
                const isUpdate = trace.messageType === 'UPDATE';
                const traceTypeClass = isError ? 'trace-type-error' : isInfo ? 'trace-type-info' : isUpdate ? 'trace-type-update' : 'trace-type-default';

                // Messages mode: show only header line, no folding
                if (currentTraceLevel === 'messages') {
                    return `
                        <div class="trace-line">
                            <div class="${traceTypeClass}" style="padding: 0.25rem; font-family: 'Consolas', 'Monaco', monospace; font-size: 0.85rem;">${escapeHtml(headerLine)}</div>
                        </div>
                    `;
                }

                // Verbose mode: header + body folded by default
                const bodyLines = lines.slice(1);
                const body = bodyLines.join('\n').trim();
                const hasBody = body.length > 0;

                // If no body, display like messages mode (no toggle arrow)
                if (!hasBody) {
                    return `
                        <div class="trace-line">
                            <div class="${traceTypeClass}" style="padding: 0.25rem; font-family: 'Consolas', 'Monaco', monospace; font-size: 0.85rem;">${escapeHtml(headerLine)}</div>
                        </div>
                    `;
                }

                // Filter out trailing empty lines
                let trimmedBodyLines = bodyLines;
                while (trimmedBodyLines.length > 0 && trimmedBodyLines[trimmedBodyLines.length - 1].trim() === '') {
                    trimmedBodyLines = trimmedBodyLines.slice(0, -1);
                }

                // Colorize stack trace lines in body
                let bodyHtml = '';
                for (let i = 0; i < trimmedBodyLines.length; i++) {
                    const line = trimmedBodyLines[i];
                    const trimmed = line.trim();
                    if (isError && trimmed.startsWith('at ') && trimmed.includes('(') && trimmed.includes(')')) {
                        bodyHtml += `<span class="text-error-light">${escapeHtml(line)}</span>`;
                    } else {
                        bodyHtml += escapeHtml(line);
                    }
                    // Add newline except for last line
                    if (i < trimmedBodyLines.length - 1) {
                        bodyHtml += '\n';
                    }
                }

                const fullContent = headerLine + '\n' + body;

                return `
                    <div class="trace-line" onmouseenter="showTooltip(event, ${index}, true)" onmouseleave="hideTooltip(${index})">
                        <div class="trace-header folded" id="header-${index}"
                             onmousedown="onHeaderMouseDown(${index})"
                             onmouseup="onHeaderMouseUp(${index})"
                             style="padding: 0.25rem; font-family: 'Consolas', 'Monaco', monospace; font-size: 0.85rem;">
                            <span class="trace-toggle" id="toggle-${index}" style="margin-right: 0.5rem;">▶</span>
                            <span class="trace-header-text ${traceTypeClass}">${escapeHtml(headerLine)}</span>
                        </div>
                        <div class="trace-body collapsed text-primary" id="body-${index}" style="font-family: 'Consolas', 'Monaco', monospace; font-size: 0.85rem; white-space: pre-wrap;">${bodyHtml}</div>
                        <div class="trace-tooltip" id="tooltip-${index}">${escapeHtml(fullContent)}</div>
                    </div>
                `;
            }).join('');

            TraceRenderer.restoreExpandedState(container, expandedIds);

            if (wasAtBottom) {
                container.scrollTop = container.scrollHeight;
            }
        }

        // escapeHtml, showTooltip, hideTooltip, toggleTrace now provided by TraceRenderer

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
                    window.toggleTrace(index); // Use global function from TraceRenderer
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

        function toggleAllTraces() {
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

                // Clear traces for current server only
                if (window.currentServerId) {
                    tracesByServer[window.currentServerId] = [];
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
            if (!selectedWorkspace) return;

            const server = workspaces.find(w => w.rootUri === selectedWorkspace)?.lspServers.find(s => s.id === serverId);
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
                    `/api/admin/lsp/servers/${encodeURIComponent(selectedWorkspace)}/${serverId}/stop`,
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
            if (!selectedWorkspace) return;

            const server = workspaces.find(w => w.rootUri === selectedWorkspace)?.lspServers.find(s => s.id === serverId);
            if (!server) return;

            clearServerActions(serverId);
            try {
                const response = await fetch(
                    `/api/admin/lsp/servers/${encodeURIComponent(selectedWorkspace)}/${serverId}/start-managed`,
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
            if (!selectedWorkspace) return;

            const server = workspaces.find(w => w.rootUri === selectedWorkspace)?.lspServers.find(s => s.id === serverId);
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
                    `/api/admin/lsp/servers/${encodeURIComponent(selectedWorkspace)}/${serverId}/restart`,
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
            if (!selectedWorkspace) return;

            const server = workspaces.find(w => w.rootUri === selectedWorkspace)?.lspServers.find(s => s.id === serverId);
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
                    `/api/admin/lsp/servers/${encodeURIComponent(selectedWorkspace)}/${serverId}/disconnect`,
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
            if (!selectedWorkspace) return;

            const server = workspaces.find(w => w.rootUri === selectedWorkspace)?.lspServers.find(s => s.id === serverId);
            if (!server) return;

            clearServerActions(serverId);
            try {
                const response = await fetch(
                    `/api/admin/lsp/servers/${encodeURIComponent(selectedWorkspace)}/${serverId}/connect-ide`,
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
        TraceRenderer.initSearchListeners((query) => {
            // Re-render with highlighting based on active console
            // Check DAP first (by currentDapSessionId presence, not tab name)
            if (window.currentDapSessionId) {
                if (window.renderDapTracesForSession) {
                    window.renderDapTracesForSession(window.currentDapSessionId);
                }
            } else if (window.currentTab === 'mcp-traces') {
                if (window.renderMcpConsoleWithHighlights) {
                    window.renderMcpConsoleWithHighlights();
                }
            } else {
                // LSP traces
                renderConsoleWithHighlights();
            }
        });

        // Aliases to TraceRenderer utilities for local use
        const highlightText = TraceRenderer.highlightText;
        const escapeHtml = TraceRenderer.escapeHtml;
        const escapeRegex = TraceRenderer.escapeRegex;

        function renderConsoleWithHighlights() {
            const container = document.getElementById('console-output');
            if (!container) return;

            const traces = tracesByServer[window.currentServerId] || [];
            const filteredTraces = traces.filter(trace => shouldShowTrace(trace, currentTraceLevel));

            if (filteredTraces.length === 0) {
                const message = currentTraceLevel === 'off'
                    ? 'Traces are disabled (level: off)'
                    : 'No LSP trace messages yet.';
                container.innerHTML = `<div class="text-secondary" style="text-align: center; padding: 2rem;">${message}</div>`;
                return;
            }

            // Use TraceRenderer for rendering traces
            container.innerHTML = filteredTraces.map((trace, index) =>
                TraceRenderer.renderTrace(trace, index, currentTraceLevel, TraceRenderer.getCurrentSearchQuery())
            ).join('');
        }

        // Search functions delegated to TraceRenderer (see initSearchListeners above)

        function renderConsoleWithoutScroll() {
            const container = document.getElementById('console-output');
            if (!container) return;

            // Get traces for current server
            const traces = tracesByServer[window.currentServerId] || [];

            // Filter traces based on current level
            const filteredTraces = traces.filter(trace => shouldShowTrace(trace, currentTraceLevel));

            if (filteredTraces.length === 0) {
                const message = currentTraceLevel === 'off'
                    ? 'Traces are disabled (level: off)'
                    : 'No LSP trace messages yet.';
                container.innerHTML = `<div class="text-secondary" style="text-align: center; padding: 2rem;">${message}</div>`;
                return;
            }

            container.innerHTML = filteredTraces.map((trace, index) => {
                const content = trace.content;

                // Parse the trace: first line is header, rest is body
                const lines = content.split('\n');
                const headerLine = lines[0]; // [Trace - HH:mm:ss] ...

                // Messages mode: show only header line, no folding
                if (currentTraceLevel === 'messages') {
                    return `
                        <div class="trace-line">
                            <div class="text-primary" style="padding: 0.25rem; font-family: 'Consolas', 'Monaco', monospace; font-size: 0.85rem;">${escapeHtml(headerLine)}</div>
                        </div>
                    `;
                }

                // Verbose mode: header + body folded by default
                const bodyLines = lines.slice(1);
                const body = bodyLines.join('\n').trim();
                const hasBody = body.length > 0;

                // If no body, display like messages mode (no toggle arrow)
                if (!hasBody) {
                    return `
                        <div class="trace-line">
                            <div class="text-primary" style="padding: 0.25rem; font-family: 'Consolas', 'Monaco', monospace; font-size: 0.85rem;">${escapeHtml(headerLine)}</div>
                        </div>
                    `;
                }

                return `
                    <div class="trace-line">
                        <div class="trace-header folded" onclick="toggleTrace(${index})" style="padding: 0.25rem; font-family: 'Consolas', 'Monaco', monospace; font-size: 0.85rem;">
                            <span class="trace-toggle" id="toggle-${index}" style="margin-right: 0.5rem;">▶</span>
                            <span class="trace-header-text text-primary">${escapeHtml(headerLine)}</span>
                        </div>
                        <div class="trace-body collapsed text-primary" id="body-${index}" style="font-family: 'Consolas', 'Monaco', monospace; font-size: 0.85rem;">${escapeHtml(body)}</div>
                    </div>
                `;
            }).join('');
        }

        // Modal functions
        function showModal(title, message, buttons) {
            const modal = document.getElementById('modal-overlay');
            const modalTitle = document.getElementById('modal-title');
            const modalMessage = document.getElementById('modal-message');
            const modalButtons = document.getElementById('modal-buttons');

            modalTitle.textContent = title;
            modalMessage.textContent = message;

            modalButtons.innerHTML = buttons.map(btn => `
                <button class="modal-button ${btn.type || 'secondary'}"
                        onclick="${btn.onclick}">${btn.label}</button>
            `).join('');

            modal.classList.add('visible');
        }

        function hideModal() {
            document.getElementById('modal-overlay').classList.remove('visible');
        }

        async function confirmAction(title, message, confirmLabel, isDanger = false) {
            return new Promise((resolve) => {
                showModal(title, message, [
                    {
                        label: 'Cancel',
                        type: 'secondary',
                        onclick: `hideModal(); window.modalResolve(false);`
                    },
                    {
                        label: confirmLabel,
                        type: isDanger ? 'danger' : 'primary',
                        onclick: `hideModal(); window.modalResolve(true);`
                    }
                ]);

                window.modalResolve = resolve;
            });
        }

        function showAlert(title, message) {
            showModal(title, message, [
                {
                    label: 'OK',
                    type: 'primary',
                    onclick: 'hideModal()'
                }
            ]);
        }

        // Close modal on overlay click (if modal exists)
        const modalOverlay = document.getElementById('modal-overlay');
        if (modalOverlay) {
            modalOverlay.addEventListener('click', (e) => {
                if (e.target.id === 'modal-overlay') {
                    hideModal();
                    if (window.modalResolve) {
                        window.modalResolve(false);
                    }
                }
            });
        }

        // Expose globally for DAP session updates
        window.loadDapSessionsForWorkspace = loadDapSessionsForWorkspace;
        window.renderServers = renderServers;


// Expose globally
window.loadDapSessions = loadDapSessions;

// Expose renderWorkspaces at global scope (needed by admin.js)
if (typeof renderWorkspaces !== 'undefined') {
    window.renderWorkspaces = renderWorkspaces;
}
