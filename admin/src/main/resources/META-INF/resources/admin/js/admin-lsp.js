/**
 * Admin UI - LSP (Language Server Protocol) Global Management
 *
 * Handles global LSP server listing with Overview/Contributions/Settings tabs
 */

import { state, getServerApiBase, buildGlobalContributedByMap, ensureLspConfigs, ensureDapConfigs } from './shared-state.js';
import {
    showAlert, renderDocumentSelector, renderRuntimeSection, renderExtensionSection, runServerInstaller,
    switchServerTabs, toggleServerEnabled, changeServerTraceLevel, buildServerSettingsHTML,
    selectListItem, buildInstallOutputHTML, buildInstallerControlsHTML, loadInstallerJsonEditor, saveInstallerJsonEditor,
    getInstallStatusBadge, renderServerNameHeader, restoreInstallOutput
} from './shared-ui.js';
import { formatContributionsSection } from './shared-contributions.js';
import { renderServerDiagram } from './diagram.js';
import { LanguageFilter } from './language-filter.js';
import { escapeHtml } from './trace-renderer.js';
import { registerActions } from './event-delegation.js';

let selectedAllServer = null; // Track selected server in global Servers tab
let currentServerTab = 'overview'; // Track current tab: overview, contributions, settings
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
            ${renderServerNameHeader(server, { icon: serverIcon, nameExtra: extensionBadge, toggleAction: 'toggleLspServerEnabled' })}
            <div class="server-id" ${contributeInfo.tooltip ? `title="${contributeInfo.tooltip}"` : ''}>${server.id}${contributeInfo.text}</div>
        </div>
    `;
}

export async function loadAllLspServers(serverIdToSelect) {
    try {
        await Promise.all([ensureLspConfigs(), ensureDapConfigs()]);
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
            showServerDetails(serverToShow, true);
        }
    } catch (error) {
        console.error('Failed to load all LSP servers:', error);
    }
}

/**
 * Show details for a global LSP server with Overview/Contributions/Settings tabs.
 */
export async function showServerDetails(serverId, scroll) {
    // Update selected server
    const previousServer = selectedAllServer;
    selectedAllServer = serverId;

    if (lspLanguageFilter) {
        selectListItem(lspLanguageFilter.getItemsContainer(),
            '.server-item[data-server-id', previousServer, serverId, scroll);
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
        const contentArea = document.querySelector('.content-area');
        const consoleColumn = document.querySelector('.console-container');
        consoleColumn.style.display = 'flex';
        contentArea.style.gridTemplateColumns = '400px 1fr';
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
                    <span class="console-install-badge" data-server-id="${details.id}">${getInstallStatusBadge(details)}</span>
                </div>
                <div class="console-tabs">
                    <button class="tab-button ${currentServerTab === 'overview' ? 'active' : ''}" data-action="switchServerTab" data-tab="overview">Overview</button>
                    <button class="tab-button ${currentServerTab === 'contributions' ? 'active' : ''}" data-action="switchServerTab" data-tab="contributions">Contributions</button>
                    <button class="tab-button ${currentServerTab === 'settings' ? 'active' : ''}" data-action="switchServerTab" data-tab="settings">Settings</button>
                </div>
                ${details.hasInstaller ? buildInstallerControlsHTML(details.id, 'installLspServer') : ''}
            </div>
            <div class="tab-content">
                <div id="server-overview-tab" class="tab-panel ${currentServerTab === 'overview' ? 'active' : ''}">
                    <div class="details-panel text-primary overflow-auto p-2xl">
                        ${detailsHTML}
                        <div class="p-lg bg-panel rounded-sm mt-2xl border-left-accent">
                            <strong>Note:</strong> To run this server, open a workspace using an MCP client.
                        </div>
                        ${buildInstallOutputHTML()}
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
            </div>
        `;

        const consoleArea = document.getElementById('console-area');
        consoleArea.innerHTML = html;

        restoreInstallOutput(serverId, 'server-install-output');

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

        <div class="detail-row">
            <span class="detail-label">Server ID:</span>
            <span class="detail-value"><code>${details.id}</code></span>
        </div>

        ${details.description ? `
        <div class="detail-row">
            <span class="detail-label">Description:</span>
            <span class="detail-value">${details.description}</span>
        </div>
        ` : ''}

        ${details.url ? `
        <div class="detail-row">
            <span class="detail-label">URL:</span>
            <span class="detail-value"><a href="${details.url}" target="_blank" class="link-accent">${details.url}</a></span>
        </div>
        ` : ''}

        <div class="detail-row">
            <span class="detail-label">Command:</span>
            <span class="detail-value">${commandHTML}</span>
        </div>

        ${details.args && details.args.length > 0 ? `
        <div class="detail-row">
            <span class="detail-label">Arguments:</span>
            <span class="detail-value">${details.args.map(arg => `<code>${arg}</code>`).join(' ')}</span>
        </div>
        ` : ''}

        ${renderRuntimeSection(details)}

        ${renderExtensionSection(details)}

        ${details.installDir ? `
        <div class="detail-row">
            <span class="detail-label">Install Path:</span>
            <span class="detail-value"><code>${details.installDir}</code></span>
        </div>
        ` : ''}

        <div class="mb-lg">
            <strong class="text-label">Supported Languages/Files:</strong>
            ${docSelectorHTML}
        </div>

    `;
}

function buildSettingsHTML(details) {
    return buildServerSettingsHTML('lsp', details, 'updateServerSetting', details.settings);
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
 * Switch between LSP server tabs (Overview/Contributions/Settings).
 */
export function switchServerTab(tabName) {
    currentServerTab = tabName;
    switchServerTabs('server', tabName);
}

/**
 * Install an LSP server (force, no check).
 */
async function installLspServer(serverId) {
    switchServerTab('overview');
    const installUrl = `${getServerApiBase(serverId)}/${serverId}/install`;
    return runServerInstaller(serverId, true, 'server-install-output', installUrl);
}

/**
 * Run installer for an LSP server (called from workspace view).
 */
export async function runInstaller(serverId, force, workspaceUri) {
    let installUrl = `${getServerApiBase(serverId)}/${serverId}/install`;
    if (workspaceUri) {
        installUrl += `?workspaceUri=${encodeURIComponent(workspaceUri)}`;
    }
    return runServerInstaller(serverId, force, 'server-install-output', installUrl);
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
    changeServerTraceLevel('lsp', serverId, level);
}

async function toggleLspServerEnabled(serverId, enabled) {
    toggleServerEnabled('lsp', serverId, enabled, state.lspConfigs, () => loadAllLspServers(selectedAllServer));
}

export function loadInstallerJson(serverId) {
    loadInstallerJsonEditor(serverId, 'installer-json-editor');
}

export function saveInstallerJson(serverId) {
    saveInstallerJsonEditor(serverId, 'installer-json-editor');
}

export function resetInstallerJson(serverId) {
    loadInstallerJsonEditor(serverId, 'installer-json-editor');
}

// Register event delegation actions
registerActions('click', {
    showServerDetails: (el) => showServerDetails(el.dataset.serverId),
    switchServerTab: (el) => switchServerTab(el.dataset.tab),
    installLspServer: (el) => installLspServer(el.dataset.serverId),
});

registerActions('change', {
    toggleLspServerEnabled: (el) => toggleLspServerEnabled(el.dataset.serverId, el.checked),
    updateServerSetting: (el) => updateServerSetting(el.dataset.serverId, el.dataset.settingKey,
        el.type === 'checkbox' ? String(el.checked) : el.value),
});

registerActions('input', {
});
