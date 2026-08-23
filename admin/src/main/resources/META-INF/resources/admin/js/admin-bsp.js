/**
 * Admin UI - BSP (Build Server Protocol) Global Management
 *
 * Handles global BSP server listing with Overview/Settings/Install tabs
 */

import { state, updateSearchBoxVisibility } from './shared-state.js';
import {
    renderExtensionSection, runServerInstaller,
    loadInstallerJsonEditor, saveInstallerJsonEditor,
    switchServerTabs, toggleServerEnabled, changeServerTraceLevel, buildServerSettingsHTML
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
            <div class="server-name d-flex align-center justify-between">
                <span>
                    <span class="server-source-icon">🔨</span>
                    ${server.name}
                </span>
                <label class="toggle-switch" onclick="event.stopPropagation()">
                    <input type="checkbox" ${server.enabled !== false ? 'checked' : ''} data-action="toggleBspServerEnabled" data-server-id="${server.id}">
                    <span class="toggle-slider"></span>
                </label>
            </div>
            <div class="server-id">${server.id}</div>
        </div>
    `;
}

export async function loadAllBspServers(serverIdToSelect) {
    try {
        let bspServers;
        if (state.bspConfigs && Object.keys(state.bspConfigs).length > 0) {
            bspServers = Object.values(state.bspConfigs).sort((a, b) => (a.name || '').localeCompare(b.name || ''));
        } else {
            const response = await fetch('/api/admin/bsp/configs');
            bspServers = (await response.json()).sort((a, b) => (a.name || '').localeCompare(b.name || ''));
        }

        bspServerConfigs = {};
        bspServers.forEach(server => {
            bspServerConfigs[server.id] = server;
        });
        state.bspConfigs = bspServerConfigs;

        const container = document.getElementById('bsp-servers-list');
        if (!container) {
            console.error('bsp-servers-list container not found');
            return;
        }

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
            showBspServerDetails(serverToShow);
        }
    } catch (error) {
        console.error('Failed to load BSP servers:', error);
    }
}

export async function showBspServerDetails(serverId) {
    const previousServer = selectedBspServer;
    selectedBspServer = serverId;

    updateSearchBoxVisibility(false);

    if (bspLanguageFilter) {
        const container = bspLanguageFilter.getItemsContainer();
        if (previousServer) {
            const prev = container.querySelector(`.server-item[data-server-id="${previousServer}"]`);
            if (prev) prev.classList.remove('active');
        }
        const next = container.querySelector(`.server-item[data-server-id="${serverId}"]`);
        if (next) {
            next.classList.add('active');
            next.scrollIntoView({ block: 'nearest' });
        }
    }

    const server = bspServerConfigs[serverId];
    if (!server) {
        console.error('BSP server not found:', serverId);
        return;
    }

    const appContainer = document.querySelector('.app-container');
    const consoleColumn = document.querySelector('.console-container');
    consoleColumn.style.display = 'flex';
    appContainer.style.gridTemplateColumns = '400px 1fr';
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

        <div class="p-lg bg-panel rounded mt-2xl border-left-success">
            <strong>Note:</strong> Build servers are started on-demand when build tools are invoked. They are not automatically started with workspaces.
        </div>
    `;

    const html = `
        <div class="console-header">
            <div class="console-title">
                <span class="server-source-icon">🔨</span>
                ${server.name || server.id}
            </div>
            <div class="console-tabs">
                <button class="tab-button ${currentBspServerTab === 'overview' ? 'active' : ''}" data-action="switchBspServerTab" data-tab="overview">Overview</button>
                <button class="tab-button ${currentBspServerTab === 'settings' ? 'active' : ''}" data-action="switchBspServerTab" data-tab="settings">Settings</button>
                <button class="tab-button ${currentBspServerTab === 'install' ? 'active' : ''}" data-action="switchBspServerTab" data-tab="install">Install</button>
            </div>
            <div class="console-controls">
            </div>
        </div>
        <div class="tab-content">
            <div id="bsp-server-overview-tab" class="tab-panel ${currentBspServerTab === 'overview' ? 'active' : ''}">
                <div class="details-panel text-primary detail-content">
                    ${detailsHTML}
                </div>
            </div>
            <div id="bsp-server-settings-tab" class="tab-panel ${currentBspServerTab === 'settings' ? 'active' : ''}">
                <div class="details-panel text-primary overflow-auto p-2xl">
                    ${buildBspSettingsHTML(server)}
                </div>
            </div>
            <div id="bsp-server-install-tab" class="tab-panel ${currentBspServerTab === 'install' ? 'active' : ''}">
                <div class="install-panel">
                    <h3>Installer Configuration</h3>
                    <div class="install-info">
                        <p><strong>Build Server:</strong> ${server.name}</p>
                        <p><strong>ID:</strong> ${server.id}</p>
                    </div>
                    <div class="installer-editor">
                        <div class="editor-header">
                            <span>installer.json</span>
                            <div class="editor-actions">
                                <button class="editor-btn" data-action="saveBspInstallerJson" data-server-id="${server.id}" title="Save">💾 Save</button>
                                <button class="editor-btn" data-action="resetBspInstallerJson" data-server-id="${server.id}" title="Reset">↻ Reset</button>
                                <span class="editor-separator"></span>
                                <button class="editor-btn install-run-btn" data-action="runBspInstaller" data-server-id="${server.id}" data-force="false" title="Install (check first, skip if already installed)">▶ Install</button>
                                <button class="editor-btn install-force-btn" data-action="runBspInstaller" data-server-id="${server.id}" data-force="true" title="Force Install (skip check, always re-install)">⟳ Force Install</button>
                            </div>
                        </div>
                        <textarea id="bsp-installer-json-editor" class="json-editor" spellcheck="false"></textarea>
                    </div>
                    <div id="bsp-install-output" class="install-output"></div>
                </div>
            </div>
        </div>
    `;

    document.getElementById('console-area').innerHTML = html;

    if (currentBspServerTab === 'install') {
        loadBspInstallerJson(server.id);
    }
}

export function switchBspServerTab(tab) {
    currentBspServerTab = tab;
    switchServerTabs('bsp-server', tab, (t) => {
        if (t === 'install' && selectedBspServer) loadBspInstallerJson(selectedBspServer);
    });
}

function buildBspSettingsHTML(server) {
    return buildServerSettingsHTML('bsp', server, 'updateBspServerSetting');
}

function updateBspServerSetting(serverId, settingKey, value) {
    if (settingKey === 'trace') {
        changeServerTraceLevel('bsp', serverId, value);
    }
}

async function loadBspInstallerJson(serverId) {
    loadInstallerJsonEditor(serverId, 'bsp-installer-json-editor');
}

async function saveBspInstallerJson(serverId) {
    saveInstallerJsonEditor(serverId, 'bsp-installer-json-editor');
}

async function resetBspInstallerJson(serverId) {
    loadBspInstallerJson(serverId);
}

async function runBspInstaller(serverId, force) {
    const installUrl = `/api/admin/bsp/configs/${serverId}/install`;
    return runServerInstaller(serverId, force, 'bsp-install-output', installUrl);
}

async function toggleBspServerEnabled(serverId, enabled) {
    toggleServerEnabled('bsp', serverId, enabled, bspServerConfigs, () => loadAllBspServers(selectedBspServer));
}

// Register event delegation actions
registerActions('click', {
    showBspServerDetails: (el) => showBspServerDetails(el.dataset.serverId),
    switchBspServerTab: (el) => switchBspServerTab(el.dataset.tab),
    saveBspInstallerJson: (el) => saveBspInstallerJson(el.dataset.serverId),
    resetBspInstallerJson: (el) => resetBspInstallerJson(el.dataset.serverId),
    runBspInstaller: (el) => runBspInstaller(el.dataset.serverId, el.dataset.force === 'true'),
});

registerActions('change', {
    toggleBspServerEnabled: (el) => toggleBspServerEnabled(el.dataset.serverId, el.checked),
    updateBspServerSetting: (el) => updateBspServerSetting(el.dataset.serverId, el.dataset.settingKey, el.value),
});
