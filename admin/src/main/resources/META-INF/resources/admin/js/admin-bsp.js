/**
 * Admin UI - BSP (Build Server Protocol) Global Management
 *
 * Handles global BSP server listing with Overview/Settings tabs
 */

import { state, updateSearchBoxVisibility, ensureBspConfigs } from './shared-state.js';
import {
    renderLoadingPlaceholder, renderExtensionSection, runServerInstaller,
    switchServerTabs, toggleServerEnabled, changeServerTraceLevel, buildServerSettingsHTML,
    selectListItem, buildInstallOutputHTML, buildInstallerControlsHTML,
    getInstallStatusBadge, renderServerNameHeader, restoreInstallOutput
} from './shared-ui.js';
import { LanguageFilter } from './language-filter.js';
import { registerActions } from './event-delegation.js';

let selectedBspServer = null;
let currentBspServerTab = 'overview';
let bspServerConfigs = {};
let bspLanguageFilter = null;

function renderBspServerItem(server) {
    const isActive = selectedBspServer === server.id ? 'active' : '';
    const disabledClass = server.enabled === false ? 'server-disabled' : '';
    return `
        <div class="server-item ${isActive} ${disabledClass}" data-action="showBspServerDetails" data-server-id="${server.id}">
            ${renderServerNameHeader(server, { icon: '🔨', toggleAction: 'toggleBspServerEnabled' })}
            <div class="server-id">${server.id}</div>
        </div>
    `;
}

export async function loadAllBspServers(serverIdToSelect) {
    try {
        const container = document.getElementById('bsp-servers-list');
        if (!container) {
            console.error('bsp-servers-list container not found');
            return;
        }

        if (!state.bspConfigs) {
            container.innerHTML = renderLoadingPlaceholder();
        }

        await ensureBspConfigs();
        bspServerConfigs = state.bspConfigs || {};
        const bspServers = Object.values(bspServerConfigs).sort((a, b) => (a.name || '').localeCompare(b.name || ''));

        if (!bspLanguageFilter) {
            bspLanguageFilter = new LanguageFilter(container, () => bspServerConfigs, () => loadAllBspServers(selectedBspServer));
        }

        if (bspServers.length === 0) {
            bspLanguageFilter.getItemsContainer().innerHTML = '<div class="servers-placeholder">No build servers configured</div>';
            return;
        }

        const filteredServers = bspLanguageFilter.filterServers(bspServers);

        bspLanguageFilter.getItemsContainer().innerHTML = filteredServers.map(server =>
            renderBspServerItem(server)
        ).join('');

        if (filteredServers.length > 0) {
            let serverToShow;
            if (serverIdToSelect && filteredServers.find(s => s.id === serverIdToSelect)) {
                serverToShow = serverIdToSelect;
            } else if (selectedBspServer && filteredServers.find(s => s.id === selectedBspServer)) {
                serverToShow = selectedBspServer;
            } else {
                serverToShow = filteredServers[0].id;
            }
            showBspServerDetails(serverToShow, true);
        }
    } catch (error) {
        console.error('Failed to load BSP servers:', error);
    }
}

export async function showBspServerDetails(serverId, scroll) {
    const previousServer = selectedBspServer;
    selectedBspServer = serverId;

    updateSearchBoxVisibility(false);

    if (bspLanguageFilter) {
        selectListItem(bspLanguageFilter.getItemsContainer(),
            '.server-item[data-server-id', previousServer, serverId, scroll);
    }

    const server = bspServerConfigs[serverId];
    if (!server) {
        console.error('BSP server not found:', serverId);
        return;
    }

    const contentArea = document.querySelector('.content-area');
    const consoleColumn = document.querySelector('.console-container');
    consoleColumn.style.display = 'flex';
    contentArea.style.gridTemplateColumns = '400px 1fr';
    consoleColumn.style.gridColumn = '2';

    const detailsHTML = `
        <h3 class="text-success mt-0">Build Server Information</h3>

        <div class="detail-row">
            <span class="detail-label">Server ID:</span>
            <span class="detail-value"><code>${server.id}</code></span>
        </div>

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

        ${renderExtensionSection(server)}

        ${server.installDir ? `
        <div class="detail-row">
            <span class="detail-label">Install Path:</span>
            <span class="detail-value"><code>${server.installDir}</code></span>
        </div>
        ` : ''}

        <div class="p-lg bg-panel rounded mt-2xl border-left-success">
            <strong>Note:</strong> Build servers are started on-demand when build tools are invoked. They are not automatically started with workspaces.
        </div>
    `;

    const html = `
        <div class="console-header">
            <div class="console-title">
                <span class="server-source-icon">🔨</span>
                ${server.name || server.id}
                <span class="console-install-badge" data-server-id="${server.id}">${getInstallStatusBadge(server)}</span>
            </div>
            <div class="console-tabs">
                <button class="tab-button ${currentBspServerTab === 'overview' ? 'active' : ''}" data-action="switchBspServerTab" data-tab="overview">Overview</button>
                <button class="tab-button ${currentBspServerTab === 'settings' ? 'active' : ''}" data-action="switchBspServerTab" data-tab="settings">Settings</button>
            </div>
            ${server.hasInstaller ? buildInstallerControlsHTML(server.id, 'installBspServer') : ''}
        </div>
        <div class="tab-content">
            <div id="bsp-server-overview-tab" class="tab-panel ${currentBspServerTab === 'overview' ? 'active' : ''}">
                <div class="details-panel text-primary detail-content">
                    ${detailsHTML}
                    ${buildInstallOutputHTML()}
                </div>
            </div>
            <div id="bsp-server-settings-tab" class="tab-panel ${currentBspServerTab === 'settings' ? 'active' : ''}">
                <div class="details-panel text-primary overflow-auto p-2xl">
                    ${buildBspSettingsHTML(server)}
                </div>
            </div>
        </div>
    `;

    document.getElementById('console-area').innerHTML = html;

    restoreInstallOutput(serverId, 'server-install-output');
}

export function switchBspServerTab(tab) {
    currentBspServerTab = tab;
    switchServerTabs('bsp-server', tab);
}

function buildBspSettingsHTML(server) {
    return buildServerSettingsHTML('bsp', server, 'updateBspServerSetting');
}

function updateBspServerSetting(serverId, settingKey, value) {
    if (settingKey === 'trace') {
        changeServerTraceLevel('bsp', serverId, value);
    }
}

async function installBspServer(serverId) {
    switchBspServerTab('overview');
    const installUrl = `/api/admin/bsp/configs/${serverId}/install`;
    return runServerInstaller(serverId, true, 'server-install-output', installUrl);
}

async function toggleBspServerEnabled(serverId, enabled) {
    toggleServerEnabled('bsp', serverId, enabled, bspServerConfigs, () => loadAllBspServers(selectedBspServer));
}

// Register event delegation actions
registerActions('click', {
    showBspServerDetails: (el) => showBspServerDetails(el.dataset.serverId),
    switchBspServerTab: (el) => switchBspServerTab(el.dataset.tab),
    installBspServer: (el) => installBspServer(el.dataset.serverId),

});

registerActions('change', {
    toggleBspServerEnabled: (el) => toggleBspServerEnabled(el.dataset.serverId, el.checked),
    updateBspServerSetting: (el) => updateBspServerSetting(el.dataset.serverId, el.dataset.settingKey, el.value),
});
