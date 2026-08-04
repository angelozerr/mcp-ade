/**
 * Admin UI - LSP (Language Server Protocol) Global Management
 *
 * Handles global LSP server listing with Overview/Install tabs
 */

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
    const extensionBadge = server.isExtension ? ' <span class="text-secondary" style="font-size: 0.85em;">(Extension)</span>' : '';
    const serverIcon = server.isExtension ? '🧩' : '🚀';
    const contributeInfo = formatGlobalContributeInfo(server, contributedByMap);
    return `
        <div class="server-item ${isActive} ${extensionClass} ${disabledClass}" onclick="showServerDetails('${server.id}')">
            <div class="server-name d-flex align-center justify-between">
                <span>
                    <span class="server-source-icon">${serverIcon}</span>
                    ${server.name}${extensionBadge}
                </span>
                <label class="toggle-switch" onclick="event.stopPropagation()">
                    <input type="checkbox" ${server.enabled !== false ? 'checked' : ''} onchange="toggleLspServerEnabled('${server.id}', this.checked)">
                    <span class="toggle-slider"></span>
                </label>
            </div>
            <div class="server-id" ${contributeInfo.tooltip ? `title="${contributeInfo.tooltip}"` : ''}>${server.id}${contributeInfo.text}</div>
        </div>
    `;
}

async function loadAllLspServers(serverIdToSelect) {
    try {
        const lspServers = Object.values(window.lspConfigs || {}).sort((a, b) => (a.name || '').localeCompare(b.name || ''));
        const dapServers = Object.values(window.dapConfigs || {}).map(s => ({...s, isDap: true}));
        const allServers = [...lspServers, ...dapServers];

        const container = document.getElementById('lsp-servers-list');
        if (!container) {
            console.error('lsp-servers-list container not found');
            return;
        }

        if (!lspLanguageFilter) {
            lspLanguageFilter = new LanguageFilter(container, () => window.lspConfigs, () => loadAllLspServers(selectedAllServer));
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
async function showServerDetails(serverId) {
    // Update selected server
    selectedAllServer = serverId;

    // Re-render server list to update active state
    const lspServers = Object.values(window.lspConfigs || {}).sort((a, b) => (a.name || '').localeCompare(b.name || ''));
    const dapServers = Object.values(window.dapConfigs || {}).map(s => ({...s, isDap: true}));
    const allServers = [...lspServers, ...dapServers];
    const contributedByMap = buildGlobalContributedByMap(allServers);

    if (lspLanguageFilter) {
        const filteredServers = lspLanguageFilter.filterServers(lspServers);
        lspLanguageFilter.getItemsContainer().innerHTML = filteredServers.map(server =>
            renderLspServerItem(server, contributedByMap)
        ).join('');
    }

    const details = window.lspConfigs[serverId];
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

        const lspTraceLevel = (window.traceLevels && window.traceLevels['lsp.' + serverId]) || 'off';

        const html = `
            <div class="console-header">
                <div class="console-title">
                    <span class="server-source-icon">${serverIcon}</span>
                    ${details.name || details.id}
                </div>
                <div class="console-tabs">
                    <button class="tab-button ${currentServerTab === 'overview' ? 'active' : ''}" onclick="switchServerTab('overview')">Overview</button>
                    <button class="tab-button ${currentServerTab === 'contributions' ? 'active' : ''}" onclick="switchServerTab('contributions')">Contributions</button>
                    <button class="tab-button ${currentServerTab === 'install' ? 'active' : ''}" onclick="switchServerTab('install')">Install</button>
                </div>
                <div class="console-controls">
                    ${TraceRenderer.renderTraceControls('lsp-server-trace', lspTraceLevel, `changeLspServerTraceLevel('${serverId}', this.value)`)}
                </div>
            </div>
            <div class="tab-content">
                <div id="server-overview-tab" class="tab-panel ${currentServerTab === 'overview' ? 'active' : ''}">
                    <div class="details-panel text-primary overflow-auto" style="padding: 2rem;">
                        ${detailsHTML}
                        <div class="p-lg bg-panel rounded-sm" style="margin-top: 2rem; border-left: 3px solid var(--accent-primary);">
                            <strong>Note:</strong> To run this server, open a workspace using an MCP client.
                        </div>
                    </div>
                </div>
                <div id="server-contributions-tab" class="tab-panel ${currentServerTab === 'contributions' ? 'active' : ''}">
                    <div id="server-diagram-container" class="w-100 bg-card" style="height: 400px; flex-shrink: 0;"></div>
                    <div class="diagram-resizer"></div>
                    <div class="details-panel text-primary flex-1 min-h-0 overflow-auto" style="padding: 2rem;">
                        ${contributionsHTML || '<p class="detail-value">No contributions</p>'}
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
                                    <button class="editor-btn" onclick="saveInstallerJson('${details.id}')" title="Save">💾 Save</button>
                                    <button class="editor-btn" onclick="resetInstallerJson('${details.id}')" title="Reset">↻ Reset</button>
                                    <span class="editor-separator"></span>
                                    <button class="editor-btn install-run-btn" onclick="runInstaller('${details.id}', false)" title="Install (check first, skip if already installed)">▶ Install</button>
                                    <button class="editor-btn install-force-btn" onclick="runInstaller('${details.id}', true)" title="Force Install (skip check, always re-install)">⟳ Force Install</button>
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
        window.currentDiagramServers = allServers;
        window.currentDiagramServerId = details.id;

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
    // Document selector
    let docSelectorHTML = '<p class="text-secondary">None configured</p>';
    if (details.documentSelector && details.documentSelector.length > 0) {
        docSelectorHTML = details.documentSelector.map(selector => {
            return `<div class="selector-item">
                ${selector.language ? `<span class="selector-tag">language: ${selector.language}</span>` : ''}
                ${selector.scheme ? `<span class="selector-tag">scheme: ${selector.scheme}</span>` : ''}
                ${selector.pattern ? `<span class="selector-tag">pattern: ${selector.pattern}</span>` : ''}
            </div>`;
        }).join('');
    }

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
        <h3 class="text-label" style="margin-top: 0;">Server Information</h3>

        <div style="margin-bottom: 1.5rem;">
            <strong class="text-label">Server ID:</strong>
            <p class="text-value" style="margin: 0.25rem 0;"><code>${details.id}</code></p>
        </div>

        ${details.description ? `
        <div style="margin-bottom: 1.5rem;">
            <strong class="text-label">Description:</strong>
            <p class="text-value" style="margin: 0.25rem 0;">${details.description}</p>
        </div>
        ` : ''}

        ${details.url ? `
        <div style="margin-bottom: 1.5rem;">
            <strong class="text-label">URL:</strong>
            <p style="margin: 0.25rem 0;"><a href="${details.url}" target="_blank" style="color: var(--accent-primary); text-decoration: none;">${details.url}</a></p>
        </div>
        ` : ''}

        <div style="margin-bottom: 1.5rem;">
            <strong class="text-label">Command:</strong>
            <p class="text-value" style="margin: 0.25rem 0;">${commandHTML}</p>
        </div>

        ${details.args && details.args.length > 0 ? `
        <div style="margin-bottom: 1.5rem;">
            <strong class="text-label">Arguments:</strong>
            <ul class="text-value" style="margin: 0.5rem 0; padding-left: 1.5rem;">
                ${details.args.map(arg => `<li><code>${arg}</code></li>`).join('')}
            </ul>
        </div>
        ` : ''}

        <div style="margin-bottom: 1.5rem;">
            <strong class="text-label">Supported Languages/Files:</strong>
            ${docSelectorHTML}
        </div>

        ${buildSettingsHTML(details)}
    `;
}

/**
 * Build settings HTML from declarative settings in server.json.
 * Renders controls dynamically based on setting type (enum, boolean, string).
 */
function buildSettingsHTML(details) {
    if (!details.settings || details.settings.length === 0) {
        return '';
    }

    const serverId = details.id;

    let controlsHTML = details.settings.map(setting => {
        let controlHTML = '';
        const currentValue = setting.currentValue || setting.defaultValue || '';

        if (setting.type === 'enum' && setting.values) {
            const options = setting.values.map(v => {
                const label = (setting.valueLabels && setting.valueLabels[v]) ? setting.valueLabels[v] : v;
                const selected = v === currentValue ? 'selected' : '';
                return `<option value="${v}" ${selected}>${label}</option>`;
            }).join('');
            controlHTML = `<select class="select-field" onchange="updateServerSetting('${serverId}', '${setting.key}', this.value)"
                                   style="padding: 0.3rem 0.5rem; font-size: 0.85rem; min-width: 200px;">
                               ${options}
                           </select>`;
        } else if (setting.type === 'boolean') {
            const checked = currentValue === 'true' ? 'checked' : '';
            controlHTML = `<label class="toggle-switch">
                               <input type="checkbox" ${checked}
                                      onchange="updateServerSetting('${serverId}', '${setting.key}', this.checked ? 'true' : 'false')">
                               <span class="toggle-slider"></span>
                           </label>`;
        } else {
            controlHTML = `<input type="text" class="input-field" value="${currentValue}"
                                  onchange="updateServerSetting('${serverId}', '${setting.key}', this.value)"
                                  style="padding: 0.3rem 0.5rem; font-size: 0.85rem; min-width: 200px;">`;
        }

        const descHTML = setting.description
            ? `<span class="text-dimmed ml-sm" style="font-size: 0.8rem;">${setting.description}</span>`
            : '';

        return `<div class="mb-md d-flex align-center gap-md">
                    <strong class="text-label" style="min-width: 120px;">${setting.label}:</strong>
                    ${controlHTML}
                    ${descHTML}
                </div>`;
    }).join('');

    return `
        <div style="margin-bottom: 1.5rem;">
            <h3 class="text-label mt-lg">Settings</h3>
            ${controlsHTML}
        </div>
    `;
}

/**
 * Update a server setting via the REST API and sync in-memory state.
 * @param {string} serverId - Server ID (e.g. "jdtls")
 * @param {string} settingKey - Setting key (e.g. "java.import.mode")
 * @param {string} value - New value
 */
function updateServerSetting(serverId, settingKey, value) {
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
        const config = window.lspConfigs && window.lspConfigs[serverId];
        if (config && config.settings) {
            const setting = config.settings.find(s => s.key === settingKey);
            if (setting) {
                setting.currentValue = value;
            }
        }
    }).catch(err => console.error('Error updating setting:', err));
}

/**
 * Build contributions HTML for Contributions tab.
 */
function buildContributionsHTML(details) {
    if (!details.contributes) return '';

    let html = '<h3 class="text-success" style="margin-top: 0;">Contributions</h3>';

    if (details.contributes.languages) {
        html += `
            <div style="margin-bottom: 1.5rem;">
                <strong class="text-label">Languages:</strong>
                <ul class="text-value" style="margin: 0.5rem 0; padding-left: 1.5rem;">
                    ${details.contributes.languages.map(lang =>
                        `<li><strong>${lang.id}</strong>${lang.extensions ? ` (${lang.extensions.join(', ')})` : ''}</li>`
                    ).join('')}
                </ul>
            </div>
        `;
    }

    if (details.contributes.snippets) {
        html += `
            <div style="margin-bottom: 1.5rem;">
                <strong class="text-label">Snippets:</strong>
                <p class="text-value" style="margin: 0.25rem 0;">${details.contributes.snippets.length} snippet file(s)</p>
            </div>
        `;
    }

    return html;
}

/**
 * Switch between LSP server tabs (Overview/Contributions/Install).
 */
function switchServerTab(tabName) {
    currentServerTab = tabName;

    // Re-render current server to update tabs
    if (selectedAllServer) {
        showServerDetails(selectedAllServer);
    }

    // Render diagram when switching to contributions tab
    if (tabName === 'contributions' && window.currentDiagramServers && window.currentDiagramServerId) {
        setTimeout(() => renderServerDiagram(window.currentDiagramServers, window.currentDiagramServerId), 100);
    }
}

/**
 * Load installer.json for an LSP or DAP server.
 */
async function loadInstallerJson(serverId) {
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
async function saveInstallerJson(serverId) {
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

        if (window.showAlert) {
            window.showAlert('Success', 'Installer configuration saved successfully.');
        }
    } catch (error) {
        console.error('Failed to save installer.json:', error);
        if (window.showAlert) {
            window.showAlert('Error', 'Failed to save installer.json: ' + error.message);
        }
    }
}

/**
 * Reset installer.json to original.
 */
function resetInstallerJson(serverId) {
    loadInstallerJson(serverId);
}

/**
 * Run installer for an LSP server.
 */
async function runInstaller(serverId, force) {
    const outputDiv = document.getElementById('install-output');
    if (!outputDiv) return;

    const label = force ? 'Force installing' : 'Installing';
    outputDiv.innerHTML = `
        <div class="install-output-header text-success mb-sm">${label} ${serverId}...</div>
        <div id="install-progress-bar" class="bg-input mb-sm d-none" style="height: 4px; border-radius: 2px;">
            <div id="install-progress-fill" style="height: 100%; background: var(--color-success); border-radius: 2px; width: 0%; transition: width 0.3s;"></div>
        </div>
        <div id="install-traces" class="font-mono bg-card p-sm rounded-sm" style="font-size: 12px; max-height: 300px; overflow-y: auto;"></div>
    `;

    window.installOutputServerId = serverId;

    try {
        const url = `${getServerApiBase(serverId)}/${serverId}/install${force ? '?force=true' : ''}`;
        const response = await fetch(url, { method: 'POST' });

        if (!response.ok) {
            window.installOutputServerId = null;
            throw new Error('Installation failed');
        }
    } catch (error) {
        console.error('Failed to run installer:', error);
        window.installOutputServerId = null;
        outputDiv.innerHTML = `<div class="text-error">✗ Installation failed: ${error.message}</div>`;
    }
}

/**
 * Append an installation trace to the install output panel.
 */
function appendInstallTrace(trace) {
    const tracesDiv = document.getElementById('install-traces');
    if (!tracesDiv) return;

    const color = trace.messageType === 'ERROR' ? 'var(--color-error-text)'
        : trace.messageType === 'UPDATE' ? 'var(--text-secondary)'
        : 'var(--text-code)';

    if (trace.messageType === 'UPDATE') {
        const lastLine = tracesDiv.lastElementChild;
        if (lastLine && lastLine.dataset.update === 'true') {
            lastLine.textContent = trace.content;
            return;
        }
    }

    const line = document.createElement('div');
    line.style.color = color;
    line.textContent = trace.content;
    if (trace.messageType === 'UPDATE') {
        line.dataset.update = 'true';
    }
    tracesDiv.appendChild(line);
    tracesDiv.scrollTop = tracesDiv.scrollHeight;
}

/**
 * Update the install output progress bar.
 */
function updateInstallProgress(msg) {
    const bar = document.getElementById('install-progress-bar');
    const fill = document.getElementById('install-progress-fill');
    const header = document.querySelector('.install-output-header');

    if (bar && fill) {
        bar.style.display = 'block';
        fill.style.width = `${Math.round((msg.progress || 0) * 100)}%`;
    }

    if (msg.status === 'completed') {
        window.installOutputServerId = null;
        if (fill) fill.style.background = 'var(--color-success)';
        if (header) {
            header.style.color = 'var(--color-success)';
            header.textContent = `✓ Installation completed`;
        }
    } else if (msg.status === 'failed') {
        window.installOutputServerId = null;
        if (fill) fill.style.background = 'var(--color-error-text)';
        if (header) {
            header.style.color = 'var(--color-error-text)';
            header.textContent = `✗ Installation failed`;
        }
    }
}

/**
 * Helper: Build contributedBy map.
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
 * Helper: Format contribute info for server list.
 */
function formatGlobalContributeInfo(server, contributedByMap) {
    const contributors = contributedByMap[server.id] || [];
    if (contributors.length === 0) return { text: '', tooltip: '' };

    const text = ` ← ${contributors.length}`;
    const tooltip = `Contributions from: ${contributors.join(', ')}`;
    return { text, tooltip };
}

// renderServerDiagram() is defined in diagram.js - do not override it here

async function changeLspServerTraceLevel(serverId, level) {
    if (window.traceLevels) {
        window.traceLevels['lsp.' + serverId] = level;
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
            if (window.lspConfigs[serverId]) {
                window.lspConfigs[serverId].enabled = enabled;
            }
            // Re-render the list
            loadAllLspServers(selectedAllServer);
        }
    } catch (error) {
        console.error(`Failed to ${action} LSP server:`, error);
    }
}

// Expose functions globally
window.toggleLspServerEnabled = toggleLspServerEnabled;
window.loadAllLspServers = loadAllLspServers;
window.showServerDetails = showServerDetails;
window.switchServerTab = switchServerTab;
window.loadInstallerJson = loadInstallerJson;
window.saveInstallerJson = saveInstallerJson;
window.resetInstallerJson = resetInstallerJson;
window.runInstaller = runInstaller;
window.appendInstallTrace = appendInstallTrace;
window.updateInstallProgress = updateInstallProgress;
window.buildGlobalContributedByMap = buildGlobalContributedByMap;
window.formatGlobalContributeInfo = formatGlobalContributeInfo;
window.renderServerDiagram = renderServerDiagram;
window.changeLspServerTraceLevel = changeLspServerTraceLevel;
