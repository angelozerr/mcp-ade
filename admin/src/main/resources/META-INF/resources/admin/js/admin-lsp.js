/**
 * Admin UI - LSP (Language Server Protocol) Global Management
 *
 * Handles global LSP server listing with Overview/Install tabs
 */

import { state, getServerApiBase, buildGlobalContributedByMap } from './shared-state.js';
import { showAlert, renderDocumentSelector, runServerInstaller } from './shared-ui.js';
import { formatContributionsSection } from './shared-contributions.js';
import { renderServerDiagram } from './diagram.js';
import { LanguageFilter } from './language-filter.js';
import { escapeHtml } from './trace-renderer.js';
import { registerActions } from './event-delegation.js';
import { renderSettingsPanel, renderServerSetting } from './admin-settings.js';

let selectedAllServer = null; // Track selected server in global Servers tab
let currentServerTab = 'overview'; // Track current tab: overview, contributions, install
let allServersLoaded = false;
let lspLanguageFilter = null;

/**
 * Load all global LSP servers.
 */
function renderLspServerItem(server, contributedByMap) {
    const isActive = selectedAllServer === server.id ? 'active' : '';
    const extensionClass = server.isExtension ? 'server-extension' : '';
    const disabledClass = server.enabled === false ? 'server-disabled' : '';
    const extensionBadge = server.isExtension ? ' <span class="text-secondary font-md">(Extension)</span>' : '';
    const serverIcon = server.isExtension ? '🧩' : '🚀';
    const contributeInfo = formatGlobalContributeInfo(server, contributedByMap);
    return `
        <div class="server-item ${isActive} ${extensionClass} ${disabledClass}" data-action="showServerDetails" data-server-id="${server.id}">
            <div class="server-name d-flex align-center justify-between">
                <span>
                    <span class="server-source-icon">${serverIcon}</span>
                    ${server.name}${extensionBadge}
                </span>
                <label class="toggle-switch" onclick="event.stopPropagation()">
                    <input type="checkbox" ${server.enabled !== false ? 'checked' : ''} data-action="toggleLspServerEnabled" data-server-id="${server.id}">
                    <span class="toggle-slider"></span>
                </label>
            </div>
            <div class="server-id" ${contributeInfo.tooltip ? `title="${contributeInfo.tooltip}"` : ''}>${server.id}${contributeInfo.text}</div>
        </div>
    `;
}

export async function loadAllLspServers(serverIdToSelect) {
    try {
        const lspServers = Object.values(state.lspConfigs || {}).sort((a, b) => (a.name || '').localeCompare(b.name || ''));
        const dapServers = Object.values(state.dapConfigs || {}).map(s => ({...s, isDap: true}));
        const allServers = [...lspServers, ...dapServers];

        const container = document.getElementById('lsp-servers-list');
        if (!container) {
            console.error('lsp-servers-list container not found');
            return;
        }

        if (!lspLanguageFilter) {
            lspLanguageFilter = new LanguageFilter(container, () => state.lspConfigs, () => loadAllLspServers(selectedAllServer));
        }

        const contributedByMap = buildGlobalContributedByMap(allServers);
        const filteredServers = lspLanguageFilter.filterServers(lspServers);

        lspLanguageFilter.getItemsContainer().innerHTML = filteredServers.map(server =>
            renderLspServerItem(server, contributedByMap)
        ).join('');

        allServersLoaded = true;

        if (filteredServers.length > 0) {
            let serverToShow;
            if (serverIdToSelect && filteredServers.find(s => s.id === serverIdToSelect)) {
                serverToShow = serverIdToSelect;
            } else if (selectedAllServer && filteredServers.find(s => s.id === selectedAllServer)) {
                serverToShow = selectedAllServer;
            } else {
                serverToShow = filteredServers[0].id;
            }
            showServerDetails(serverToShow);
        }
    } catch (error) {
        console.error('Failed to load all LSP servers:', error);
    }
}

/**
 * Show details for a global LSP server with Overview/Contributions/Install tabs.
 */
export async function showServerDetails(serverId) {
    // Update selected server
    const previousServer = selectedAllServer;
    selectedAllServer = serverId;

    // Toggle active class instead of re-rendering the entire list
    if (lspLanguageFilter) {
        const container = lspLanguageFilter.getItemsContainer();
        if (previousServer) {
            const prev = container.querySelector(`.server-item[data-server-id="${previousServer}"]`);
            if (prev) prev.classList.remove('active');
        }
        const next = container.querySelector(`.server-item[data-server-id="${serverId}"]`);
        if (next) next.classList.add('active');
    }

    const lspServers = Object.values(state.lspConfigs || {}).sort((a, b) => (a.name || '').localeCompare(b.name || ''));
    const dapServers = Object.values(state.dapConfigs || {}).map(s => ({...s, isDap: true}));
    const allServers = [...lspServers, ...dapServers];

    const details = state.lspConfigs[serverId];
    if (!details) {
        console.error('Server not found:', serverId);
        return;
    }

    try {
        // Show console column
        const appContainer = document.querySelector('.app-container');
        const consoleColumn = document.querySelector('.console-container');
        consoleColumn.style.display = 'flex';
        appContainer.style.gridTemplateColumns = '400px 1fr';
        consoleColumn.style.gridColumn = '2';

        // Build details HTML
        const serverIcon = details.isExtension ? '🧩' : '🚀';

        // Overview tab content (pass allServers for contribution detection)
        const detailsHTML = buildServerDetailsHTML(details, allServers);

        // Contributions tab content (use same function as workspace, pass allServers)
        const contributionsHTML = formatContributionsSection(details, allServers);

        const html = `
            <div class="console-header">
                <div class="console-title">
                    <span class="server-source-icon">${serverIcon}</span>
                    ${details.name || details.id}
                </div>
                <div class="console-tabs">
                    <button class="tab-button ${currentServerTab === 'overview' ? 'active' : ''}" data-action="switchServerTab" data-tab="overview">Overview</button>
                    <button class="tab-button ${currentServerTab === 'contributions' ? 'active' : ''}" data-action="switchServerTab" data-tab="contributions">Contributions</button>
                    <button class="tab-button ${currentServerTab === 'settings' ? 'active' : ''}" data-action="switchServerTab" data-tab="settings">Settings</button>
                    <button class="tab-button ${currentServerTab === 'install' ? 'active' : ''}" data-action="switchServerTab" data-tab="install">Install</button>
                </div>
                <div class="console-controls">
                </div>
            </div>
            <div class="tab-content">
                <div id="server-overview-tab" class="tab-panel ${currentServerTab === 'overview' ? 'active' : ''}">
                    <div class="details-panel text-primary overflow-auto p-2xl">
                        ${detailsHTML}
                        <div class="p-lg bg-panel rounded-sm mt-2xl border-left-accent">
                            <strong>Note:</strong> To run this server, open a workspace using an MCP client.
                        </div>
                    </div>
                </div>
                <div id="server-contributions-tab" class="tab-panel ${currentServerTab === 'contributions' ? 'active' : ''}">
                    <div id="server-diagram-container" class="w-100 bg-card diagram-container"></div>
                    <div class="diagram-resizer"></div>
                    <div class="details-panel text-primary flex-1 min-h-0 overflow-auto p-2xl">
                        ${contributionsHTML || '<p class="detail-value">No contributions</p>'}
                    </div>
                </div>
                <div id="server-settings-tab" class="tab-panel ${currentServerTab === 'settings' ? 'active' : ''}">
                    <div class="details-panel text-primary overflow-auto p-2xl">
                        ${buildSettingsHTML(details)}
                    </div>
                </div>
                <div id="server-install-tab" class="tab-panel ${currentServerTab === 'install' ? 'active' : ''}">
                    <div class="install-panel">
                        <h3>Installer Configuration</h3>
                        <div class="install-info">
                            <p><strong>Server:</strong> ${details.name}</p>
                            <p><strong>ID:</strong> ${details.id}</p>
                        </div>
                        <div class="installer-editor">
                            <div class="editor-header">
                                <span>installer.json</span>
                                <div class="editor-actions">
                                    <button class="editor-btn" data-action="saveInstallerJson" data-server-id="${details.id}" title="Save">💾 Save</button>
                                    <button class="editor-btn" data-action="resetInstallerJson" data-server-id="${details.id}" title="Reset">↻ Reset</button>
                                    <span class="editor-separator"></span>
                                    <button class="editor-btn install-run-btn" data-action="runInstaller" data-server-id="${details.id}" data-force="false" title="Install (check first, skip if already installed)">▶ Install</button>
                                    <button class="editor-btn install-force-btn" data-action="runInstaller" data-server-id="${details.id}" data-force="true" title="Force Install (skip check, always re-install)">⟳ Force Install</button>
                                </div>
                            </div>
                            <textarea id="installer-json-editor" class="json-editor" spellcheck="false"></textarea>
                        </div>
                        <div id="install-output" class="install-output"></div>
                    </div>
                </div>
            </div>
        `;

        const consoleArea = document.getElementById('console-area');
        consoleArea.innerHTML = html;

        // Load installer.json for this server
        loadInstallerJson(details.id);

        // Render diagram (will be called when switching to diagram tab)
        // Store servers data for diagram rendering (include both LSP and DAP)
        state.currentDiagramServers = allServers;
        state.currentDiagramServerId = details.id;

        // If contributions tab is active, render diagram immediately
        if (currentServerTab === 'contributions') {
            setTimeout(() => renderServerDiagram(allServers, details.id), 100);
        }

    } catch (error) {
        console.error('Failed to load server details:', error);
        document.getElementById('console-area').innerHTML = `
            <div class="placeholder text-error-light">
                Failed to load server details
            </div>
        `;
    }
}

/**
 * Build server details HTML for Overview tab.
 */
function buildServerDetailsHTML(details, allServers) {
    const docSelectorHTML = renderDocumentSelector(details.documentSelector);

    // Command
    let commandHTML = '<p class="text-secondary">None (contribution-only server)</p>';
    if (details.command) {
        if (typeof details.command === 'string') {
            commandHTML = `<code>${details.command}</code>`;
        } else {
            commandHTML = Object.entries(details.command).map(([os, cmd]) =>
                `<div class="mb-xs"><strong>${os}:</strong> <code>${cmd}</code></div>`
            ).join('');
        }
    }

    return `
        <h3 class="text-label mt-0">Server Information</h3>

        <div class="mb-xl">
            <strong class="text-label">Server ID:</strong>
            <p class="text-value mt-xs mb-xs"><code>${details.id}</code></p>
        </div>

        ${details.description ? `
        <div class="mb-xl">
            <strong class="text-label">Description:</strong>
            <p class="text-value mt-xs mb-xs">${details.description}</p>
        </div>
        ` : ''}

        ${details.url ? `
        <div class="mb-xl">
            <strong class="text-label">URL:</strong>
            <p class="mt-xs mb-xs"><a href="${details.url}" target="_blank" class="link-accent">${details.url}</a></p>
        </div>
        ` : ''}

        <div class="mb-xl">
            <strong class="text-label">Command:</strong>
            <p class="text-value mt-xs mb-xs">${commandHTML}</p>
        </div>

        ${details.args && details.args.length > 0 ? `
        <div class="mb-xl">
            <strong class="text-label">Arguments:</strong>
            <ul class="text-value mt-sm mb-sm" style="padding-left: 1.5rem;">
                ${details.args.map(arg => `<li><code>${arg}</code></li>`).join('')}
            </ul>
        </div>
        ` : ''}

        <div class="mb-xl">
            <strong class="text-label">Supported Languages/Files:</strong>
            ${docSelectorHTML}
        </div>

    `;
}

function buildSettingsHTML(details) {
    const lspTraceLevel = (state.traceLevels && state.traceLevels['lsp.' + details.id]) || 'off';
    const traceSetting = {
        key: 'trace',
        label: 'Trace Level',
        description: 'Controls protocol message tracing',
        type: 'enum',
        values: ['off', 'messages', 'verbose'],
        currentValue: lspTraceLevel,
        source: null
    };

    const traceItems = [renderServerSetting(traceSetting, 'updateServerSetting', null, { 'server-id': details.id })];

    const regularItems = (details.settings || []).map(setting =>
        renderServerSetting({ ...setting, source: null }, 'updateServerSetting', null,
            { 'server-id': details.id })
    );

    return renderSettingsPanel({
        title: 'Settings',
        itemsHtml: [...traceItems, ...regularItems]
    });
}

/**
 * Update a server setting via the REST API and sync in-memory state.
 * @param {string} serverId - Server ID (e.g. "jdtls")
 * @param {string} settingKey - Setting key (e.g. "java.import.mode")
 * @param {string} value - New value
 */
function updateServerSetting(serverId, settingKey, value) {
    if (settingKey === 'trace') {
        changeLspServerTraceLevel(serverId, value);
        return;
    }
    const persistKey = `lsp.${serverId}.settings.${settingKey}`;
    fetch(`/api/admin/settings/${persistKey}`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ value: value })
    }).then(response => {
        if (!response.ok) {
            console.error('Failed to update setting:', persistKey, response.statusText);
            return;
        }
        const config = state.lspConfigs && state.lspConfigs[serverId];
        if (config && config.settings) {
            const setting = config.settings.find(s => s.key === settingKey);
            if (setting) {
                setting.currentValue = value;
            }
        }
    }).catch(err => console.error('Error updating setting:', err));
}

/**
 * Switch between LSP server tabs (Overview/Contributions/Install).
 */
export function switchServerTab(tabName) {
    currentServerTab = tabName;
    // Toggle tab buttons
    document.querySelectorAll('#console-area .tab-button').forEach(btn => {
        btn.classList.toggle('active', btn.dataset.tab === tabName);
    });
    // Toggle tab panels
    document.querySelectorAll('#console-area .tab-panel').forEach(panel => {
        panel.classList.toggle('active', panel.id === `server-${tabName}-tab`);
    });
    // Render diagram when switching to contributions tab
    if (tabName === 'contributions' && state.currentDiagramServers && state.currentDiagramServerId) {
        setTimeout(() => renderServerDiagram(state.currentDiagramServers, state.currentDiagramServerId), 100);
    }
}

/**
 * Load installer.json for an LSP or DAP server.
 */
export async function loadInstallerJson(serverId) {
    try {
        const response = await fetch(`${getServerApiBase(serverId)}/${serverId}/installer`);
        if (!response.ok) {
            throw new Error('Failed to load installer.json');
        }

        const installerJson = await response.json();
        const editor = document.getElementById('installer-json-editor');
        if (editor) {
            editor.value = JSON.stringify(installerJson, null, 2);
        }
    } catch (error) {
        console.error('Failed to load installer.json:', error);
        const editor = document.getElementById('installer-json-editor');
        if (editor) {
            editor.value = '// No installer.json found for this server';
        }
    }
}

/**
 * Save installer.json for an LSP or DAP server.
 */
export async function saveInstallerJson(serverId) {
    const editor = document.getElementById('installer-json-editor');
    if (!editor) return;

    try {
        const installerJson = JSON.parse(editor.value);

        const response = await fetch(`${getServerApiBase(serverId)}/${serverId}/installer`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(installerJson)
        });

        if (!response.ok) {
            throw new Error('Failed to save installer.json');
        }

        showAlert('Success', 'Installer configuration saved successfully.');
    } catch (error) {
        console.error('Failed to save installer.json:', error);
        showAlert('Error', 'Failed to save installer.json: ' + error.message);
    }
}

/**
 * Reset installer.json to original.
 */
export function resetInstallerJson(serverId) {
    loadInstallerJson(serverId);
}

/**
 * Run installer for an LSP server.
 */
export async function runInstaller(serverId, force, workspaceUri) {
    let installUrl = `${getServerApiBase(serverId)}/${serverId}/install`;
    if (workspaceUri) {
        installUrl += `?workspaceUri=${encodeURIComponent(workspaceUri)}`;
    }
    return runServerInstaller(serverId, force, 'install-output', installUrl);
}

/**
 * Helper: Format contribute info for server list.
 */
function formatGlobalContributeInfo(server, contributedByMap) {
    const contributors = contributedByMap[server.id] || [];
    if (contributors.length === 0) return { text: '', tooltip: '' };

    const text = ` ← ${contributors.length}`;
    const tooltip = `Contributions from: ${contributors.join(', ')}`;
    return { text, tooltip };
}

async function changeLspServerTraceLevel(serverId, level) {
    if (state.traceLevels) {
        state.traceLevels['lsp.' + serverId] = level;
    }
    try {
        await fetch(`/api/admin/traces/lsp/${serverId}`, {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ traceLevel: level })
        });
    } catch (e) {
        console.error('Failed to save LSP trace level:', e);
    }
}

/**
 * Toggle enable/disable for an LSP server.
 */
async function toggleLspServerEnabled(serverId, enabled) {
    const action = enabled ? 'enable' : 'disable';
    try {
        const response = await fetch(`/api/admin/extensions/lsp/servers/${serverId}/${action}`, { method: 'POST' });
        if (response.ok) {
            // Update cached config
            if (state.lspConfigs[serverId]) {
                state.lspConfigs[serverId].enabled = enabled;
            }
            // Re-render the list
            loadAllLspServers(selectedAllServer);
        }
    } catch (error) {
        console.error(`Failed to ${action} LSP server:`, error);
    }
}

// Register event delegation actions
registerActions('click', {
    showServerDetails: (el) => showServerDetails(el.dataset.serverId),
    switchServerTab: (el) => switchServerTab(el.dataset.tab),
    saveInstallerJson: (el) => saveInstallerJson(el.dataset.serverId),
    resetInstallerJson: (el) => resetInstallerJson(el.dataset.serverId),
    runInstaller: (el) => runInstaller(el.dataset.serverId, el.dataset.force === 'true'),
});

registerActions('change', {
    toggleLspServerEnabled: (el) => toggleLspServerEnabled(el.dataset.serverId, el.checked),
    updateServerSetting: (el) => updateServerSetting(el.dataset.serverId, el.dataset.settingKey,
        el.type === 'checkbox' ? String(el.checked) : el.value),
});

registerActions('input', {
});
