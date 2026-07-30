        let workspaces = [];
        let selectedWorkspace = null;
        let selectedServer = null;

        // Expose globally for admin-dap.js and admin-lsp.js
        window.selectedWorkspace = null;
        window.workspaces = workspaces;

        // ========== Theme Toggle ==========
        const THEME_DARK = 'dark';
        const THEME_LIGHT = 'light';
        const THEME_STORAGE_KEY = 'admin-theme';
        const THEME_ATTR = 'data-theme';

        function getCurrentTheme() {
            return document.documentElement.getAttribute(THEME_ATTR) || THEME_DARK;
        }

        function setTheme(theme) {
            document.documentElement.setAttribute(THEME_ATTR, theme);
            localStorage.setItem(THEME_STORAGE_KEY, theme);
            updateThemeIcon(theme);
        }

        function toggleTheme() {
            const next = getCurrentTheme() === THEME_DARK ? THEME_LIGHT : THEME_DARK;
            setTheme(next);
            // Persist to server settings
            fetch('/api/admin/settings/admin.theme', {
                method: 'PUT',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ value: next })
            }).catch(() => {});
            // Re-render diagram if visible
            if (typeof renderCurrentDiagram === 'function') {
                renderCurrentDiagram();
            }
        }
        window.toggleTheme = toggleTheme;

        function updateThemeIcon(theme) {
            const btn = document.getElementById('theme-toggle-btn');
            if (btn) {
                btn.innerHTML = theme === THEME_DARK ? '🌙' : '☀️';
                btn.title = theme === THEME_DARK ? 'Switch to light theme' : 'Switch to dark theme';
            }
        }

        // Init theme icon on load
        updateThemeIcon(getCurrentTheme());

        let tracesByServer = {}; // Store traces per server: {serverId: [...traces]}
        let currentTab = 'workspaces';
        let workspacesRendered = false; // Track if workspaces have been rendered at least once
        let currentConsoleTab = 'traces'; // Track current tab in Workspaces view
        let currentWorkspaceTab = 'servers'; // Track current tab in workspace view: 'servers' or 'debuggers'

        // Expose globals for other scripts
        window.currentTab = currentTab;
        window.currentServerId = null; // Track currently selected LSP server (used by admin-workspace.js)

        // WebSocket connection (replaces SSE and polling)
        let adminWebSocket = null;

        // Global LSP configurations (static config loaded once at startup)
        let lspConfigs = {}; // Map<serverId, LspConfigDTO>
        let dapConfigs = {}; // Map<serverId, DapConfigDTO>

        // Trace levels received via WebSocket: { "lsp.serverId": level, "dap.serverId": level, "mcp": level }
        let traceLevels = {};
        window.traceLevels = traceLevels;

        // Expose configs and merge function globally for admin-lsp.js and admin-dap.js
        window.lspConfigs = lspConfigs;
        window.dapConfigs = dapConfigs;
        window.mergeServerData = mergeServerData;
        window.loadLspConfigs = loadLspConfigs;
        window.loadDapConfigs = loadDapConfigs;

        /**
         * Returns the API base path for a server: '/api/admin/dap/configs' or '/api/admin/lsp/configs'.
         */
        function getServerApiBase(serverId) {
            const isDap = window.dapConfigs && window.dapConfigs[serverId];
            return isDap ? '/api/admin/dap/configs' : '/api/admin/lsp/configs';
        }
        window.getServerApiBase = getServerApiBase;

        /**
         * Show or hide the search box based on whether we're viewing trace console.
         */
        function updateSearchBoxVisibility(showSearchBox) {
            const searchBox = document.getElementById('search-box');
            if (searchBox) {
                // Always close the popup when changing visibility
                searchBox.classList.remove('visible');

                if (showSearchBox) {
                    // Make search box available (but closed) - use CSS class instead of inline style
                    searchBox.classList.add('search-box-available');
                } else {
                    // Hide search box completely
                    searchBox.classList.remove('search-box-available');
                }
            }
        }

        /**
         * Format status into CSS class name.
         */
        function formatStatusClass(status, isReady) {
            if (status === 'RUNNING' && !isReady) {
                return 'status-running-not-ready';
            }
            return 'status-' + status.toLowerCase();
        }

        /**
         * Format status label for display.
         */
        function formatStatusLabel(status, externalInstance) {
            const labels = {
                'NOT_STARTED': 'Not Started',
                'INSTALLING': 'Installing',
                'INSTALL_FAILED': 'Install Failed',
                'STARTING': 'Starting',
                'START_FAILED': 'Start Failed',
                'RUNNING': 'Running',
                'STOPPING': 'Stopping',
                'STOPPED': 'Stopped',
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

        /**
         * Load global LSP configurations (called once at startup).
         */
        async function loadLspConfigs() {
            try {
                const response = await fetch('/api/admin/lsp/configs');
                const configs = await response.json();

                // Build a map for quick lookup
                lspConfigs = {};
                configs.forEach(config => {
                    lspConfigs[config.id] = config;
                });

                // Update global reference
                window.lspConfigs = lspConfigs;

                console.log('Loaded', configs.length, 'LSP configs');
            } catch (error) {
                console.error('Failed to load LSP configs:', error);
            }
        }

        /**
         * Load global DAP configurations (called once at startup).
         */
        async function loadDapConfigs() {
            try {
                const response = await fetch('/api/admin/dap/configs');
                const configs = await response.json();

                // Store globally as map for consistency
                dapConfigs = {};
                configs.forEach(config => {
                    dapConfigs[config.id] = config;
                });

                // Update global reference
                window.dapConfigs = dapConfigs;

                console.log('Loaded', configs.length, 'DAP configs');
            } catch (error) {
                console.error('Failed to load DAP configs:', error);
                window.dapConfigs = {};
            }
        }

        /**
         * Check if a server is an extension (no documentSelector = pure extension).
         * @param {Object} server - Server config
         * @returns {boolean} True if extension
         */
        function isExtension(server) {
            return !server.documentSelector || server.documentSelector.length === 0;
        }

        /**
         * Merge runtime state with static config.
         * @param {Object} runtime - ServerRuntimeDTO from workspace
         * @returns {Object} Merged server object with both config and runtime
         */
        function mergeServerData(runtime) {
            const serverId = runtime.serverId || runtime.id;
            const config = lspConfigs[serverId] || {};
            return {
                // Config fields
                id: serverId,
                name: config.name || serverId,
                description: config.description,
                documentSelector: config.documentSelector,
                command: runtime.command || config.command,
                args: config.args,
                env: config.env,
                workingDirectory: config.workingDirectory,
                initializationOptions: config.initializationOptions,
                contributions: config.contributions,
                isExtension: config.isExtension,
                enabled: config.enabled,
                parentServerId: runtime.parentServerId,

                // Runtime fields
                status: runtime.status,
                statusMessage: runtime.statusMessage,
                isReady: runtime.isReady,
                pid: runtime.pid,
                externalInstance: runtime.externalInstance,
                installProgress: runtime.installProgress
            };
        }

        /**
         * Build contributedBy map (inverse of contributesTo)
         */
        function buildContributedByMap(servers) {
            const map = {};
            for (const server of servers) {
                // contributions is now a Map<targetServerId, Map<contributionType, List<?>>>
                if (server.contributions) {
                    for (const targetId of Object.keys(server.contributions)) {
                        if (!map[targetId]) map[targetId] = [];
                        map[targetId].push(server.id);
                    }
                }
            }
            return map;
        }

        /**
         * Format contribute info for display (contributesTo or contributedBy)
         */
        function formatContributeInfo(server, contributedByMap) {
            // Extract contributesTo from contributions map
            const contributesTo = server.contributions ? Object.keys(server.contributions) : [];
            const contributedBy = contributedByMap[server.id] || [];

            let text = '';
            let tooltip = '';

            if (contributesTo.length > 0) {
                const full = contributesTo.join(', ');
                const styled = contributesTo.map(id => `<span class="text-secondary">${id}</span>`).join(', ');
                const displayStyled = full.length > 20
                    ? contributesTo.slice(0, 1).map(id => `<span class="text-secondary">${id}</span>`).join('') + ', <span class="text-secondary">...</span>'
                    : styled;
                text = ` <span class="text-muted" style="font-size: 1.3rem; font-weight: bold;">→</span> ${displayStyled}`;
                if (full.length > 20) {
                    tooltip = `Contributes to: ${full}`;
                }
            } else if (contributedBy.length > 0) {
                const full = contributedBy.join(', ');
                const styled = contributedBy.map(id => `<span class="text-secondary">${id}</span>`).join(', ');
                const displayStyled = full.length > 20
                    ? contributedBy.slice(0, 1).map(id => `<span class="text-secondary">${id}</span>`).join('') + ', <span class="text-secondary">...</span>'
                    : styled;
                text = ` <span class="text-muted" style="font-size: 1.3rem; font-weight: bold;">←</span> ${displayStyled}`;
                if (full.length > 20) {
                    tooltip = `Contributed by: ${full}`;
                }
            }

            return { text, tooltip };
        }

        /**
         * Connect to admin WebSocket for real-time updates.
         * Replaces SSE streams and polling intervals.
         */
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

        /**
         * Route incoming WebSocket messages by type.
         */
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
                    handleDapSessionUpdate(message);
                    break;
                case 'progress-init':
                    if (typeof handleProgressInit === 'function') {
                        handleProgressInit(message);
                    }
                    break;
                case 'progress-update':
                    if (typeof handleProgressUpdate === 'function') {
                        handleProgressUpdate(message);
                    }
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
                default:
                    console.warn('Unknown WebSocket message type:', message.type);
            }
        }

        /**
         * Handle LSP trace message from WebSocket.
         */
        function handleLspTrace(trace) {
            console.log('handleLspTrace called for server:', trace.serverId, 'current:', window.currentServerId);

            // Forward to install output panel if this server is being installed
            if (window.installOutputServerId === trace.serverId && typeof appendInstallTrace === 'function') {
                appendInstallTrace(trace);
            }

            // Store trace by server
            if (!tracesByServer[trace.serverId]) {
                tracesByServer[trace.serverId] = [];
            }

            // Check if this is an UPDATE message (replaces previous line)
            if (trace.messageType === 'UPDATE') {
                const traces = tracesByServer[trace.serverId];
                const lastTrace = traces[traces.length - 1];
                // Replace last trace if it was also an UPDATE or if it exists
                if (lastTrace && lastTrace.messageType === 'UPDATE') {
                    traces[traces.length - 1] = trace;
                } else {
                    traces.push(trace);
                }
            } else {
                tracesByServer[trace.serverId].push(trace);
            }

            console.log('Stored trace, total for', trace.serverId, ':', tracesByServer[trace.serverId].length);

            // Keep only last 200 traces per server
            if (tracesByServer[trace.serverId].length > 200) {
                tracesByServer[trace.serverId] = tracesByServer[trace.serverId].slice(-200);
            }

            // If this is an installation trace (INFO, UPDATE, ERROR) and no server is currently selected,
            // auto-select this server to show installation progress
            if ((trace.messageType === 'INFO' || trace.messageType === 'UPDATE' || trace.messageType === 'ERROR') &&
                !window.currentServerId) {
                console.log('Auto-selecting server for installation:', trace.serverId);

                // Find the workspace and server (installation traces may have null workspaceUri)
                const workspace = trace.workspaceUri
                    ? workspaces.find(w => w.rootUri === trace.workspaceUri)
                    : workspaces.find(w => w.lspServers && w.lspServers.some(s => s.id === trace.serverId));
                if (workspace && workspace.lspServers) {
                    const server = workspace.lspServers.find(s => s.id === trace.serverId);
                    if (server) {
                        selectedWorkspace = workspace.rootUri;

                        console.log('Calling window.selectServer with:', server);
                        if (typeof window.selectServer === 'function') {
                            window.selectServer(server);
                        } else {
                            console.log('window.selectServer not found, using fallback');
                            window.currentServerId = trace.serverId;
                            if (typeof renderConsole === 'function') {
                                renderConsole();
                            }
                        }
                    } else {
                        console.log('Server not found in workspace:', trace.serverId);
                    }
                } else {
                    console.log('Workspace not found:', trace.workspaceUri);
                }
            }

            // Refresh console if this trace is for the currently selected server
            // Installation traces have null workspaceUri — match by serverId only
            if ((trace.workspaceUri == null || trace.workspaceUri === selectedWorkspace) &&
                trace.serverId === window.currentServerId) {
                console.log('Refreshing console for current server');
                renderConsole();
            }

        }

        /**
         * Handle DAP session update from WebSocket.
         */
        function handleDapSessionUpdate(message) {
            console.log('DAP session update:', message.eventType, message.sessionId);

            // Delegate to admin-dap.js
            if (typeof window.onDapSessionUpdate === 'function') {
                window.onDapSessionUpdate(message);
            }
        }

        /**
         * Handle DAP trace message from WebSocket.
         */
        function handleDapTrace(trace) {
            if (!window.dapTracesBySession) {
                window.dapTracesBySession = {};
            }
            if (!window.dapTracesByServer) {
                window.dapTracesByServer = {};
            }

            if (trace.sessionId) {
                // Protocol trace — store by sessionId
                if (!window.dapTracesBySession[trace.sessionId]) {
                    window.dapTracesBySession[trace.sessionId] = [];
                }

                if (trace.messageType === 'UPDATE') {
                    const traces = window.dapTracesBySession[trace.sessionId];
                    const lastTrace = traces[traces.length - 1];
                    if (lastTrace && lastTrace.messageType === 'UPDATE') {
                        traces[traces.length - 1] = trace;
                    } else {
                        traces.push(trace);
                    }
                } else {
                    window.dapTracesBySession[trace.sessionId].push(trace);
                }

                if (window.dapTracesBySession[trace.sessionId].length > 200) {
                    window.dapTracesBySession[trace.sessionId] = window.dapTracesBySession[trace.sessionId].slice(-200);
                }

                // Refresh if this session is currently displayed
                if (window.currentDapSessionId === trace.sessionId) {
                    if (typeof window.renderDapTracesForSession === 'function') {
                        window.renderDapTracesForSession(trace.sessionId);
                    }
                }
            } else if (trace.serverId) {
                // Installation trace — store by serverId
                if (!window.dapTracesByServer[trace.serverId]) {
                    window.dapTracesByServer[trace.serverId] = [];
                }

                if (trace.messageType === 'UPDATE') {
                    const traces = window.dapTracesByServer[trace.serverId];
                    const lastTrace = traces[traces.length - 1];
                    if (lastTrace && lastTrace.messageType === 'UPDATE') {
                        traces[traces.length - 1] = trace;
                    } else {
                        traces.push(trace);
                    }
                } else {
                    window.dapTracesByServer[trace.serverId].push(trace);
                }

                if (window.dapTracesByServer[trace.serverId].length > 200) {
                    window.dapTracesByServer[trace.serverId] = window.dapTracesByServer[trace.serverId].slice(-200);
                }

                // Refresh current session if it belongs to this server
                if (window.currentDapSessionId && window.currentDapServerId === trace.serverId) {
                    if (typeof window.renderDapTracesForSession === 'function') {
                        window.renderDapTracesForSession(window.currentDapSessionId);
                    }
                }
            }
        }

        /**
         * Handle MCP trace message from WebSocket.
         */
        function handleMcpTrace(trace) {
            const connectionId = trace.connectionId;
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

            // Keep only last 500 traces per client
            if (mcpTracesByClient[connectionId].length > 500) {
                mcpTracesByClient[connectionId].shift();
            }

            // Refresh MCP console if this trace is for the currently selected client
            if (connectionId === selectedMcpClient) {
                renderMcpConsole();
            }
        }

        /**
         * Handle workspaces update from WebSocket.
         */
        function handleWorkspacesUpdate(newWorkspaces) {
            console.log('WebSocket workspaces update:', newWorkspaces);

            // WorkspaceDTO now only contains mcpClients (LSP servers loaded lazily)
            const mergedWorkspaces = newWorkspaces;

            // Update if data changed OR if this is the first render
            if (!workspacesRendered || JSON.stringify(mergedWorkspaces) !== JSON.stringify(workspaces)) {
                workspaces = mergedWorkspaces;
                window.workspaces = workspaces; // Update global
                workspacesRendered = true;
                console.log('Workspaces updated, rendering...');
                window.renderWorkspaces();

                // If a workspace was selected, re-render its current tab (servers or debuggers)
                if (selectedWorkspace) {
                    console.log('Selected workspace:', selectedWorkspace);
                    const workspace = workspaces.find(w => w.rootUri === selectedWorkspace);
                    console.log('Found workspace:', workspace);
                    if (workspace) {
                        // Re-render current tab (will lazy load if needed)
                        if (typeof window.switchWorkspaceTab === 'function') {
                            window.switchWorkspaceTab(window.currentWorkspaceTab || 'servers');
                        }
                    } else {
                        // Selected workspace no longer exists
                        selectedWorkspace = null;
                        window.selectedWorkspace = null;
                        document.getElementById('servers-list').innerHTML = '<div class="servers-placeholder">No workspaces selected</div>';
                    }
                } else if (workspaces.length > 0 && currentTab === 'workspaces') {
                    // Auto-select first workspace on initial load
                    console.log('Auto-selecting first workspace');
                    selectWorkspace(workspaces[0].rootUri);
                }
            }
        }

        /**
         * Handle MCP clients update from WebSocket.
         */
        function handleMcpClientsUpdate(newClients) {
            // Only update if data actually changed
            if (JSON.stringify(newClients) !== JSON.stringify(mcpClients)) {
                mcpClients = newClients;
                renderMcpClients();
            }
        }

        /**
         * Handle trace level update from WebSocket.
         */
        function handleTraceLevelUpdate(message) {
            const key = message.serverId
                ? message.serverType + '.' + message.serverId
                : message.serverType;
            traceLevels[key] = message.traceLevel;
            window.traceLevels = traceLevels;

            if (message.serverType === 'lsp') {
                // Update workspace LSP trace controls
                if (window.currentServerId === message.serverId) {
                    if (typeof currentTraceLevel !== 'undefined') {
                        currentTraceLevel = message.traceLevel;
                    }
                    TraceRenderer.updateTraceControls('trace', message.traceLevel);
                    if (typeof renderConsole === 'function') renderConsole();
                }
                // Update global LSP server detail combo
                TraceRenderer.updateTraceControls('lsp-server-trace', message.traceLevel);
            } else if (message.serverType === 'mcp') {
                if (typeof mcpTraceLevel !== 'undefined') {
                    mcpTraceLevel = message.traceLevel;
                }
                TraceRenderer.updateTraceControls('mcp-trace', message.traceLevel);
                if (typeof renderMcpConsole === 'function') renderMcpConsole();
            } else if (message.serverType === 'dap' && window.currentDapServerId === message.serverId) {
                TraceRenderer.updateTraceControls('dap-trace', message.traceLevel);
                if (window.currentDapSessionId && typeof renderDapTracesForSession === 'function') {
                    renderDapTracesForSession(window.currentDapSessionId);
                }
            }
        }

        /**
         * Handle server status change from WebSocket.
         */
        function handleServerStatusChanged(event) {
            console.log('Server status changed:', event);

            // Find the workspace
            const workspace = workspaces.find(w => w.rootUri === event.workspaceUri);
            if (!workspace || !workspace.lspServers) {
                return;
            }

            // Find the server that changed
            const changedServer = workspace.lspServers.find(s => s.id === event.serverId);
            if (!changedServer) {
                return;
            }

            // Update the server's status and progress info
            changedServer.status = event.newStatus;
            changedServer.statusMessage = event.statusMessage;
            changedServer.installProgress = event.installProgress;
            changedServer.isReady = event.isReady;

            // If this server is a parent, update all its extensions
            const extensions = workspace.lspServers.filter(s => s.parentServerId === event.serverId);
            for (const ext of extensions) {
                ext.status = event.newStatus;
                ext.isReady = event.isReady;
                ext.statusMessage = event.statusMessage;
                ext.installProgress = event.installProgress;
                ext.pid = changedServer.pid;
                ext.command = changedServer.command;
            }

            // Only update DOM if this workspace is selected
            if (selectedWorkspace === event.workspaceUri) {
                // Update the status badge for the changed server
                updateServerStatusBadge(event.serverId, changedServer);

                // Update status badges for all extensions of this server
                for (const ext of extensions) {
                    updateServerStatusBadge(ext.id, ext);
                }

                // Update the detail panel status badge if this server is selected
                if (selectedServer && selectedServer.id === event.serverId) {
                    updateDetailPanelStatusBadge(changedServer);
                }
            }
        }

        function handleServerEnabledChanged(event) {
            const enabled = event.enabled;
            const serverId = event.serverId;

            // Update cached config
            if (window.lspConfigs && window.lspConfigs[serverId]) {
                window.lspConfigs[serverId].enabled = enabled;
            }
            if (window.dapConfigs && window.dapConfigs[serverId]) {
                window.dapConfigs[serverId].enabled = enabled;
            }

            // Update workspace server data
            for (const ws of workspaces) {
                if (ws.lspServers) {
                    const srv = ws.lspServers.find(s => s.id === serverId);
                    if (srv) srv.enabled = enabled;
                }
            }

            // Update DOM (LSP uses data-server-id, DAP uses data-dap-server)
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

        /**
         * Update the status badge of a specific server in the DOM without re-rendering the entire list.
         */
        function updateServerStatusBadge(serverId, server) {
            const serverElement = document.querySelector(`.server-item[data-server-id="${serverId}"]`);
            if (!serverElement) {
                return;
            }

            // Update status badge
            const statusBadgeContainer = serverElement.querySelector('.server-status-badge-container');
            if (statusBadgeContainer) {
                const statusClass = formatStatusClass(server.status, server.isReady);
                const label = formatStatusLabel(server.status, server.externalInstance);
                statusBadgeContainer.innerHTML = `<span class="status-badge ${statusClass}">${label}</span>`;
            }

            // Update action buttons based on new status
            const actionsContainer = serverElement.querySelector('.server-actions');
            if (actionsContainer && !server.isExtension) {
                const isExternal = server.externalInstance != null &&
                                   (server.status === 'CONNECTED_TO_IDE' || server.status === 'CONNECTING_TO_IDE');
                let actions = '';
                if (isExternal) {
                    actions = `<button class="server-action-btn server-action-disconnect"
                                      onclick='event.stopPropagation(); disconnectFromIdeAction("${serverId}")'
                                      title="Disconnect from IDE">⏏</button>`;
                } else if (server.status === 'RUNNING' || server.status === 'STARTING') {
                    actions = `<button class="server-action-btn" onclick='event.stopPropagation(); restartServerAction("${serverId}")' title="Restart">↻</button>
                               <button class="server-action-btn" onclick='event.stopPropagation(); stopServerAction("${serverId}")' title="Stop">■</button>`;
                } else if (server.status === 'STOPPED' || server.status === 'START_FAILED' || server.status === 'INSTALL_FAILED' || server.status === 'ERROR') {
                    actions = `<button class="server-action-btn" onclick='event.stopPropagation(); startManagedServerAction("${serverId}")' title="Start MCP-managed server">▶</button>
                               <button class="server-action-btn" onclick='event.stopPropagation(); connectToIdeAction("${serverId}")' title="Try to connect to IDE instance">🔗</button>`;
                }
                actionsContainer.innerHTML = actions;
            }
        }

        /**
         * Update the progress section in the detail panel when a server status changes.
         */
        function updateDetailPanelStatusBadge(server) {
            const progressElement = document.getElementById('server-detail-progress');

            if (!progressElement) {
                return; // Detail panel not showing
            }

            const statusClass = server.status === 'RUNNING' && !server.isReady ? 'status-running-not-ready' : 'status-' + server.status.toLowerCase();
            const label = formatStatusLabel(server.status, server.externalInstance);

            // Show progress if installing/starting with progress
            if ((server.status === 'INSTALLING' || server.status === 'STARTING') && server.installProgress != null) {
                const progressPercent = server.installProgress * 100;
                const message = server.statusMessage || null;
                progressElement.innerHTML = renderProgressBadge(label, statusClass, progressPercent, message);
                progressElement.style.display = 'block';
                progressElement.style.padding = '0.5rem 1rem';
                progressElement.style.background = 'var(--bg-card)';
                progressElement.style.borderBottom = '1px solid var(--border-progress)';
            } else {
                // Hide progress section
                progressElement.innerHTML = '';
                progressElement.style.display = 'none';
            }
        }

        function switchTab(tab, element, options = {}) {
            currentTab = tab;
            window.currentTab = tab; // Update global reference

            // Clear DAP session ID when leaving DAP tab
            if (tab !== 'dap-servers') {
                window.currentDapSessionId = null;
            }

            // Close search box when switching tabs
            const searchBox = document.getElementById('search-box');
            if (searchBox) {
                searchBox.classList.remove('visible');
                if (typeof clearHighlights === 'function') {
                    clearHighlights();
                }
            }

            // Update tab UI
            document.querySelectorAll('.tab').forEach(t => t.classList.remove('active'));
            if (element) {
                element.classList.add('active');
            } else {
                // If no element provided, find it by checking onclick attribute
                const tabs = document.querySelectorAll('.tab');
                tabs.forEach(t => {
                    const onclickAttr = t.getAttribute('onclick');
                    if (onclickAttr && onclickAttr.includes(`'${tab}'`)) {
                        t.classList.add('active');
                    }
                });
            }

            // Show/hide content and adjust layout
            const appContainer = document.querySelector('.app-container');
            const serversColumn = document.querySelector('.servers-sidebar');
            const consoleColumn = document.querySelector('.console-container');

            if (tab === 'workspaces') {
                document.getElementById('workspaces-list').style.display = 'block';
                document.getElementById('lsp-servers-list').style.display = 'none';
                document.getElementById('dap-servers-list').style.display = 'none';
                document.getElementById('extensions-container').style.display = 'none';
                document.getElementById('mcp-traces-list').style.display = 'none';
                serversColumn.style.display = 'block';
                consoleColumn.style.display = 'flex';
                // 3 columns layout: workspaces | servers | console
                appContainer.style.gridTemplateColumns = '400px 300px 1fr';
                consoleColumn.style.gridColumn = '3';

                // Refresh the view: reload servers list and console for selected workspace
                // (workspace data comes via WebSocket)
                if (selectedWorkspace) {
                    loadServers(selectedWorkspace);
                    if (selectedServer) {
                        loadConsole(selectedServer);
                    } else {
                        // No server selected, show placeholder
                        document.getElementById('console-area').innerHTML = `
                            <div class="placeholder">
                                ← Select a workspace and LSP server to view console
                            </div>
                        `;
                        updateSearchBoxVisibility(false);
                    }
                } else {
                    // No workspace selected, show placeholder
                    document.getElementById('servers-list').innerHTML = '<div class="servers-placeholder">No workspaces selected</div>';
                    document.getElementById('console-area').innerHTML = `
                        <div class="placeholder">
                            ← Select a workspace and LSP server to view console
                        </div>
                    `;
                    updateSearchBoxVisibility(false);
                }
            } else if (tab === 'lsp-servers') {
                document.getElementById('workspaces-list').style.display = 'none';
                document.getElementById('lsp-servers-list').style.display = '';
                document.getElementById('dap-servers-list').style.display = 'none';
                document.getElementById('extensions-container').style.display = 'none';
                document.getElementById('mcp-traces-list').style.display = 'none';
                serversColumn.style.display = 'none';
                consoleColumn.style.display = 'flex';
                // 2 columns layout: servers | console
                appContainer.style.gridTemplateColumns = '400px 1fr';
                consoleColumn.style.gridColumn = '2';

                // Always reload servers when switching to this tab
                // Pass serverIdToSelect if provided in options
                loadAllLspServers(options.serverId);
                // Search box will be shown/hidden by loadAllLspServers -> selectServer -> loadConsole
            } else if (tab === 'dap-servers') {
                document.getElementById('workspaces-list').style.display = 'none';
                document.getElementById('lsp-servers-list').style.display = 'none';
                document.getElementById('dap-servers-list').style.display = '';
                document.getElementById('extensions-container').style.display = 'none';
                document.getElementById('mcp-traces-list').style.display = 'none';
                serversColumn.style.display = 'none';
                consoleColumn.style.display = 'flex';
                // 2 columns layout: servers | console
                appContainer.style.gridTemplateColumns = '400px 1fr';
                consoleColumn.style.gridColumn = '2';

                // Always reload DAP servers when switching to this tab
                // Pass serverIdToSelect if provided in options
                loadAllDapServers(options.serverId);
                // DAP now supports search via TraceRenderer
                // Search box visibility will be updated when session detail is shown
            } else if (tab === 'extensions') {
                document.getElementById('workspaces-list').style.display = 'none';
                document.getElementById('lsp-servers-list').style.display = 'none';
                document.getElementById('dap-servers-list').style.display = 'none';
                document.getElementById('extensions-container').style.display = 'flex';
                document.getElementById('mcp-traces-list').style.display = 'none';
                serversColumn.style.display = 'none';
                consoleColumn.style.display = 'flex';
                appContainer.style.gridTemplateColumns = '400px 1fr';
                consoleColumn.style.gridColumn = '2';

                loadAllExtensions();
                updateSearchBoxVisibility(false);
            } else if (tab === 'mcp-traces') {
                document.getElementById('workspaces-list').style.display = 'none';
                document.getElementById('lsp-servers-list').style.display = 'none';
                document.getElementById('dap-servers-list').style.display = 'none';
                document.getElementById('extensions-container').style.display = 'none';
                document.getElementById('mcp-traces-list').style.display = 'block';
                serversColumn.style.display = 'none';
                consoleColumn.style.display = 'flex';
                // 2 columns layout: mcp info | console
                appContainer.style.gridTemplateColumns = '400px 1fr';
                consoleColumn.style.gridColumn = '2';

                // MCP clients data comes via WebSocket
                // Auto-select first client or previously selected client
                if (mcpClients.length > 0) {
                    if (selectedMcpClient && mcpClients.find(c => c.id === selectedMcpClient)) {
                        // Re-select and refresh
                        loadMcpConsole(selectedMcpClient);
                        updateSearchBoxVisibility(true); // MCP traces support search
                    } else {
                        // Select first client
                        selectMcpClient(mcpClients[0].id);
                        updateSearchBoxVisibility(true); // MCP traces support search
                    }
                } else {
                    // No clients, show placeholder
                    loadMcpTracesConsole();
                    updateSearchBoxVisibility(false);
                }
            }
        }

        // Expose switchTab globally for diagram navigation
        window.switchTab = switchTab;

        // ========== LSP Servers Tab ==========
        // (Code moved to admin-lsp.js)


        // ========== Debuggers Tab ==========
        // (Code moved to admin-dap.js)

        // Initialize: load LSP and DAP configs first, then connect WebSocket
        // DAP sessions are loaded lazily when clicking "Debuggers" tab
        (async function init() {
            await loadLspConfigs();
            await loadDapConfigs();
            connectAdminWebSocket();

            // Register keyboard shortcuts for all consoles (LSP, MCP, DAP)
            KeyboardShortcuts.register({
                getActiveConsole: () => {
                    // DAP console (highest priority - active when detail view open)
                    const dapTracesContainer = document.getElementById(`dap-traces-container-${window.currentDapSessionId}`);
                    if (dapTracesContainer && window.currentDapSessionId) {
                        return {
                            type: 'dap',
                            containerId: `dap-traces-container-${window.currentDapSessionId}`,
                            data: [
                                ...(window.currentDapServerId && window.dapTracesByServer?.[window.currentDapServerId] || []),
                                ...(window.dapTracesBySession?.[window.currentDapSessionId] || [])
                            ]
                        };
                    }

                    // LSP console (workspace tab with selected server)
                    const consoleOutput = document.getElementById('console-output');
                    if (consoleOutput && selectedServer) {
                        return {
                            type: 'lsp',
                            containerId: 'console-output',
                            data: tracesByServer[window.currentServerId] || []
                        };
                    }

                    // MCP console (mcp-traces tab)
                    const mcpConsoleOutput = document.getElementById('mcp-console-output');
                    if (mcpConsoleOutput && currentTab === 'mcp-traces' && typeof selectedMcpClient !== 'undefined') {
                        return {
                            type: 'mcp',
                            containerId: 'mcp-console-output',
                            data: (typeof mcpTracesByClient !== 'undefined' && mcpTracesByClient[selectedMcpClient]) || []
                        };
                    }

                    return null;
                },
                onSearch: () => {
                    // Only allow search in trace consoles (LSP/MCP with server/client selected)
                    const consoleOutput = document.getElementById('console-output');
                    const mcpConsoleOutput = document.getElementById('mcp-console-output');
                    const dapTracesContainer = document.getElementById(`dap-traces-container-${window.currentDapSessionId}`);

                    const hasLspConsole = consoleOutput && selectedServer;
                    const hasMcpConsole = mcpConsoleOutput && currentTab === 'mcp-traces' && typeof selectedMcpClient !== 'undefined';
                    const hasDapConsole = dapTracesContainer && window.currentDapSessionId;

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
                    // Delegate to TraceRenderer.closeSearch which handles re-rendering
                    if (window.closeSearch) {
                        window.closeSearch();
                    }
                }
            });
        })();
