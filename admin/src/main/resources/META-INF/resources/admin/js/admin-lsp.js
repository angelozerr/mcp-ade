/**
 * Admin UI - LSP (Language Server Protocol) Global Management
 *
 * Handles global LSP server listing with Overview/Contributions/Settings tabs
 */

import { state, getServerApiBase, ensureLspConfigs, ensureLspConfigDetail } from './shared-state.js';
import {
    renderLoadingPlaceholder, renderDocumentSelector, renderRuntimeSection, renderExtensionSection, runServerInstaller,
    switchServerTabs, toggleServerEnabled, changeServerTraceLevel, buildServerSettingsHTML,
    selectListItem, buildInstallOutputHTML, buildInstallerControlsHTML, loadInstallerJsonEditor, saveInstallerJsonEditor,
    getInstallStatusBadge, renderServerNameHeader, restoreInstallOutput
} from './shared-ui.js';
import { formatContributionsSection } from './shared-contributions.js';
import { renderServerDiagram } from './diagram.js';
import { LanguageFilter } from './language-filter.js';
import { registerActions } from './event-delegation.js';
import { showToast } from './toast.js';

let selectedAllServer = null; // Track selected server in global Servers tab
let currentServerTab = 'overview'; // Track current tab: overview, contributions, settings
let lspLanguageFilter = null;

function renderLspServerItem(server) {
    const isActive = selectedAllServer === server.id ? 'active' : '';
    const extensionClass = server.isExtension ? 'server-extension' : '';
    const disabledClass = !server.enabled ? 'server-disabled' : '';
    const extensionBadge = server.isExtension ? ' <span class="text-secondary font-md">(Extension)</span>' : '';
    const serverIcon = server.isExtension ? '🧩' : '🚀';
    return `
        <div class="server-item ${isActive} ${extensionClass} ${disabledClass}" data-action="showServerDetails" data-server-id="${server.id}">
            ${renderServerNameHeader(server, { icon: serverIcon, nameExtra: extensionBadge, toggleAction: 'toggleLspServerEnabled' })}
            <div class="server-id">${server.id}</div>
        </div>
    `;
}

export async function loadAllLspServers(serverIdToSelect) {
    try {
        const container = document.getElementById('lsp-servers-list');
        if (!container) {
            console.error('lsp-servers-list container not found');
            return;
        }

        if (!state.lspConfigs) {
            container.innerHTML = renderLoadingPlaceholder();
        }

        await ensureLspConfigs();
        const lspServers = Object.values(state.lspConfigs || {}).sort((a, b) => (a.name || '').localeCompare(b.name || ''));

        if (!lspLanguageFilter) {
            lspLanguageFilter = new LanguageFilter(container, () => state.lspConfigs, () => loadAllLspServers(selectedAllServer));
        }

        const filteredServers = lspLanguageFilter.filterServers(lspServers);

        lspLanguageFilter.getItemsContainer().innerHTML = filteredServers.map(server =>
            renderLspServerItem(server)
        ).join('');

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
    const previousServer = selectedAllServer;
    selectedAllServer = serverId;

    if (lspLanguageFilter) {
        selectListItem(lspLanguageFilter.getItemsContainer(),
            '.server-item[data-server-id', previousServer, serverId, scroll);
    }

    const contentArea = document.querySelector('.content-area');
    const consoleColumn = document.querySelector('.console-container');
    consoleColumn.style.display = 'flex';
    contentArea.style.gridTemplateColumns = '400px 1fr';
    consoleColumn.style.gridColumn = '2';

    const details = state.lspConfigs?.[serverId];
    if (!details) {
        console.error('Server not found:', serverId);
        return;
    }

    renderServerDetailsHTML(serverId, details);

    if (!details._detailLoaded) {
        await ensureLspConfigDetail(serverId);
        if (selectedAllServer !== serverId) return;
        const detailSection = document.getElementById('server-detail-section');
        if (detailSection) {
            detailSection.innerHTML = buildServerDetailHTML(details);
        }
        const settingsPanel = document.getElementById('server-settings-content');
        if (settingsPanel) {
            settingsPanel.innerHTML = buildSettingsHTML(details);
        }
    }

    restoreInstallOutput(serverId, 'server-install-output');

    if (currentServerTab === 'contributions') {
        refreshContributionsTab();
    }
}

function renderServerDetailsHTML(serverId, details) {
    const serverIcon = details.isExtension ? '🧩' : '🚀';

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
                    ${buildServerSummaryHTML(details)}
                    <div id="server-detail-section">
                        ${details._detailLoaded ? buildServerDetailHTML(details) : renderLoadingPlaceholder()}
                    </div>
                </div>
            </div>
            <div id="server-contributions-tab" class="tab-panel ${currentServerTab === 'contributions' ? 'active' : ''}">
                <div id="server-diagram-container" class="w-100 bg-card diagram-container"></div>
                <div class="diagram-resizer"></div>
                <div class="details-panel text-primary flex-1 min-h-0 overflow-auto p-2xl">
                    <p class="detail-value">Loading...</p>
                </div>
            </div>
            <div id="server-settings-tab" class="tab-panel ${currentServerTab === 'settings' ? 'active' : ''}">
                <div class="details-panel text-primary overflow-auto p-2xl" id="server-settings-content">
                    ${details._detailLoaded ? buildSettingsHTML(details) : renderLoadingPlaceholder()}
                </div>
            </div>
        </div>
    `;

    document.getElementById('console-area').innerHTML = html;
}

function buildServerSummaryHTML(details) {
    const docSelectorHTML = renderDocumentSelector(details.documentSelector);

    return `
        <h3 class="text-label mt-0">Server Information</h3>

        <div class="detail-row">
            <span class="detail-label">Server ID:</span>
            <span class="detail-value"><code>${details.id}</code></span>
        </div>

        <div class="mb-lg">
            <strong class="text-label">Supported Languages/Files:</strong>
            ${docSelectorHTML}
        </div>
    `;
}

function buildServerDetailHTML(details) {
    let commandHTML;
    if (details.command) {
        if (typeof details.command === 'string') {
            commandHTML = `<code>${details.command}</code>`;
        } else {
            commandHTML = Object.entries(details.command).map(([os, cmd]) =>
                `<div class="mb-xs"><strong>${os}:</strong> <code>${cmd}</code></div>`
            ).join('');
        }
    } else if (details.hasInstaller) {
        commandHTML = '<p class="text-secondary">Requires installation</p>';
    } else {
        commandHTML = '<p class="text-secondary">None (contribution-only server)</p>';
    }

    return `
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

        <div class="p-lg bg-panel rounded-sm mt-2xl border-left-accent">
            <strong>Note:</strong> To run this server, open a workspace using an MCP client.
        </div>
        ${buildInstallOutputHTML()}
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
        showToast('Settings saved');
    }).catch(err => console.error('Error updating setting:', err));
}

async function refreshContributionsTab() {
    const serverId = selectedAllServer;
    if (!serverId) return;

    const contributionsPanel = document.querySelector('#server-contributions-tab .details-panel');
    try {
        const response = await fetch(`/api/admin/lsp/configs/${serverId}/contributions`);
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
        console.error('Failed to load contributions:', error);
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

/**
 * Switch between LSP server tabs (Overview/Contributions/Settings).
 */
export async function switchServerTab(tabName) {
    currentServerTab = tabName;
    switchServerTabs('server', tabName);

    if (tabName === 'contributions') {
        await refreshContributionsTab();
    }
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

export function refreshServerDetailAfterInstall(serverId) {
    if (selectedAllServer !== serverId) return;
    const config = state.lspConfigs?.[serverId];
    if (!config) return;
    const detailSection = document.getElementById('server-detail-section');
    if (detailSection) {
        detailSection.innerHTML = buildServerDetailHTML(config);
    }
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
