/**
 * Admin UI - DAP (Debug Adapter Protocol) Management
 *
 * Handles DAP session creation, launching, and management
 */

import { state, getServerApiBase, updateSearchBoxVisibility } from './shared-state.js';
import { confirmAction, showAlert } from './shared-ui.js';
import { formatContributionsSection } from './shared-contributions.js';
import { renderServerDiagram } from './diagram.js';
import { formatErrorWithFolding } from './error-formatter.js';
import { LanguageFilter } from './language-filter.js';
import {
    renderTraceControls, updateTraceControls, renderTracesInContainer,
    getCurrentSearchQuery, toggleAllTraces, clearHighlights, initTraceContainer
} from './trace-renderer.js';
import { registerActions } from './event-delegation.js';
import { renderSettingsPanel, renderServerSetting } from './admin-settings.js';

let selectDapSessionByServerIdCallback = null;
export function setSelectDapSessionByServerIdCallback(cb) { selectDapSessionByServerIdCallback = cb; }

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
        case 'AI_AGENT':
            return '🤖 AI Agent';
        case 'MANUAL':
            return '👤 Manual';
        case 'UNKNOWN':
            return 'Unknown';
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
                        ${renderTraceControls('dap-trace', 'off', 'changeDapTraceLevel', {
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
        // Just show it
        showSessionDiv(sessionId);
        return;
    }

    // Session div doesn't exist yet, fetch details and create it
    try {
        const response = await fetch(`/api/admin/dap/sessions`);
        if (!response.ok) {
            throw new Error('Failed to fetch DAP sessions');
        }
        const sessions = await response.json();

        // Find the session by ID
        const session = sessions.find(s => s.sessionId === sessionId);

        if (!session) {
            console.error('Session not found:', sessionId);
            return;
        }

        // Create and show launch config form
        showLaunchConfigForm(session, session.serverId);

    } catch (error) {
        console.error('Error loading session:', error);
        showAlert('Failed to Load Session', error.message);
    }
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
                updateTraceControls('dap-trace', currentDapTraceLevel);
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

    renderTracesInContainer(containerId, traces, getDapTraceLevel(), getCurrentSearchQuery());
}

/**
 * ============================================
 * GLOBAL DAP SERVERS (Debuggers tab)
 * ============================================
 */

let selectedDapServer = null;
let currentDapServerTab = 'overview'; // overview, install
let dapServerConfigs = {};
let dapLanguageFilter = null;

/**
 * Load all global DAP servers.
 */
function renderDapServerItem(server) {
    const isActive = selectedDapServer === server.id ? 'active' : '';
    const disabledClass = server.enabled === false ? 'server-disabled' : '';
    return `
        <div class="server-item ${isActive} ${disabledClass}" data-action="showDapServerDetails" data-server-id="${server.id}">
            <div class="server-name d-flex align-center justify-between">
                <span>
                    <span class="server-source-icon">🐛</span>
                    ${server.name}
                </span>
                <label class="toggle-switch" onclick="event.stopPropagation()">
                    <input type="checkbox" ${server.enabled !== false ? 'checked' : ''} data-action="toggleDapServerEnabled" data-server-id="${server.id}">
                    <span class="toggle-slider"></span>
                </label>
            </div>
            <div class="server-id">${server.id}</div>
        </div>
    `;
}

export async function loadAllDapServers(serverIdToSelect) {
    try {
        const response = await fetch('/api/admin/dap/configs');
        const dapServers = (await response.json()).sort((a, b) => (a.name || '').localeCompare(b.name || ''));

        dapServerConfigs = {};
        dapServers.forEach(server => {
            dapServerConfigs[server.id] = server;
        });
        // Also update state.dapConfigs so the filter can extract languages
        state.dapConfigs = dapServerConfigs;

        const container = document.getElementById('dap-servers-list');
        if (!container) {
            console.error('dap-servers-list container not found');
            return;
        }

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
            showDapServerDetails(serverToShow);
        }
    } catch (error) {
        console.error('Failed to load DAP servers:', error);
    }
}

/**
 * Build a map of serverId -> [contributorServerIds] from contributions.
 */
function buildGlobalContributedByMap(servers) {
    const map = {};
    servers.forEach(server => {
        if (server.contributes && server.contributes.contributeServerConfigurations) {
            server.contributes.contributeServerConfigurations.forEach(targetId => {
                if (!map[targetId]) map[targetId] = [];
                map[targetId].push(server.id);
            });
        }
    });
    return map;
}

/**
 * Show details for a global DAP server with Overview/Install tabs.
 */
export async function showDapServerDetails(serverId) {
    selectedDapServer = serverId;
    state.currentDapServerId = serverId;

    // Clear current DAP session ID (we're viewing server config, not a session)
    state.currentDapSessionId = null;

    // Hide search box when showing server details (not traces)
    updateSearchBoxVisibility(false);

    // Re-render server list to update active state
    const dapServers = Object.values(dapServerConfigs).sort((a, b) => (a.name || '').localeCompare(b.name || ''));

    if (dapLanguageFilter) {
        const filteredServers = dapLanguageFilter.filterServers(dapServers);
        dapLanguageFilter.getItemsContainer().innerHTML = filteredServers.map(server =>
            renderDapServerItem(server)
        ).join('');
    }

    const server = dapServerConfigs[serverId];
    if (!server) {
        console.error('DAP server not found:', serverId);
        return;
    }

    // Show console column
    const appContainer = document.querySelector('.app-container');
    const consoleColumn = document.querySelector('.console-container');
    consoleColumn.style.display = 'flex';
    appContainer.style.gridTemplateColumns = '400px 1fr';
    consoleColumn.style.gridColumn = '2';

    // Build document selector info
    let docSelectorHTML = '<p class="text-secondary">None configured</p>';
    if (server.documentSelector && server.documentSelector.length > 0) {
        docSelectorHTML = server.documentSelector.map(selector => {
            return `<div class="selector-item">
                ${selector.language ? `<span class="selector-tag">language: ${selector.language}</span>` : ''}
                ${selector.scheme ? `<span class="selector-tag">scheme: ${selector.scheme}</span>` : ''}
                ${selector.pattern ? `<span class="selector-tag">pattern: ${selector.pattern}</span>` : ''}
            </div>`;
        }).join('');
    }

    // Check if server has contributions
    const lspServers = Object.values(state.lspConfigs || {});
    const dapServersWithFlag = dapServers.map(s => ({...s, isDap: true}));
    const allServers = [...lspServers, ...dapServersWithFlag];
    const hasContributions = (server.contributions && Object.keys(server.contributions).length > 0) ||
                            buildGlobalContributedByMap(allServers)[server.id]?.length > 0;

    // Prepare contributions HTML and diagram data (only if has contributions)
    const contributionsHTML = hasContributions ? formatContributionsSection(server, allServers) : '';

    // Store for diagram rendering
    if (hasContributions) {
        state.currentDiagramServers = allServers;
        state.currentDiagramServerId = server.id;
    }

    const detailsHTML = `
        <h3 class="text-success mt-0">Debug Adapter Information</h3>

        <div class="mb-xl">
            <strong class="text-label">Server ID:</strong>
            <p class="text-value mt-xs mb-xs"><code>${server.id}</code></p>
        </div>

        ${server.description ? `
        <div class="mb-xl">
            <strong class="text-label">Description:</strong>
            <p class="text-value mt-xs mb-xs">${server.description}</p>
        </div>
        ` : ''}

        ${server.url ? `
        <div class="mb-xl">
            <strong class="text-label">URL:</strong>
            <p class="mt-xs mb-xs"><a href="${server.url}" target="_blank" class="link-accent">${server.url}</a></p>
        </div>
        ` : ''}

        <div class="mb-xl">
            <strong class="text-label">Supported Languages/Files:</strong>
            ${docSelectorHTML}
        </div>

        <div class="p-lg bg-panel rounded mt-2xl border-left-success">
            <strong>Note:</strong> Debuggers are started on-demand during debug sessions. They are not automatically started with workspaces.
        </div>
    `;

    const html = `
        <div class="console-header">
            <div class="console-title">
                <span class="server-source-icon">🐛</span>
                ${server.name || server.id}
            </div>
            <div class="console-tabs">
                <button class="tab-button ${currentDapServerTab === 'overview' ? 'active' : ''}" data-action="switchDapServerTab" data-tab="overview">Overview</button>
                ${hasContributions ? `<button class="tab-button ${currentDapServerTab === 'contributions' ? 'active' : ''}" data-action="switchDapServerTab" data-tab="contributions">Contributions</button>` : ''}
                <button class="tab-button ${currentDapServerTab === 'settings' ? 'active' : ''}" data-action="switchDapServerTab" data-tab="settings">Settings</button>
                <button class="tab-button ${currentDapServerTab === 'install' ? 'active' : ''}" data-action="switchDapServerTab" data-tab="install">Install</button>
            </div>
            <div class="console-controls">
            </div>
        </div>
        <div class="tab-content">
            <div id="dap-server-overview-tab" class="tab-panel ${currentDapServerTab === 'overview' ? 'active' : ''}">
                <div class="details-panel text-primary detail-content">
                    ${detailsHTML}
                </div>
            </div>
            ${hasContributions ? `
            <div id="dap-server-contributions-tab" class="tab-panel ${currentDapServerTab === 'contributions' ? 'active' : ''}">
                <div id="server-diagram-container" class="w-100 bg-card diagram-container"></div>
                <div class="diagram-resizer"></div>
                <div class="details-panel text-primary flex-1 min-h-0 detail-content" id="dap-contributions-content">
                    ${contributionsHTML}
                </div>
            </div>
            ` : ''}
            <div id="dap-server-settings-tab" class="tab-panel ${currentDapServerTab === 'settings' ? 'active' : ''}">
                <div class="details-panel text-primary overflow-auto p-2xl">
                    ${buildDapSettingsHTML(server)}
                </div>
            </div>
            <div id="dap-server-install-tab" class="tab-panel ${currentDapServerTab === 'install' ? 'active' : ''}">
                <div class="install-panel">
                    <h3>Installer Configuration</h3>
                    <div class="install-info">
                        <p><strong>Debugger:</strong> ${server.name}</p>
                        <p><strong>ID:</strong> ${server.id}</p>
                    </div>
                    <div class="installer-editor">
                        <div class="editor-header">
                            <span>installer.json</span>
                            <div class="editor-actions">
                                <button class="editor-btn" data-action="saveDapInstallerJson" data-server-id="${server.id}" title="Save">💾 Save</button>
                                <button class="editor-btn" data-action="resetDapInstallerJson" data-server-id="${server.id}" title="Reset">↻ Reset</button>
                                <span class="editor-separator"></span>
                                <button class="editor-btn install-run-btn" data-action="runDapInstaller" data-server-id="${server.id}" data-force="false" title="Install (check first, skip if already installed)">▶ Install</button>
                                <button class="editor-btn install-force-btn" data-action="runDapInstaller" data-server-id="${server.id}" data-force="true" title="Force Install (skip check, always re-install)">⟳ Force Install</button>
                            </div>
                        </div>
                        <textarea id="dap-installer-json-editor" class="json-editor" spellcheck="false"></textarea>
                    </div>
                    <div id="dap-install-output" class="install-output"></div>
                </div>
            </div>
        </div>
    `;

    document.getElementById('console-area').innerHTML = html;

    // Load installer.json for this DAP server if on Install tab
    if (currentDapServerTab === 'install') {
        loadDapInstallerJson(server.id);
    }
}

/**
 * Switch between DAP server tabs (Overview/Install).
 */
export function switchDapServerTab(tab) {
    currentDapServerTab = tab;
    if (selectedDapServer) {
        showDapServerDetails(selectedDapServer);
    }
    // Render diagram when switching to contributions tab
    if (tab === 'contributions' && state.currentDiagramServers && state.currentDiagramServerId) {
        setTimeout(() => renderServerDiagram(state.currentDiagramServers, state.currentDiagramServerId), 100);
    }
}

/**
 * Load installer.json for a DAP server.
 */
async function loadDapInstallerJson(serverId) {
    try {
        const response = await fetch(`/api/admin/dap/configs/${serverId}/installer`);
        if (!response.ok) throw new Error('Failed to load installer.json');

        const installerJson = await response.json();
        const editor = document.getElementById('dap-installer-json-editor');
        if (editor) {
            editor.value = JSON.stringify(installerJson, null, 2);
        }
    } catch (error) {
        console.error('Failed to load DAP installer.json:', error);
        const editor = document.getElementById('dap-installer-json-editor');
        if (editor) {
            editor.value = '// No installer.json found for this debugger';
        }
    }
}

/**
 * Save installer.json for a DAP server.
 */
async function saveDapInstallerJson(serverId) {
    const editor = document.getElementById('dap-installer-json-editor');
    if (!editor) return;

    try {
        const installerJson = JSON.parse(editor.value);

        const response = await fetch(`/api/admin/dap/configs/${serverId}/installer`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(installerJson)
        });

        if (!response.ok) throw new Error('Failed to save installer.json');

        showAlert('Success', 'Installer configuration saved successfully.');
    } catch (error) {
        console.error('Failed to save DAP installer.json:', error);
        showAlert('Error', 'Failed to save installer.json: ' + error.message);
    }
}

/**
 * Reset installer.json to original.
 */
async function resetDapInstallerJson(serverId) {
    loadDapInstallerJson(serverId);
}

/**
 * Run installer for a DAP server.
 */
async function runDapInstaller(serverId, force) {
    const outputDiv = document.getElementById('dap-install-output');
    if (!outputDiv) return;

    const label = force ? 'Force installing' : 'Installing';
    outputDiv.innerHTML = `<div class="text-success">${label}...</div>`;

    try {
        const url = `/api/admin/dap/configs/${serverId}/install${force ? '?force=true' : ''}`;
        const response = await fetch(url, { method: 'POST' });

        if (!response.ok) throw new Error('Installation failed');

        const result = await response.json();
        outputDiv.innerHTML = `
            <div class="text-success">Installation started</div>
            <pre class="text-value mt-sm">${JSON.stringify(result, null, 2)}</pre>
        `;
    } catch (error) {
        console.error('Failed to run DAP installer:', error);
        outputDiv.innerHTML = `<div class="text-error">Installation failed: ${error.message}</div>`;
    }
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
    const foldButton = document.getElementById('dap-trace-fold-button');
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
    const dapTraceLevel = (state.traceLevels && state.traceLevels['dap.' + server.id]) || 'off';
    const traceSetting = {
        key: 'trace',
        label: 'Trace Level',
        description: 'Controls protocol message tracing',
        type: 'enum',
        values: ['off', 'messages', 'verbose'],
        currentValue: dapTraceLevel,
        source: null
    };
    return renderSettingsPanel({
        title: 'Settings',
        itemsHtml: [renderServerSetting(traceSetting, 'updateDapServerSetting', null, { 'server-id': server.id })]
    });
}

function updateDapServerSetting(serverId, settingKey, value) {
    if (settingKey === 'trace') {
        changeDapServerTraceLevel(serverId, value);
    }
}

export async function changeDapServerTraceLevel(serverId, level) {
    if (state.traceLevels) {
        state.traceLevels['dap.' + serverId] = level;
    }
    try {
        await fetch(`/api/admin/traces/dap/${serverId}`, {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ traceLevel: level })
        });
    } catch (e) {
        console.error('Failed to save DAP trace level:', e);
    }
}

export async function changeDapTraceLevel(sessionId, level) {
    currentDapTraceLevel = level;
    const session = state.dapSessions?.find(s => s.sessionId === sessionId);
    const serverId = session?.serverId || session?.dapServerId || state.currentDapServerId;
    const workspaceUri = session?.workspaceUri;
    if (serverId && workspaceUri) {
        try {
            await fetch(`/api/admin/workspaces/${encodeURIComponent(workspaceUri)}/traces/dap/${encodeURIComponent(serverId)}`, {
                method: 'PUT',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ traceLevel: level })
            });
        } catch (e) {
            console.error('Failed to save DAP trace level:', e);
        }
    } else if (serverId) {
        changeDapServerTraceLevel(serverId, level);
    }
    updateTraceControls('dap-trace', level);
    renderDapTracesForSession(sessionId);
}


/**
 * Handle DAP session update from WebSocket.
 */
export async function onDapSessionUpdate(message) {
    console.log('[DAP] Session update:', message.eventType, message.sessionId, message.newStatus);

    if (state.currentWorkspaceTab !== 'debuggers') {
        return; // Not on debuggers tab, ignore
    }

    // Update only the affected session based on event type
    switch (message.eventType) {
        case 'CREATED':
            if (message.workspaceUri === state.selectedWorkspace) {
                // Skip if already in DOM (added by createNewTestSession)
                if (document.querySelector(`[data-session-id="${message.sessionId}"]`)) {
                    break;
                }
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

                            const serverElement = document.querySelector(`[data-dap-server="${newSession.serverId}"]`);
                            if (serverElement) {
                                serverElement.insertAdjacentHTML('afterend', createSessionHTML(newSession));
                                selectDapSession(newSession.sessionId);
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

            updateSessionStateInDOM(message.sessionId, message.newStatus, message.debugMode);
            updateSessionDetailInDOM(message.sessionId, message);
            break;

        case 'DELETED': {
            const wasDisplayed = state.currentDapSessionId === message.sessionId;
            const deletedSession = state.dapSessions?.find(s => s.sessionId === message.sessionId);
            const deletedServerId = deletedSession?.serverId || deletedSession?.dapServerId || state.currentDapServerId;

            if (state.dapSessions) {
                state.dapSessions = state.dapSessions.filter(s => s.sessionId !== message.sessionId);
            }
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
            // Extract emoji from HTML string (e.g., "<span>⏹️</span>" -> "⏹️")
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
 * Update an existing session in the DOM.
 */
async function updateSessionInDOM(message) {
    try {
        // Determine status text, class, and icon
        let statusText = message.newStatus ? message.newStatus.charAt(0) + message.newStatus.slice(1).toLowerCase() : '';
        let statusClass = 'status-stopped';
        let stateIcon = '<span>⏹️</span>';

        if (message.newStatus === 'INSTALLING') {
            statusText = 'Installing';
            statusClass = 'status-installing';
            stateIcon = '<span>⏳</span>';
        } else if (message.newStatus === 'STARTING') {
            statusText = 'Starting';
            statusClass = 'status-starting';
            stateIcon = '<span>⏳</span>';
        } else if (message.newStatus === 'LAUNCHING') {
            statusText = 'Launching';
            statusClass = 'status-starting';
            stateIcon = '<span>🚀</span>';
        } else if (message.newStatus === 'ATTACHING') {
            statusText = 'Attaching';
            statusClass = 'status-starting';
            stateIcon = '<span>🔗</span>';
        } else if (message.newStatus === 'TERMINATED') {
            statusText = 'Terminated';
            statusClass = 'status-error';
            stateIcon = '<span>⏹️</span>';
        } else if (message.newStatus === 'RUNNING') {
            statusText = 'Running';
            statusClass = 'status-running';
            stateIcon = '<span>▶️</span>';
        } else if (message.newStatus === 'PAUSED') {
            statusText = 'Paused';
            statusClass = 'status-paused';
            stateIcon = '<span>⏸️</span>';
        } else if (message.newStatus === 'LAUNCH_FAILED') {
            statusText = 'Launch Failed';
            statusClass = 'status-error';
            stateIcon = '<span>❌</span>';
        } else if (message.newStatus === 'ATTACH_FAILED') {
            statusText = 'Attach Failed';
            statusClass = 'status-error';
            stateIcon = '<span>❌</span>';
        } else if (message.newStatus === 'ERROR' || message.newStatus === 'START_FAILED') {
            statusText = 'Error';
            statusClass = 'status-error';
            stateIcon = '<span>❌</span>';
        }

        // Update status badge AND icon in workspace list (left sidebar)
        const sessionElement = document.querySelector(`[data-session-id="${message.sessionId}"]`);
        if (sessionElement) {
            const statusBadge = sessionElement.querySelector('.status-badge');
            if (statusBadge && message.newStatus) {
                statusBadge.className = `status-badge status-badge-compact ${statusClass}`;
                statusBadge.textContent = statusText;
            }
            // Update icon (first child element in the session div)
            const firstChild = sessionElement.firstElementChild;
            if (firstChild && firstChild.tagName === 'SPAN') {
                // Parse the new icon HTML and replace the old span
                const temp = document.createElement('div');
                temp.innerHTML = stateIcon;
                const newIcon = temp.firstElementChild;
                if (newIcon && firstChild.parentNode) {
                    firstChild.parentNode.replaceChild(newIcon, firstChild);
                }
            }
        }

        // Update status badge in session console (right panel)
        const sessionStatusBadge = document.getElementById(`dap-session-status-${message.sessionId}`);
        if (sessionStatusBadge && message.newStatus) {
            sessionStatusBadge.className = `status-badge status-badge-compact ${statusClass}`;
            sessionStatusBadge.textContent = statusText;
        }

        // Update Debug/Launch/Stop buttons state based on status
        const debugBtn = document.getElementById(`dap-debug-btn-${message.sessionId}`);
        const launchBtn = document.getElementById(`dap-launch-btn-${message.sessionId}`);
        const stopBtn = document.getElementById(`dap-stop-btn-${message.sessionId}`);

        if (debugBtn && launchBtn && stopBtn) {
            // Determine button states based on status
            const isRunning = message.newStatus === 'RUNNING';
            const isPaused = message.newStatus === 'PAUSED';
            const isStarting = message.newStatus === 'STARTING' || message.newStatus === 'INSTALLING' || message.newStatus === 'LAUNCHING' || message.newStatus === 'ATTACHING';
            const isStopped = message.newStatus === 'STOPPED' || message.newStatus === 'START_FAILED' || message.newStatus === 'ERROR' || message.newStatus === 'LAUNCH_FAILED' || message.newStatus === 'ATTACH_FAILED' || message.newStatus === 'CREATED' || message.newStatus === 'TERMINATED';

            const canLaunch = isStopped;
            const canStop = isRunning || isStarting || isPaused;

            // Update Debug button (same state as Launch)
            debugBtn.disabled = !canLaunch;
            debugBtn.classList.toggle('is-disabled', !canLaunch);

            // Update Launch button
            launchBtn.disabled = !canLaunch;
            launchBtn.classList.toggle('is-disabled', !canLaunch);

            // Update Stop button
            stopBtn.disabled = !canStop;
            stopBtn.classList.toggle('is-disabled', !canStop);
        }

        console.log('[DAP] Session status updated in DOM:', message.sessionId, message.oldStatus, '->', message.newStatus);
    } catch (error) {
        console.error('[DAP] Error updating session in DOM:', error);
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
    let stateIcon = '<span>⏹️</span>';
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
        stateIcon = isDebugging ? '<span>🐛</span>' : '<span>▶️</span>';
        statusText = isDebugging ? 'Debugging' : 'Running';
        statusClass = 'status-running';
    } else if (session.state === 'PAUSED') {
        stateIcon = '<span>⏸️</span>';
        statusText = 'Paused';
        statusClass = 'status-paused';
    } else if (session.state === 'TERMINATED') {
        stateIcon = '<span>⏹️</span>';
        statusText = 'Terminated';
        statusClass = 'status-error';
    } else if (session.state === 'LAUNCH_FAILED') {
        stateIcon = '<span>❌</span>';
        statusText = 'Launch Failed';
        statusClass = 'status-error';
    } else if (session.state === 'ATTACH_FAILED') {
        stateIcon = '<span>❌</span>';
        statusText = 'Attach Failed';
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

    // Add creator icon
    console.log('[DAP] Session createdBy:', session.sessionId, session.createdBy);
    const creatorIcon = session.createdBy === 'AI_AGENT'
        ? '<span class="font-sm opacity-70" title="Created by AI Agent">🤖</span>'
        : session.createdBy === 'MANUAL'
        ? '<span class="font-sm opacity-70" title="Created manually">👤</span>'
        : '<span class="text-dimmed font-sm opacity-50" title="Creator unknown">❓</span>';

    return `
        <div data-session-id="${session.sessionId}" class="dap-session-item d-flex align-center gap-sm cursor-pointer font-md rounded template-selector" data-action="selectDapSession">
            ${stateIcon}
            ${creatorIcon}
            <span class="flex-1 truncate">${session.sessionName}</span>
            <span class="status-badge status-badge-compact ${statusClass}">${statusText}</span>
            ${actions}
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

/**
 * Toggle enable/disable for a DAP server.
 */
export async function toggleDapServerEnabled(serverId, enabled) {
    const action = enabled ? 'enable' : 'disable';
    try {
        const response = await fetch(`/api/admin/extensions/dap/servers/${serverId}/${action}`, { method: 'POST' });
        if (response.ok) {
            if (dapServerConfigs[serverId]) {
                dapServerConfigs[serverId].enabled = enabled;
            }
            loadAllDapServers(selectedDapServer);
        }
    } catch (error) {
        console.error(`Failed to ${action} DAP server:`, error);
    }
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
    saveDapInstallerJson: (el) => saveDapInstallerJson(el.dataset.serverId),
    resetDapInstallerJson: (el) => resetDapInstallerJson(el.dataset.serverId),
    runDapInstaller: (el) => runDapInstaller(el.dataset.serverId, el.dataset.force === 'true'),
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
