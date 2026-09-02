/**
 * Admin UI - DAP (Debug Adapter Protocol) Management
 *
 * Handles DAP session creation, launching, and management
 */

import { state, updateSearchBoxVisibility, ensureDapConfigs, ensureDapConfigDetail, isOnDebuggersTab } from './shared-state.js';
import {
    confirmAction, showAlert, renderLoadingPlaceholder, renderDocumentSelector, renderRuntimeSection, renderExtensionSection, runServerInstaller,
    switchServerTabs, toggleServerEnabled, changeServerTraceLevel, buildServerSettingsHTML,
    selectListItem, buildInstallOutputHTML, buildInstallerControlsHTML,
    getInstallStatusBadge, renderServerNameHeader, restoreInstallOutput
} from './shared-ui.js';
import { formatContributionsSection } from './shared-contributions.js';
import { renderServerDiagram } from './diagram.js';
import { formatErrorWithFolding } from './error-formatter.js';
import { LanguageFilter } from './language-filter.js';
import {
    renderTraceControls, updateTraceControls, renderTracesInContainer,
    getCurrentSearchQuery, toggleAllTraces, clearHighlights, initTraceContainer
} from './trace-renderer.js';
import { registerActions } from './event-delegation.js';
import { showToast } from './toast.js';

let selectDapSessionByServerIdCallback = null;
export function setSelectDapSessionByServerIdCallback(cb) { selectDapSessionByServerIdCallback = cb; }

let refreshWorkspaceServersFn = null;
export function setRefreshWorkspaceServersFn(fn) { refreshWorkspaceServersFn = fn; }


/**
 * Create a new test session for a DAP server.
 * Called from the workspace Debuggers tab.
 */
export async function createNewTestSession(dapServerId) {
    try {
        // Get current workspace URI from admin.js
        const workspaceUri = state.selectedWorkspace;
        if (!workspaceUri) {
            showAlert('No Workspace Selected', 'Please select a workspace first.');
            return;
        }

        // Create the session
        const response = await fetch('/api/admin/dap/sessions', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                workspaceUri: workspaceUri,
                dapServerId: dapServerId,
                sessionName: 'Test Session'
            })
        });

        if (!response.ok) {
            const errorText = await response.text();

            // Try to parse JSON error
            let errorMessage = errorText;
            try {
                const errorJson = JSON.parse(errorText);
                errorMessage = errorJson.error || errorJson.message || errorText;
            } catch (e) {
                // If HTML error page, try to extract the error message
                if (errorText.includes('<html')) {
                    const match = errorText.match(/<h1[^>]*>(.*?)<\/h1>/i) ||
                                  errorText.match(/<title>(.*?)<\/title>/i);
                    if (match) {
                        errorMessage = match[1].replace(/&quot;/g, '"').replace(/&lt;/g, '<').replace(/&gt;/g, '>');
                    } else {
                        errorMessage = 'Server error (see console for details)';
                        console.error('Full error HTML:', errorText);
                    }
                }
            }

            throw new Error(errorMessage);
        }

        const session = await response.json();

        // Add session to DOM immediately (don't wait for WebSocket)
        await addSessionToDOM({
            sessionId: session.sessionId,
            workspaceUri: workspaceUri,
            eventType: 'CREATED'
        });

        // Show launch config form in console
        showLaunchConfigForm(session, dapServerId);

    } catch (error) {
        console.error('Error creating test session:', error);

        // Show error in console
        const consoleArea = document.getElementById('console-area');
        consoleArea.innerHTML = `
            <div class="p-lg">
                <h3 class="text-error">Failed to Create Session</h3>
                <pre class="text-error bg-card p-lg rounded-sm font-mono text-pre-wrap">${error.message}</pre>
            </div>
        `;
    }
}

/**
 * Format SessionActor enum to readable label.
 */
function formatSessionActor(actor) {
    if (!actor) return '-';
    switch (actor) {
        case 'AGENT':
            return '\u{1F916} Agent';
        case 'USER':
            return '\u{1F464} User';
        default:
            return actor;
    }
}

/**
 * Format ISO-8601 timestamp to readable format.
 */
function formatTimestamp(isoString) {
    if (!isoString) return '';
    try {
        const date = new Date(isoString);
        return date.toLocaleString();
    } catch (e) {
        return isoString;
    }
}

/**
 * Show the launch configuration form in the console area.
 */
function showLaunchConfigForm(session, dapServerId) {
    const consoleArea = document.getElementById('console-area');
    const sessionId = session.sessionId;

    // Store session ID and server ID for later use
    state.currentDapSessionId = sessionId;
    state.currentDapServerId = dapServerId || session.serverId || session.dapServerId;

    // Check if session div already exists
    let sessionDiv = document.getElementById(`dap-session-${sessionId}`);
    if (sessionDiv) {
        // Session already exists, just show it
        showSessionDiv(sessionId);
        return;
    }

    // Use launchConfiguration from session if available, otherwise empty
    const defaultConfig = session.launchConfiguration || {};

    // Get session state info (same logic as session list)
    const { statusText, statusClass } = getSessionStateInfo(session);

    const sessionHTML = `
        <div class="p-lg d-flex flex-column overflow-hidden" style="height: 100%;">
            <div class="mb-lg">
                <div class="d-flex align-center gap-sm mb-sm">
                    <h3 class="text-primary mt-0 mb-0">${session.sessionName || 'New Debug Session'}</h3>
                    <span id="dap-session-status-${sessionId}" class="session-server-status status-badge status-badge-compact ${statusClass}">${statusText}</span>
                </div>
                <p class="text-secondary font-md mt-0 mb-0">Server: ${session.serverId || session.dapServerId || dapServerId}</p>
                <p class="text-dimmed font-sm font-mono mt-0 mb-0">Session ID: ${sessionId}</p>
                ${session.createdBy ? `<p class="text-dimmed font-sm mt-0 mb-0">Created by: <span class="session-created-by">${formatSessionActor(session.createdBy)}</span>${session.createdAt ? ` at ${formatTimestamp(session.createdAt)}` : ''}</p>` : ''}
                ${session.launchedBy ? `<p class="text-dimmed font-sm mt-0 mb-0">Launched by: <span class="session-launched-by">${formatSessionActor(session.launchedBy)}</span>${session.launchedAt ? ` at ${formatTimestamp(session.launchedAt)}` : ''}</p>` : '<p class="text-dimmed font-sm mt-0 mb-0">Launched by: <span class="session-launched-by">-</span></p>'}
            </div>

            <div class="mb-lg">
                <div class="d-flex align-center gap-sm mb-sm">
                    <label class="text-primary font-medium">Launch Configuration</label>
                    <div class="d-flex gap-0">
                        <button
                            id="dap-launch-btn-${sessionId}"
                            class="dap-toolbar-btn dap-toolbar-btn-run"
                            data-action="launchDapSession" data-session-id="${session.sessionId}"
                            title="Run (without debugging)">
                            ▶
                        </button>
                        <button
                            id="dap-debug-btn-${sessionId}"
                            class="dap-toolbar-btn dap-toolbar-btn-debug"
                            data-action="debugDapSession" data-session-id="${session.sessionId}"
                            title="Debug (with breakpoints)">
                            🐛
                        </button>
                        <button
                            id="dap-stop-btn-${sessionId}"
                            class="dap-toolbar-btn dap-toolbar-btn-stop"
                            data-action="stopDapSession" data-session-id="${session.sessionId}"
                            disabled
                            title="Stop debug session">
                            ⏹
                        </button>
                        <button
                            class="dap-toolbar-btn dap-toolbar-btn-delete"
                            data-action="deleteDapSession" data-session-id="${session.sessionId}"
                            title="Delete session">
                            🗑️
                        </button>
                    </div>
                    <select
                        id="launch-template-selector-${sessionId}"
                        class="select-field font-md"
                        data-action="applyLaunchTemplate" data-session-id="${session.sessionId}"
                        style="padding: 0.2rem 0.4rem;">
                        <option value="">Select template...</option>
                    </select>
                </div>
                <textarea
                    id="launch-config-editor-${sessionId}"
                    class="input-field w-100 p-md bg-card text-code rounded-sm font-mono font-base"
                    style="border: 1px solid var(--border-subtle); resize: vertical; height: 150px;"
                >${JSON.stringify(defaultConfig, null, 2)}</textarea>
            </div>

            <div class="flex-1 d-flex flex-column min-h-0">
                <div class="d-flex justify-between align-center mb-sm">
                    <label class="text-primary font-medium">Console:</label>
                    <div class="console-controls">
                        ${renderTraceControls('dap-trace-' + sessionId, (state.traceLevels && state.traceLevels['dap.' + dapServerId]) || 'off', 'changeDapTraceLevel', {
                            foldAction: 'toggleAllDapTraces',
                            clearAction: 'clearDapConsole'
                        })}
                    </div>
                </div>
                <div id="dap-traces-container-${sessionId}" class="flex-1 bg-card p-sm rounded-sm font-mono font-md overflow-auto">
                    <div class="text-dimmed">Ready. Click ▶ to launch.</div>
                </div>
            </div>
        </div>
    `;

    // Hide all existing children (session divs, server details, placeholders)
    Array.from(consoleArea.children).forEach(child => child.style.display = 'none');

    sessionDiv = document.createElement('div');
    sessionDiv.id = `dap-session-${sessionId}`;
    sessionDiv.style.display = 'block';
    sessionDiv.style.height = '100%';
    sessionDiv.innerHTML = sessionHTML;
    consoleArea.appendChild(sessionDiv);

    // Load launch configuration templates for this DAP server
    if (dapServerId) {
        loadLaunchConfigurationTemplates(sessionId, dapServerId);
    }

    // Load existing traces for this session (renderDapTracesForSession will be called by WebSocket when traces arrive)
    renderDapTracesForSession(sessionId);

    // Initialize trace container event delegation (mousedown/mouseup for fold/unfold)
    initTraceContainer(`dap-traces-container-${sessionId}`);

    // Initialize trace level from workspace-resolved settings
    const dapSession = state.dapSessions?.find(s => s.sessionId === sessionId);
    loadWorkspaceDapTraceLevel(dapServerId, dapSession?.workspaceUri);

    // Initialize button states based on session state
    const debugBtn = document.getElementById(`dap-debug-btn-${sessionId}`);
    const launchBtn = document.getElementById(`dap-launch-btn-${sessionId}`);
    const stopBtn = document.getElementById(`dap-stop-btn-${sessionId}`);

    if (debugBtn && launchBtn && stopBtn && session.state) {
        const { canLaunch, canStop } = getSessionButtonStates(session.state);

        debugBtn.disabled = !canLaunch;
        debugBtn.classList.toggle('is-disabled', !canLaunch);

        launchBtn.disabled = !canLaunch;
        launchBtn.classList.toggle('is-disabled', !canLaunch);

        stopBtn.disabled = !canStop;
        stopBtn.classList.toggle('is-disabled', !canStop);
    }

    // Clear DAP server selection
    selectedDapServer = null;

    // Highlight the session in the workspace list
    document.querySelectorAll('.dap-session-item').forEach(el => el.classList.remove('active'));
    const selectedElement = document.querySelector(`[data-session-id="${sessionId}"]`);
    if (selectedElement) {
        selectedElement.classList.add('active');
    }
}

/**
 * Show a specific session div and hide others.
 */
function showSessionDiv(sessionId) {
    state.currentDapSessionId = sessionId;
    selectedDapServer = null; // Clear DAP server selection

    const consoleArea = document.getElementById('console-area');

    // Check if session div exists, if not create it
    let sessionDiv = document.getElementById(`dap-session-${sessionId}`);
    if (!sessionDiv) {
        // Session div doesn't exist, need to create it
        showLaunchConfigForm({ sessionId: sessionId }, null);
        return;
    }

    // Hide all children (session divs, server details, placeholders)
    Array.from(consoleArea.children).forEach(child => child.style.display = 'none');

    // Show the selected session
    sessionDiv.style.display = 'block';

    // Render traces for this session
    renderDapTracesForSession(sessionId);
}


/**
 * Debug a DAP session with the provided configuration (with breakpoints).
 */
export async function debugDapSession(sessionId) {
    await launchDapSessionInternal(sessionId, true); // debugMode = true
}

/**
 * Launch a DAP session with the provided configuration (without debugging).
 */
export async function launchDapSession(sessionId) {
    await launchDapSessionInternal(sessionId, false); // debugMode = false
}

/**
 * Internal function to launch/debug a DAP session.
 */
async function launchDapSessionInternal(sessionId, debugMode) {
    // Disable buttons immediately to prevent double-click
    disableSessionButtons(sessionId);

    try {
        // Try to get config from editor (if in detail view), otherwise use stored config
        const editor = document.getElementById(`launch-config-editor-${sessionId}`);
        let launchConfig;

        if (editor) {
            // Config from editor (detail view)
            const configText = editor.value;
            launchConfig = JSON.parse(configText);
        } else {
            // Config from session cache (list view button click)
            const session = state.dapSessions?.find(s => s.sessionId === sessionId);
            if (!session || !session.launchConfiguration) {
                throw new Error('No launch configuration found. Please open the session detail first.');
            }
            launchConfig = session.launchConfiguration;
        }

        // Pass debugMode as query parameter (not in config)
        const response = await fetch(`/api/admin/dap/sessions/${sessionId}/launch?debugMode=${debugMode}`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(launchConfig)
        });

        if (!response.ok) {
            const error = await response.text();
            throw new Error(error || 'Launch failed');
        }

        const result = await response.json();

        // Traces will appear in the dap-traces-container below
        console.log(`${debugMode ? 'Debug' : 'Run'} result:`, result);

        // WebSocket will automatically notify of state changes, no manual refresh needed

    } catch (error) {
        console.error(`Error ${debugMode ? 'debugging' : 'launching'} session:`, error);

        // Parse error response
        let errorData = null;
        try {
            errorData = JSON.parse(error.message);
        } catch (e) {
            // Not JSON, use as-is
            errorData = { message: error.message, type: 'Error', stackTrace: '' };
        }

        // Add error to traces (don't replace existing traces)
        const tracesContainer = document.getElementById(`dap-traces-container-${sessionId}`);
        if (tracesContainer) {
            const errorHtml = formatErrorWithFolding(`Failed to ${debugMode ? 'Debug' : 'Launch'}`, errorData);
            tracesContainer.insertAdjacentHTML('beforeend', errorHtml);
            // Scroll to bottom to show the error
            tracesContainer.scrollTop = tracesContainer.scrollHeight;
        }
    }
}

/**
 * Stop a running DAP session.
 */
export async function stopDapSession(sessionId) {
    // Disable buttons immediately to prevent double-click
    disableSessionButtons(sessionId);

    try {
        const response = await fetch(`/api/admin/dap/sessions/${sessionId}/stop`, {
            method: 'POST'
        });

        if (!response.ok) {
            throw new Error('Failed to stop session');
        }

        console.log('Stop request sent for session:', sessionId);

    } catch (error) {
        console.error('Error stopping session:', error);
        alert(`Failed to stop session: ${error.message}`);
    }
}

/**
 * Delete a DAP session.
 */
export async function deleteDapSession(sessionId) {
    const confirmed = await confirmAction(
        'Delete Debug Session',
        'Delete this test session?\n\nThis action cannot be undone.',
        'Delete',
        true
    );

    if (!confirmed) return;

    try {
        const response = await fetch(`/api/admin/dap/sessions/${sessionId}`, {
            method: 'DELETE'
        });

        if (!response.ok) {
            throw new Error('Failed to delete session');
        }

        // Remove session from cache immediately so STATE_CHANGED events are ignored
        if (state.dapSessions) {
            state.dapSessions = state.dapSessions.filter(s => s.sessionId !== sessionId);
        }

        // Remove session from DOM (both workspace list and console area)
        removeSessionFromDOM(sessionId);

        // Show placeholder in console
        const consoleArea = document.getElementById('console-area');
        const remainingSessions = consoleArea.querySelectorAll('[id^="dap-session-"]');
        if (remainingSessions.length === 0) {
            consoleArea.innerHTML = `
                <div class="placeholder">
                    Session deleted
                </div>
            `;
        }

    } catch (error) {
        console.error('Error deleting session:', error);
        showAlert('Failed to Delete Session', error.message);
    }
}

/**
 * Select a DAP session (called from workspace view).
 */
export async function selectDapSession(sessionId) {
    console.log('Selected DAP session:', sessionId);

    // Remove 'active' class from all sessions
    document.querySelectorAll('.dap-session-item').forEach(el => el.classList.remove('active'));

    // Add 'active' class to selected session
    const selectedElement = document.querySelector(`[data-session-id="${sessionId}"]`);
    if (selectedElement) {
        selectedElement.classList.add('active');
    }

    // Clear search when switching sessions
    const searchBox = document.getElementById('search-box');
    const searchInput = document.getElementById('search-input');
    if (searchBox && searchInput) {
        searchBox.classList.remove('visible');
        searchInput.value = '';
        clearHighlights();
    }

    // Show search box for DAP traces
    updateSearchBoxVisibility(true);

    // Check if session div already exists
    const sessionDiv = document.getElementById(`dap-session-${sessionId}`);
    if (sessionDiv) {
        // Sync status from state in case it changed while off-tab
        const cachedSession = state.dapSessions?.find(s => s.sessionId === sessionId);
        if (cachedSession) {
            updateSessionStateInDOM(sessionId, cachedSession.state, cachedSession.debugMode);
        }
        showSessionDiv(sessionId);
        return;
    }

    // Session div doesn't exist yet — use cached state first, fetch only as fallback
    let session = state.dapSessions?.find(s => s.sessionId === sessionId);
    if (!session) {
        try {
            const response = await fetch(`/api/admin/dap/sessions`);
            if (!response.ok) {
                throw new Error('Failed to fetch DAP sessions');
            }
            const sessions = await response.json();
            session = sessions.find(s => s.sessionId === sessionId);
        } catch (error) {
            console.error('Error loading session:', error);
            showAlert('Failed to Load Session', error.message);
            return;
        }
    }

    if (!session) {
        console.error('Session not found:', sessionId);
        return;
    }

    showLaunchConfigForm(session, session.serverId);
}

let currentDapTraceLevel = 'off';

function getDapTraceLevel() {
    return currentDapTraceLevel;
}

async function loadWorkspaceDapTraceLevel(serverId, workspaceUri) {
    if (!serverId || !workspaceUri) {
        currentDapTraceLevel = (state.traceLevels && state.traceLevels['dap.' + serverId]) || 'off';
        return;
    }
    try {
        const response = await fetch(`/api/admin/workspaces/${encodeURIComponent(workspaceUri)}/dap-servers/${encodeURIComponent(serverId)}/settings`);
        if (response.ok) {
            const settings = await response.json();
            const traceSetting = settings.find(s => s.key === 'trace');
            if (traceSetting) {
                currentDapTraceLevel = traceSetting.currentValue || 'off';
                if (state.currentDapSessionId) {
                    updateTraceControls('dap-trace-' + state.currentDapSessionId, currentDapTraceLevel);
                }
                if (state.currentDapSessionId) {
                    renderDapTracesForSession(state.currentDapSessionId);
                }
                return;
            }
        }
    } catch (e) {
        console.error('Failed to load workspace DAP trace level:', e);
    }
    currentDapTraceLevel = (state.traceLevels && state.traceLevels['dap.' + serverId]) || 'off';
}

/**
 * Refresh traces display for the current session (called by handleDapTrace).
 */
export function renderDapTracesForSession(sessionId) {
    if (!state.currentDapSessionId || state.currentDapSessionId !== sessionId) {
        return;
    }

    const containerId = `dap-traces-container-${sessionId}`;
    const serverId = state.currentDapServerId;
    const serverTraces = (serverId && state.dapTracesByServer?.[serverId]) || [];
    const sessionTraces = state.dapTracesBySession?.[sessionId] || [];
    const traces = [...serverTraces, ...sessionTraces];

    renderTracesInContainer(containerId, traces, getDapTraceLevel(), getCurrentSearchQuery(), undefined, 'dap-trace-' + sessionId);
}

/**
 * ============================================
 * GLOBAL DAP SERVERS (Debuggers tab)
 * ============================================
 */

let selectedDapServer = null;
let currentDapServerTab = 'overview'; // overview, contributions, settings
let dapServerConfigs = {};
let dapLanguageFilter = null;

/**
 * Load all global DAP servers.
 */
function renderDapServerItem(server) {
    const isActive = selectedDapServer === server.id ? 'active' : '';
    const disabledClass = !server.enabled ? 'server-disabled' : '';
    return `
        <div class="server-item ${isActive} ${disabledClass}" data-action="showDapServerDetails" data-server-id="${server.id}">
            ${renderServerNameHeader(server, { icon: '🐛', toggleAction: 'toggleDapServerEnabled' })}
            <div class="server-id">${server.id}</div>
        </div>
    `;
}

export async function loadAllDapServers(serverIdToSelect) {
    try {
        const container = document.getElementById('dap-servers-list');
        if (!container) {
            console.error('dap-servers-list container not found');
            return;
        }

        if (!state.dapConfigs) {
            container.innerHTML = renderLoadingPlaceholder();
        }

        await ensureDapConfigs();
        dapServerConfigs = state.dapConfigs || {};
        const dapServers = Object.values(dapServerConfigs).sort((a, b) => (a.name || '').localeCompare(b.name || ''));

        if (!dapLanguageFilter) {
            dapLanguageFilter = new LanguageFilter(container, () => dapServerConfigs, () => loadAllDapServers(selectedDapServer));
        }

        if (dapServers.length === 0) {
            dapLanguageFilter.getItemsContainer().innerHTML = '<div class="servers-placeholder">No debuggers configured</div>';
            return;
        }

        const filteredServers = dapLanguageFilter.filterServers(dapServers);

        dapLanguageFilter.getItemsContainer().innerHTML = filteredServers.map(server =>
            renderDapServerItem(server)
        ).join('');

        if (filteredServers.length > 0) {
            let serverToShow;
            if (serverIdToSelect && filteredServers.find(s => s.id === serverIdToSelect)) {
                serverToShow = serverIdToSelect;
            } else if (selectedDapServer && filteredServers.find(s => s.id === selectedDapServer)) {
                serverToShow = selectedDapServer;
            } else {
                serverToShow = filteredServers[0].id;
            }
            showDapServerDetails(serverToShow, true);
        }
    } catch (error) {
        console.error('Failed to load DAP servers:', error);
    }
}

/**
 * Show details for a global DAP server with Overview/Contributions/Settings tabs.
 */
export async function showDapServerDetails(serverId, scroll) {
    const previousServer = selectedDapServer;
    selectedDapServer = serverId;
    state.currentDapServerId = serverId;

    state.currentDapSessionId = null;
    updateSearchBoxVisibility(false);

    if (dapLanguageFilter) {
        selectListItem(dapLanguageFilter.getItemsContainer(),
            '.server-item[data-server-id', previousServer, serverId, scroll);
    }

    const contentArea = document.querySelector('.content-area');
    const consoleColumn = document.querySelector('.console-container');
    consoleColumn.style.display = 'flex';
    contentArea.style.gridTemplateColumns = '400px 1fr';
    consoleColumn.style.gridColumn = '2';

    const server = dapServerConfigs[serverId];
    if (!server) {
        console.error('DAP server not found:', serverId);
        return;
    }

    renderDapServerDetailsHTML(serverId, server);

    if (!server._detailLoaded) {
        await ensureDapConfigDetail(serverId);
        if (selectedDapServer !== serverId) return;
        const detailSection = document.getElementById('dap-server-detail-section');
        if (detailSection) {
            detailSection.innerHTML = buildDapServerDetailHTML(server);
        }
        const settingsPanel = document.getElementById('dap-server-settings-content');
        if (settingsPanel) {
            settingsPanel.innerHTML = buildDapSettingsHTML(server);
        }
    }

    restoreInstallOutput(serverId, 'server-install-output');
}

function renderDapServerDetailsHTML(serverId, server) {
    const docSelectorHTML = renderDocumentSelector(server.documentSelector);

    const html = `
        <div class="console-header">
            <div class="console-title">
                <span class="server-source-icon">🐛</span>
                ${server.name || server.id}
                <span class="console-install-badge" data-server-id="${server.id}">${getInstallStatusBadge(server)}</span>
            </div>
            <div class="console-tabs">
                <button class="tab-button ${currentDapServerTab === 'overview' ? 'active' : ''}" data-action="switchDapServerTab" data-tab="overview">Overview</button>
                <button class="tab-button ${currentDapServerTab === 'contributions' ? 'active' : ''}" data-action="switchDapServerTab" data-tab="contributions">Contributions</button>
                <button class="tab-button ${currentDapServerTab === 'settings' ? 'active' : ''}" data-action="switchDapServerTab" data-tab="settings">Settings</button>
            </div>
            ${server.hasInstaller ? buildInstallerControlsHTML(server.id, 'installDapServer') : ''}
        </div>
        <div class="tab-content">
            <div id="dap-server-overview-tab" class="tab-panel ${currentDapServerTab === 'overview' ? 'active' : ''}">
                <div class="details-panel text-primary detail-content">
                    <h3 class="text-success mt-0">Debug Adapter Information</h3>
                    <div class="detail-row">
                        <span class="detail-label">Server ID:</span>
                        <span class="detail-value"><code>${server.id}</code></span>
                    </div>
                    <div class="mb-lg">
                        <strong class="text-label">Supported Languages/Files:</strong>
                        ${docSelectorHTML}
                    </div>
                    <div id="dap-server-detail-section">
                        ${server._detailLoaded ? buildDapServerDetailHTML(server) : renderLoadingPlaceholder()}
                    </div>
                </div>
            </div>
            <div id="dap-server-contributions-tab" class="tab-panel ${currentDapServerTab === 'contributions' ? 'active' : ''}">
                <div id="server-diagram-container" class="w-100 bg-card diagram-container"></div>
                <div class="diagram-resizer"></div>
                <div class="details-panel text-primary flex-1 min-h-0 detail-content" id="dap-contributions-content">
                </div>
            </div>
            <div id="dap-server-settings-tab" class="tab-panel ${currentDapServerTab === 'settings' ? 'active' : ''}">
                <div class="details-panel text-primary overflow-auto p-2xl" id="dap-server-settings-content">
                    ${server._detailLoaded ? buildDapSettingsHTML(server) : renderLoadingPlaceholder()}
                </div>
            </div>
        </div>
    `;

    document.getElementById('console-area').innerHTML = html;
}

function buildDapServerDetailHTML(server) {
    return `
        ${server.description ? `
        <div class="detail-row">
            <span class="detail-label">Description:</span>
            <span class="detail-value">${server.description}</span>
        </div>
        ` : ''}

        ${server.url ? `
        <div class="detail-row">
            <span class="detail-label">URL:</span>
            <span class="detail-value"><a href="${server.url}" target="_blank" class="link-accent">${server.url}</a></span>
        </div>
        ` : ''}

        ${renderRuntimeSection(server)}

        ${renderExtensionSection(server)}

        ${server.installDir ? `
        <div class="detail-row">
            <span class="detail-label">Install Path:</span>
            <span class="detail-value"><code>${server.installDir}</code></span>
        </div>
        ` : ''}

        <div class="p-lg bg-panel rounded mt-2xl border-left-success">
            <strong>Note:</strong> Debuggers are started on-demand during debug sessions. They are not automatically started with workspaces.
        </div>
        ${buildInstallOutputHTML()}
    `;
}

/**
 * Switch between DAP server tabs (Overview/Contributions/Settings).
 */
export async function switchDapServerTab(tab) {
    currentDapServerTab = tab;
    switchServerTabs('dap-server', tab);

    if (tab === 'contributions') {
        await refreshDapContributionsTab();
    }
}

async function refreshDapContributionsTab() {
    const serverId = selectedDapServer;
    if (!serverId) return;

    const contributionsPanel = document.getElementById('dap-contributions-content');
    try {
        const response = await fetch(`/api/admin/dap/configs/${serverId}/contributions`);
        const data = await response.json();

        if (contributionsPanel) {
            const contributionsHTML = formatContributionsSection(data);
            contributionsPanel.innerHTML = contributionsHTML || '<p class="detail-value">No contributions</p>';
        }

        const diagramServers = buildDiagramServersFromContributions(serverId, data);
        state.currentDiagramServers = diagramServers;
        state.currentDiagramServerId = serverId;
        setTimeout(() => renderServerDiagram(diagramServers, serverId), 100);
    } catch (error) {
        console.error('Failed to load DAP contributions:', error);
        if (contributionsPanel) {
            contributionsPanel.innerHTML = '<p class="detail-value text-error">Failed to load contributions</p>';
        }
    }
}

function buildDiagramServersFromContributions(serverId, data) {
    const serversMap = new Map();
    serversMap.set(serverId, { id: serverId, contributions: data.contributesTo || {} });
    for (const [contributorId, contribData] of Object.entries(data.contributedBy || {})) {
        serversMap.set(contributorId, { id: contributorId, contributions: { [serverId]: contribData } });
    }
    for (const targetId of Object.keys(data.contributesTo || {})) {
        if (!serversMap.has(targetId)) {
            serversMap.set(targetId, { id: targetId });
        }
    }
    return Array.from(serversMap.values());
}

async function installDapServer(serverId) {
    switchDapServerTab('overview');
    const installUrl = `/api/admin/dap/configs/${serverId}/install`;
    return runServerInstaller(serverId, true, 'server-install-output', installUrl);
}


/**
 * Toggle individual DAP trace item.
 */
// toggleDapTrace now provided by TraceRenderer (via toggleTrace)

/**
 * Toggle folding state for DAP traces.
 */
let dapFoldedState = {};

export function toggleAllDapTraces(sessionId) {
    const container = document.getElementById(`dap-traces-container-${sessionId}`);
    const foldButton = document.getElementById(`dap-trace-${sessionId}-fold-button`);
    const isFolded = dapFoldedState[sessionId] || false;

    // Use toggleAllTraces
    toggleAllTraces(`dap-traces-container-${sessionId}`, isFolded);

    // Update button text and state
    if (isFolded) {
        foldButton.textContent = 'Fold All';
        dapFoldedState[sessionId] = false;
    } else {
        foldButton.textContent = 'Unfold All';
        dapFoldedState[sessionId] = true;
    }
}

/**
 * Clear DAP traces for a session.
 */
export async function clearDapConsole(sessionId) {
    try {
        await fetch('/api/admin/traces/dap', { method: 'DELETE' });
    } catch (e) {
        console.error('Failed to clear DAP traces on server:', e);
    }
    if (state.dapTracesBySession) {
        state.dapTracesBySession[sessionId] = [];
    }
    if (state.dapTracesByServer && state.currentDapServerId) {
        state.dapTracesByServer[state.currentDapServerId] = [];
    }
    renderDapTracesForSession(sessionId);
}

function buildDapSettingsHTML(server) {
    return buildServerSettingsHTML('dap', server, 'updateDapServerSetting');
}

function updateDapServerSetting(serverId, settingKey, value) {
    if (settingKey === 'trace') {
        changeServerTraceLevel('dap', serverId, value);
    }
}

export async function changeDapServerTraceLevel(serverId, level) {
    changeServerTraceLevel('dap', serverId, level);
}

export async function changeDapTraceLevel(sessionId, level) {
    currentDapTraceLevel = level;
    const session = state.dapSessions?.find(s => s.sessionId === sessionId);
    const serverId = session?.serverId || session?.dapServerId || state.currentDapServerId;
    const workspaceUri = session?.workspaceUri;
    if (serverId && workspaceUri) {
        try {
            const response = await fetch(`/api/admin/workspaces/${encodeURIComponent(workspaceUri)}/traces/dap/${encodeURIComponent(serverId)}`, {
                method: 'PUT',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ traceLevel: level })
            });
            if (response.ok) showToast('Settings saved');
        } catch (e) {
            console.error('Failed to save DAP trace level:', e);
        }
    } else if (serverId) {
        changeServerTraceLevel('dap', serverId, level);
    }
    updateTraceControls('dap-trace-' + sessionId, level);
    renderDapTracesForSession(sessionId);
}


/**
 * Handle DAP session update from WebSocket.
 */
export async function onDapSessionUpdate(message) {
    console.log('[DAP] Session update:', message.eventType, message.sessionId, message.newStatus);

    const onDebuggersTab = isOnDebuggersTab();

    // Update only the affected session based on event type
    switch (message.eventType) {
        case 'CREATED':
            if (message.workspaceUri === state.selectedWorkspace) {
                try {
                    const response = await fetch(`/api/admin/dap/sessions`);
                    if (response.ok) {
                        const sessions = await response.json();
                        const newSession = sessions.find(s => s.sessionId === message.sessionId);
                        if (newSession) {
                            if (!state.dapSessions) state.dapSessions = [];
                            if (!state.dapSessions.find(s => s.sessionId === newSession.sessionId)) {
                                state.dapSessions.push(newSession);
                            }

                            if (onDebuggersTab) {
                                // Skip DOM insert if already in DOM (added by createNewTestSession)
                                if (document.querySelector(`[data-session-id="${message.sessionId}"]`)) {
                                    break;
                                }
                                const serverElement = document.querySelector(`[data-dap-server="${newSession.serverId}"]`);
                                if (serverElement) {
                                    serverElement.insertAdjacentHTML('afterend', createSessionHTML(newSession));
                                } else if (refreshWorkspaceServersFn) {
                                    await refreshWorkspaceServersFn();
                                }
                                if (!state.userExplicitlySelectedServer) {
                                    selectDapSession(newSession.sessionId);
                                }
                            }
                        }
                    }
                } catch (error) {
                    console.error('[DAP] Failed to add new session:', error);
                }
            }
            break;

        case 'STATE_CHANGED':
            if (!state.dapSessions) {
                state.dapSessions = [];
            }

            // Ignore events for sessions no longer tracked (already deleted)
            const session = state.dapSessions.find(s => s.sessionId === message.sessionId);
            if (!session) {
                break;
            }

            if (message.debugMode !== null && message.debugMode !== undefined) {
                session.debugMode = message.debugMode;
            }
            if (message.createdBy) session.createdBy = message.createdBy;
            if (message.createdAt) session.createdAt = message.createdAt;
            if (message.launchBy) session.launchedBy = message.launchBy;
            if (message.launchedAt) session.launchedAt = message.launchedAt;
            session.state = message.newStatus;

            if (onDebuggersTab) {
                updateSessionStateInDOM(message.sessionId, message.newStatus, message.debugMode);
                updateSessionDetailInDOM(message.sessionId, message);

                const activeStates = ['RUNNING', 'PAUSED', 'STARTING', 'INSTALLING', 'LAUNCHING', 'ATTACHING'];
                if (!state.userExplicitlySelectedServer && activeStates.includes(message.newStatus)) {
                    selectDapSession(message.sessionId);
                }
            }
            break;

        case 'DELETED': {
            const wasDisplayed = state.currentDapSessionId === message.sessionId;
            const deletedSession = state.dapSessions?.find(s => s.sessionId === message.sessionId);
            const deletedServerId = deletedSession?.serverId || deletedSession?.dapServerId || state.currentDapServerId;

            if (state.dapSessions) {
                state.dapSessions = state.dapSessions.filter(s => s.sessionId !== message.sessionId);
            }

            if (onDebuggersTab) {
                removeSessionFromDOM(message.sessionId);

                if (wasDisplayed) {
                    const remainingSession = document.querySelector('.dap-session-item[data-session-id]');
                    if (remainingSession) {
                        selectDapSession(remainingSession.getAttribute('data-session-id'));
                    } else if (deletedServerId && selectDapSessionByServerIdCallback) {
                        selectDapSessionByServerIdCallback(deletedServerId);
                    } else {
                        const consoleArea = document.getElementById('console-area');
                        if (consoleArea) {
                            consoleArea.innerHTML = '<div class="placeholder">No active debug session</div>';
                        }
                    }
                }
            }
            break;
        }
    }
}

/**
 * Update session detail (createdBy, launchedBy, timestamps) in the DOM.
 */
function updateSessionDetailInDOM(sessionId, message) {
    // Update Created by
    const createdByElement = document.querySelector(`#dap-session-${sessionId} .session-created-by`);
    if (createdByElement && message.createdBy) {
        const createdByText = formatSessionActor(message.createdBy);
        const createdAtText = message.createdAt ? ` at ${formatTimestamp(message.createdAt)}` : '';
        createdByElement.parentElement.innerHTML = `Created by: <span class="session-created-by">${createdByText}</span>${createdAtText}`;
    }

    // Update Launched by
    const launchedByElement = document.querySelector(`#dap-session-${sessionId} .session-launched-by`);
    if (launchedByElement && message.launchBy) {
        const launchedByText = formatSessionActor(message.launchBy);
        const launchedAtText = message.launchedAt ? ` at ${formatTimestamp(message.launchedAt)}` : '';
        launchedByElement.parentElement.innerHTML = `Launched by: <span class="session-launched-by">${launchedByText}</span>${launchedAtText}`;
    }
}

/**
 * Update just the session state in the DOM without reloading everything.
 */
function updateSessionStateInDOM(sessionId, newStatus, debugMode) {
    console.log('[DAP] Updating session in DOM:', sessionId, 'new status:', newStatus, 'debugMode:', debugMode);

    // Update in the dapSessions array
    if (state.dapSessions) {
        const session = state.dapSessions.find(s => s.sessionId === sessionId);
        if (session) {
            session.state = newStatus;
            if (debugMode !== null && debugMode !== undefined) {
                session.debugMode = debugMode;
            }
        }
    }

    // Get session info to calculate display values (use same logic as everywhere)
    let session = state.dapSessions?.find(s => s.sessionId === sessionId);
    if (!session) {
        session = { state: newStatus, debugMode: debugMode };
    }
    const { stateIcon, statusText, statusClass } = getSessionStateInfo(session);

    // Update the session element in the list (left side)
    const sessionElement = document.querySelector(`[data-session-id="${sessionId}"]`);
    if (sessionElement) {
        // Update icon (use stateIcon from getSessionStateInfo)
        const iconElement = sessionElement.querySelector('span:first-child');
        if (iconElement) {
            // Extract emoji from HTML string (e.g., "<span>■</span>" -> "⏹️")
            const tempDiv = document.createElement('div');
            tempDiv.innerHTML = stateIcon;
            iconElement.textContent = tempDiv.textContent;
        }

        // Update status badge
        const statusBadge = sessionElement.querySelector('.status-badge');
        if (statusBadge) {
            statusBadge.className = `status-badge status-badge-compact ${statusClass}`;
            statusBadge.textContent = statusText;
        }

        // Update action buttons in the list
        const runBtn = sessionElement.querySelector('.session-run-btn');
        const debugBtn = sessionElement.querySelector('.session-debug-btn');
        const stopBtn = sessionElement.querySelector('.session-stop-btn');

        const { canLaunch, canStop } = getSessionButtonStates(newStatus);

        if (runBtn) {
            runBtn.disabled = !canLaunch;
            runBtn.classList.toggle('is-disabled', !canLaunch);
        }
        if (debugBtn) {
            debugBtn.disabled = !canLaunch;
            debugBtn.classList.toggle('is-disabled', !canLaunch);
        }
        if (stopBtn) {
            stopBtn.disabled = !canStop;
            stopBtn.classList.toggle('is-disabled', !canStop);
        }
    }

    // Update detail panel (right side) if this session is selected
    const sessionDetailDiv = document.getElementById(`dap-session-${sessionId}`);
    console.log('[DAP] Detail div found:', !!sessionDetailDiv, 'display:', sessionDetailDiv?.style.display);
    if (sessionDetailDiv && sessionDetailDiv.style.display !== 'none') {
        // Update server status in detail panel (use ID selector for more precision)
        const serverStatusEl = document.getElementById(`dap-session-status-${sessionId}`);
        console.log('[DAP] Badge element found:', !!serverStatusEl, 'statusText:', statusText, 'statusClass:', statusClass);
        if (serverStatusEl) {
            serverStatusEl.textContent = statusText;
            serverStatusEl.className = `session-server-status status-badge status-badge-compact ${statusClass}`;
            console.log('[DAP] Updated detail panel status to:', statusText);
        } else {
            console.warn('[DAP] Could not find badge element with ID dap-session-status-' + sessionId);
        }

        // Update button states
        const debugBtn = document.getElementById(`dap-debug-btn-${sessionId}`);
        const launchBtn = document.getElementById(`dap-launch-btn-${sessionId}`);
        const stopBtn = document.getElementById(`dap-stop-btn-${sessionId}`);

        if (debugBtn && launchBtn && stopBtn) {
            const { canLaunch, canStop } = getSessionButtonStates(newStatus);

            debugBtn.disabled = !canLaunch;
            debugBtn.classList.toggle('is-disabled', !canLaunch);

            launchBtn.disabled = !canLaunch;
            launchBtn.classList.toggle('is-disabled', !canLaunch);

            stopBtn.disabled = !canStop;
            stopBtn.classList.toggle('is-disabled', !canStop);
        }
    }
}

/**
 * Add a new session to the DOM.
 */
async function addSessionToDOM(message) {
    try {
        const response = await fetch('/api/admin/dap/sessions');
        if (!response.ok) return;
        const sessions = await response.json();

        const session = sessions.find(s => s.sessionId === message.sessionId);
        if (!session) {
            console.warn('[DAP] Session not found:', message.sessionId);
            return;
        }

        // Check if session already exists in DOM
        if (document.querySelector(`[data-session-id="${message.sessionId}"]`)) {
            return;
        }

        // Find the debugger container in the DOM
        const debuggerContainer = document.querySelector(`[data-dap-server="${session.serverId || session.dapServerId}"]`);
        if (!debuggerContainer) {
            return;
        }

        debuggerContainer.insertAdjacentHTML('afterend', createSessionHTML(session));
    } catch (error) {
        console.error('[DAP] Error adding session to DOM:', error);
    }
}

/**
 * Remove a session from the DOM.
 */
function removeSessionFromDOM(sessionId) {
    // Remove from workspace list
    const sessionElement = document.querySelector(`[data-session-id="${sessionId}"]`);
    if (sessionElement) {
        sessionElement.remove();
        console.log('[DAP] Session removed from workspace list:', sessionId);
    }

    // Remove from console area
    const sessionDiv = document.getElementById(`dap-session-${sessionId}`);
    if (sessionDiv) {
        sessionDiv.remove();
        console.log('[DAP] Session div removed from console:', sessionId);
    }

    // Clear current session if it was the deleted one
    if (state.currentDapSessionId === sessionId) {
        state.currentDapSessionId = null;
    }
}

/**
 * Create HTML for a DAP session item.
 */
/**
 * Disable all action buttons for a session (prevent double-click during launch).
 */
function disableSessionButtons(sessionId) {
    // Disable buttons in list
    const sessionElement = document.querySelector(`[data-session-id="${sessionId}"]`);
    if (sessionElement) {
        const buttons = sessionElement.querySelectorAll('.session-run-btn, .session-debug-btn, .session-stop-btn');
        buttons.forEach(btn => {
            btn.disabled = true;
            btn.classList.add('is-disabled');
        });
    }

    // Disable buttons in detail
    const runBtn = document.getElementById(`dap-launch-btn-${sessionId}`);
    const debugBtn = document.getElementById(`dap-debug-btn-${sessionId}`);
    const stopBtn = document.getElementById(`dap-stop-btn-${sessionId}`);

    if (runBtn) {
        runBtn.disabled = true;
        runBtn.classList.add('is-disabled');
    }
    if (debugBtn) {
        debugBtn.disabled = true;
        debugBtn.classList.add('is-disabled');
    }
    if (stopBtn) {
        stopBtn.disabled = true;
        stopBtn.classList.add('is-disabled');
    }
}

/**
 * Get button states based on session state.
 */
function getSessionButtonStates(sessionState) {
    const isPaused = sessionState === 'PAUSED';
    const isRunning = sessionState === 'RUNNING';
    const isStarting = sessionState === 'STARTING' || sessionState === 'INSTALLING' || sessionState === 'LAUNCHING' || sessionState === 'ATTACHING';
    const isStopped = sessionState === 'STOPPED' || sessionState === 'START_FAILED' || sessionState === 'ERROR' || sessionState === 'LAUNCH_FAILED' || sessionState === 'ATTACH_FAILED' || sessionState === 'CREATED' || sessionState === 'TERMINATED';

    const canLaunch = isStopped;
    const canStop = isRunning || isStarting || isPaused;

    return { canLaunch, canStop };
}

/**
 * Get session state display info (icon, text, CSS class).
 */
function getSessionStateInfo(session) {
    let stateIcon = '<span>■</span>';
    let statusText = session.state ? session.state.charAt(0) + session.state.slice(1).toLowerCase() : 'Created';
    let statusClass = 'status-stopped';

    if (session.state === 'INSTALLING') {
        stateIcon = '<span>⏳</span>';
        statusText = 'Installing';
        statusClass = 'status-installing';
    } else if (session.state === 'STARTING') {
        stateIcon = '<span>⏳</span>';
        statusText = 'Starting';
        statusClass = 'status-starting';
    } else if (session.state === 'LAUNCHING') {
        stateIcon = '<span>🚀</span>';
        statusText = 'Launching';
        statusClass = 'status-starting';
    } else if (session.state === 'ATTACHING') {
        stateIcon = '<span>🔗</span>';
        statusText = 'Attaching';
        statusClass = 'status-starting';
    } else if (session.state === 'RUNNING') {
        // Check if it's debugging or just running
        const isDebugging = session.debugMode === true;
        stateIcon = isDebugging ? '<span>🐛</span>' : '<span>▶</span>';
        statusText = isDebugging ? 'Debugging' : 'Running';
        statusClass = 'status-running';
    } else if (session.state === 'PAUSED') {
        stateIcon = '<span>⏸</span>';
        statusText = 'Paused';
        statusClass = 'status-paused';
    } else if (session.state === 'TERMINATED') {
        stateIcon = '<span>■</span>';
        statusText = 'Terminated';
        statusClass = 'status-terminated';
    } else if (session.state === 'LAUNCH_FAILED') {
        stateIcon = '<span>❌</span>';
        statusText = 'Failed';
        statusClass = 'status-error';
    } else if (session.state === 'ATTACH_FAILED') {
        stateIcon = '<span>❌</span>';
        statusText = 'Failed';
        statusClass = 'status-error';
    } else if (session.state === 'ERROR' || session.state === 'START_FAILED') {
        stateIcon = '<span>❌</span>';
        statusText = 'Error';
        statusClass = 'status-error';
    }

    return { stateIcon, statusText, statusClass };
}

export function createSessionHTML(session) {
    const { stateIcon, statusText, statusClass } = getSessionStateInfo(session);
    const { canLaunch, canStop } = getSessionButtonStates(session.state);

    // Always show all 3 buttons, grayed/enabled based on state
    const runDisabledClass = canLaunch ? '' : ' is-disabled';
    const debugDisabledClass = canLaunch ? '' : ' is-disabled';
    const stopDisabledClass = canStop ? '' : ' is-disabled';

    const actions = `
        <button class="server-action-btn session-run-btn session-btn-sm${runDisabledClass}" data-session-id="${session.sessionId}" ${canLaunch ? '' : 'disabled'} data-action="sessionRunBtn" data-stop-propagation title="Run (without debugging)">▶</button>
        <button class="server-action-btn session-debug-btn session-btn-sm${debugDisabledClass}" data-session-id="${session.sessionId}" ${canLaunch ? '' : 'disabled'} data-action="sessionDebugBtn" data-stop-propagation title="Debug (with breakpoints)">🐛</button>
        <button class="server-action-btn session-stop-btn session-btn-sm${stopDisabledClass}" data-session-id="${session.sessionId}" ${canStop ? '' : 'disabled'} data-action="sessionStopBtn" data-stop-propagation title="Stop">⏹</button>
    `;

    const creatorIcon = session.createdBy === 'AGENT'
        ? '<span class="font-sm opacity-70" title="Created by Agent">\u{1F916}</span>'
        : session.createdBy === 'USER'
        ? '<span class="font-sm opacity-70" title="Created by User">\u{1F464}</span>'
        : '<span class="text-dimmed font-sm opacity-50" title="Creator unknown">❓</span>';

    return `
        <div data-session-id="${session.sessionId}" class="dap-session-item d-flex align-center gap-sm cursor-pointer rounded template-selector" data-action="selectDapSession">
            ${stateIcon}
            <span class="flex-1 truncate session-name">${session.sessionName}</span>
            <span class="status-badge status-badge-compact ${statusClass}">${statusText}</span>
        </div>
    `;
}

// ============================================
// Keyboard shortcuts for DAP console (Ctrl+A, Ctrl+F)
// Handled by keyboard-shortcuts.js (see admin.js for registration)
// ============================================

/**
 * Load launch configuration templates for a DAP server.
 * Called when a debug session is displayed.
 */
export async function loadLaunchConfigurationTemplates(sessionId, serverId) {
    try {
        const response = await fetch(`/api/admin/dap/sessions/templates/${serverId}`);
        if (!response.ok) {
            console.warn(`No templates found for ${serverId}`);
            return;
        }

        const data = await response.json();
        const templates = data.templates || [];

        // Populate the template selector
        const selector = document.getElementById(`launch-template-selector-${sessionId}`);
        if (!selector) return;

        // Clear existing options (except the first "Select template..." option)
        selector.innerHTML = '<option value="">Select template...</option>';

        // Add template options
        templates.forEach((template, index) => {
            const option = document.createElement('option');
            option.value = index;
            option.textContent = template.label;
            selector.appendChild(option);
        });

        // Store templates on the selector for later use
        selector.dataset.templates = JSON.stringify(templates);

    } catch (error) {
        console.error('Failed to load launch configuration templates:', error);
    }
}

/**
 * Apply a selected launch configuration template to the editor.
 */
export function applyLaunchTemplate(sessionId, templateIndex) {
    if (!templateIndex) return; // "Select template..." option

    const selector = document.getElementById(`launch-template-selector-${sessionId}`);
    if (!selector) return;

    const templates = JSON.parse(selector.dataset.templates || '[]');
    const template = templates[templateIndex];
    if (!template) return;

    // template.body is already an object (not a JSON string)
    try {
        const editor = document.getElementById(`launch-config-editor-${sessionId}`);
        editor.value = JSON.stringify(template.body, null, 2);

        // Reset selector to "Select template..."
        selector.value = '';
    } catch (error) {
        console.error('Failed to apply template:', error);
    }
}

export async function toggleDapServerEnabled(serverId, enabled) {
    toggleServerEnabled('dap', serverId, enabled, dapServerConfigs, () => loadAllDapServers(selectedDapServer));
}

// Register event delegation actions
registerActions('click', {
    launchDapSession: (el) => launchDapSession(el.dataset.sessionId),
    debugDapSession: (el) => debugDapSession(el.dataset.sessionId),
    stopDapSession: (el) => stopDapSession(el.dataset.sessionId),
    deleteDapSession: (el) => deleteDapSession(el.dataset.sessionId),
    selectDapSession: (el) => selectDapSession(el.dataset.sessionId),
    showDapServerDetails: (el) => showDapServerDetails(el.dataset.serverId),
    switchDapServerTab: (el) => switchDapServerTab(el.dataset.tab),
    installDapServer: (el) => installDapServer(el.dataset.serverId),
    toggleAllDapTraces: () => {
        const sessionId = state.currentDapSessionId;
        if (sessionId) toggleAllDapTraces(sessionId);
    },
    clearDapConsole: () => {
        const sessionId = state.currentDapSessionId;
        if (sessionId) clearDapConsole(sessionId);
    },
    sessionRunBtn: (el) => {
        if (!el.disabled) {
            selectDapSession(el.dataset.sessionId);
            launchDapSession(el.dataset.sessionId);
        }
    },
    sessionDebugBtn: (el) => {
        if (!el.disabled) {
            selectDapSession(el.dataset.sessionId);
            debugDapSession(el.dataset.sessionId);
        }
    },
    sessionStopBtn: (el) => {
        if (!el.disabled) {
            selectDapSession(el.dataset.sessionId);
            stopDapSession(el.dataset.sessionId);
        }
    },
});

registerActions('change', {
    toggleDapServerEnabled: (el) => toggleDapServerEnabled(el.dataset.serverId, el.checked),
    changeDapTraceLevel: (el) => {
        const sessionId = state.currentDapSessionId;
        if (sessionId) changeDapTraceLevel(sessionId, el.value);
    },
    updateDapServerSetting: (el) => updateDapServerSetting(el.dataset.serverId, el.dataset.settingKey, el.value),
    applyLaunchTemplate: (el) => {
        const sessionId = el.dataset.sessionId || state.currentDapSessionId;
        if (sessionId) applyLaunchTemplate(sessionId, el.value);
    },
});
