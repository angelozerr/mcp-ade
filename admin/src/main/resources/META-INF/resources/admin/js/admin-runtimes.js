/**
 * Admin UI - Runtimes Management
 *
 * Handles runtime listing with status, dependent servers, and install/check actions.
 */

import { state, updateSearchBoxVisibility } from './shared-state.js';
import { getRuntimeStatusInfo, appendTraceLine, renderServerLink, renderExtensionLink } from './shared-ui.js';
import { registerActions } from './event-delegation.js';

let selectedRuntime = null;
let switchTabCallback = null;
export function setSwitchTabCallback(cb) { switchTabCallback = cb; }

function getStatusIconHTML(runtime) {
    const info = getRuntimeStatusInfo(runtime.status, runtime.autoInstallable);
    const animClass = info.animate ? ' status-checking' : '';
    return `<span class="server-source-icon${animClass}" title="${info.label}">${info.icon}</span>`;
}

function getStatusBadge(runtime) {
    const info = getRuntimeStatusInfo(runtime.status, runtime.autoInstallable);
    return `<span class="badge ${info.badgeClass}">${info.label}</span>`;
}

function renderRuntimeItem(runtime) {
    const isActive = selectedRuntime === runtime.id ? 'active' : '';
    const dependentCount = countDependents(runtime.dependentServers);
    return `
        <div class="server-item ${isActive}" data-action="showRuntimeDetails" data-runtime-id="${runtime.id}">
            <div class="server-name d-flex align-center justify-between">
                <span>
                    ${getStatusIconHTML(runtime)}
                    ${runtime.name}
                </span>
                ${getStatusBadge(runtime)}
            </div>
            <div class="server-id">${runtime.id} &middot; ${dependentCount} server${dependentCount !== 1 ? 's' : ''}</div>
        </div>
    `;
}

function countDependents(dependentServers) {
    if (!dependentServers) return 0;
    let count = 0;
    for (const type in dependentServers) {
        if (Array.isArray(dependentServers[type])) {
            count += dependentServers[type].length;
        }
    }
    return count;
}

export function loadAllRuntimes(runtimeIdToSelect) {
    const runtimes = Object.values(state.runtimeConfigs || {}).sort((a, b) => (a.name || '').localeCompare(b.name || ''));

    const container = document.getElementById('runtimes-list');
    if (!container) {
        console.error('runtimes-list container not found');
        return;
    }

    if (runtimes.length === 0) {
        container.innerHTML = '<div class="servers-placeholder">No runtimes registered</div>';
        return;
    }

    container.innerHTML = runtimes.map(rt => renderRuntimeItem(rt)).join('');

    let runtimeToShow;
    if (runtimeIdToSelect && runtimes.find(r => r.id === runtimeIdToSelect)) {
        runtimeToShow = runtimeIdToSelect;
    } else if (selectedRuntime && runtimes.find(r => r.id === selectedRuntime)) {
        runtimeToShow = selectedRuntime;
    } else {
        runtimeToShow = runtimes[0].id;
    }
    showRuntimeDetails(runtimeToShow);
}

export async function showRuntimeDetails(runtimeId) {
    const previousRuntime = selectedRuntime;
    selectedRuntime = runtimeId;

    updateSearchBoxVisibility(false);

    const container = document.getElementById('runtimes-list');
    if (container) {
        if (previousRuntime) {
            const prev = container.querySelector(`.server-item[data-runtime-id="${previousRuntime}"]`);
            if (prev) prev.classList.remove('active');
        }
        const next = container.querySelector(`.server-item[data-runtime-id="${runtimeId}"]`);
        if (next) next.classList.add('active');
    }

    const runtime = state.runtimeConfigs[runtimeId];
    if (!runtime) {
        console.error('Runtime not found:', runtimeId);
        return;
    }

    const appContainer = document.querySelector('.app-container');
    const consoleColumn = document.querySelector('.console-container');
    consoleColumn.style.display = 'flex';
    appContainer.style.gridTemplateColumns = '400px 1fr';
    consoleColumn.style.gridColumn = '2';

    const html = `
        <div class="console-header">
            <div class="console-title">
                ${getStatusIconHTML(runtime)}
                ${runtime.name || runtime.id}
            </div>
            <div class="console-controls">
                ${runtime.autoInstallable ? `
                    <button class="console-btn" data-action="installRuntime" data-runtime-id="${runtime.id}" title="Install this runtime">▶ Install</button>
                ` : ''}
                <button class="console-btn" data-action="checkRuntime" data-runtime-id="${runtime.id}" title="Check if runtime is available">🔍 Check</button>
            </div>
        </div>
        <div class="details-panel text-primary detail-content">
            ${buildRuntimeDetailsHTML(runtime)}
            <div id="runtime-install-output" style="display:${runtime.status === 'INSTALLING' ? 'block' : 'none'}"></div>
        </div>
    `;

    document.getElementById('console-area').innerHTML = html;
}

function buildRuntimeDetailsHTML(runtime) {
    const status = runtime.status || 'NOT_INSTALLED';

    const info = getRuntimeStatusInfo(status, runtime.autoInstallable);
    let statusLabel = (status === 'NOT_INSTALLED' || !status) ? 'Not checked' : info.label;
    let statusHTML = `<span class="text-${info.cssClass}">${statusLabel}</span>`;
    if ((status === 'FAILED' || status === 'ERROR') && runtime.error) {
        statusHTML += `<p class="text-error mt-xs">${runtime.error}</p>`;
    }

    let typeHTML;
    if (runtime.autoInstallable) {
        typeHTML = '<span class="text-success">Auto-installable</span> — can be downloaded and installed automatically';
    } else {
        typeHTML = `<span class="text-warning">Manual / Check-only</span> — must be installed manually`;
    }

    let dependentsHTML = '';
    const deps = runtime.dependentServers;
    if (deps) {
        const sections = [];
        if (deps.lsp && deps.lsp.length > 0) {
            sections.push(`
                <div class="mb-lg">
                    <strong class="text-label">Language Servers (LSP):</strong>
                    ${deps.lsp.map(id => renderServerLink('lsp', id)).join('')}
                </div>
            `);
        }
        if (deps.dap && deps.dap.length > 0) {
            sections.push(`
                <div class="mb-lg">
                    <strong class="text-label">Debug Adapters (DAP):</strong>
                    ${deps.dap.map(id => renderServerLink('dap', id)).join('')}
                </div>
            `);
        }
        if (deps.bsp && deps.bsp.length > 0) {
            sections.push(`
                <div class="mb-lg">
                    <strong class="text-label">Build Servers (BSP):</strong>
                    ${deps.bsp.map(id => renderServerLink('bsp', id)).join('')}
                </div>
            `);
        }
        if (sections.length > 0) {
            dependentsHTML = `
                <h3 class="text-success mt-2xl">Dependent Servers</h3>
                ${sections.join('')}
            `;
        }
    }

    return `
        <h3 class="text-success mt-0">Runtime Information</h3>

        <div class="detail-row">
            <span class="detail-label">Runtime ID:</span>
            <span class="detail-value"><code>${runtime.id}</code></span>
        </div>

        ${runtime.description ? `
        <div class="detail-row">
            <span class="detail-label">Description:</span>
            <span class="detail-value">${runtime.description}</span>
        </div>
        ` : ''}

        <div class="detail-row">
            <span class="detail-label">Status:</span>
            <span id="runtime-status-value" class="detail-value">${statusHTML}</span>
        </div>

        <div class="detail-row">
            <span class="detail-label">Type:</span>
            <span class="detail-value">${typeHTML}</span>
        </div>

        ${runtime.url ? `
        <div class="detail-row">
            <span class="detail-label">Website:</span>
            <span class="detail-value"><a href="${runtime.url}" target="_blank" class="link-accent">${runtime.url}</a></span>
        </div>
        ` : ''}

        ${runtime.extensionId ? `
        <div class="detail-row">
            <span class="detail-label">Extension:</span>
            <span class="detail-value">${renderExtensionLink(runtime.extensionId)}</span>
        </div>
        ` : ''}

        ${dependentsHTML}

        ${!runtime.autoInstallable ? `
        <div class="p-lg bg-panel rounded mt-2xl border-left-warning">
            <strong>Manual Installation Required:</strong>
            <p class="mt-xs mb-0">This runtime must be installed manually. ${runtime.url ? `Visit <a href="${runtime.url}" target="_blank" class="link-accent">${runtime.url}</a> for installation instructions.` : ''}</p>
        </div>
        ` : ''}
    `;
}

async function installRuntime(runtimeId) {
    showInstallOutput(runtimeId);
    try {
        const response = await fetch(`/api/admin/runtimes/${runtimeId}/install`, { method: 'POST' });
        const result = await response.json();

        if (!response.ok) {
            alert(result.error || 'Failed to install runtime');
        }
    } catch (error) {
        console.error('Failed to install runtime:', error);
    }
}

function showInstallOutput(runtimeId) {
    const outputDiv = document.getElementById('runtime-install-output');
    if (!outputDiv) return;
    const runtime = state.runtimeConfigs[runtimeId];
    const isChecking = runtime && (runtime.status === 'CHECKING' || runtime.status === 'NOT_INSTALLED');
    const label = isChecking ? 'Checking' : 'Installing';
    outputDiv.innerHTML = `
        <div class="install-output-header text-success mb-sm">${label} ${runtimeId}...</div>
        <div id="runtime-install-traces" class="font-mono bg-card p-sm rounded-sm font-sm overflow-auto" style="max-height: 300px;"></div>
    `;
    outputDiv.style.display = 'block';
}

async function checkRuntime(runtimeId) {
    showInstallOutput(runtimeId);
    try {
        const response = await fetch(`/api/admin/runtimes/${runtimeId}/check`, { method: 'POST' });
        const result = await response.json();

        if (!response.ok) {
            alert(result.error || 'Failed to check runtime');
        }
    } catch (error) {
        console.error('Failed to check runtime:', error);
    }
}

/**
 * Append an install trace message for a runtime.
 * Shows the install output panel if not already visible.
 */
export function appendRuntimeTrace(message) {
    if (selectedRuntime !== message.runtimeId) return;

    const outputDiv = document.getElementById('runtime-install-output');
    if (outputDiv && outputDiv.style.display === 'none') {
        showInstallOutput(message.runtimeId);
    }

    appendTraceLine(document.getElementById('runtime-install-traces'), message);
}

/**
 * Update a runtime's status from a WebSocket message.
 * Updates the cached config and refreshes the UI for that runtime.
 */
export function updateRuntimeStatus(runtimeId, status, error) {
    const runtime = state.runtimeConfigs[runtimeId];
    if (!runtime) return;

    runtime.status = status;
    runtime.error = error || null;

    const container = document.getElementById('runtimes-list');
    if (container) {
        const item = container.querySelector(`.server-item[data-runtime-id="${runtimeId}"]`);
        if (item) {
            const iconEl = item.querySelector('.server-source-icon');
            if (iconEl) iconEl.outerHTML = getStatusIconHTML(runtime);
            const badgeEl = item.querySelector('.badge');
            if (badgeEl) badgeEl.outerHTML = getStatusBadge(runtime);
        }
    }

    if (selectedRuntime === runtimeId) {
        if (status === 'INSTALLING') {
            const outputDiv = document.getElementById('runtime-install-output');
            if (outputDiv && outputDiv.style.display === 'none') {
                showInstallOutput(runtimeId);
            }
        } else {
            updateInstallOutputHeader(status, error);
            updateRuntimeDetailsStatus(runtime);
        }
    }
}

function updateRuntimeDetailsStatus(runtime) {
    const statusEl = document.getElementById('runtime-status-value');
    if (!statusEl) return;

    const status = runtime.status || 'NOT_INSTALLED';
    const info = getRuntimeStatusInfo(status, runtime.autoInstallable);
    const statusLabel = (status === 'NOT_INSTALLED' || !status) ? 'Not checked' : info.label;
    let html = `<span class="text-${info.cssClass}">${statusLabel}</span>`;
    if ((status === 'FAILED' || status === 'ERROR') && runtime.error) {
        html += `<p class="text-error mt-xs">${runtime.error}</p>`;
    }
    statusEl.innerHTML = html;

    const headerIcon = document.querySelector('.console-title .server-source-icon');
    if (headerIcon) headerIcon.outerHTML = getStatusIconHTML(runtime);
}

function updateInstallOutputHeader(status, error) {
    const header = document.querySelector('#runtime-install-output .install-output-header');
    if (!header) return;

    if (status === 'INSTALLED' || status === 'ALREADY_INSTALLED') {
        header.style.color = 'var(--color-success)';
        header.textContent = header.textContent.startsWith('Checking') ? 'Check completed — installed' : 'Installation completed';
    } else if (status === 'NOT_INSTALLED') {
        header.style.color = 'var(--color-warning)';
        header.textContent = 'Check completed — not installed';
    } else if (status === 'FAILED' || status === 'ERROR') {
        header.style.color = 'var(--color-error-text)';
        header.textContent = (header.textContent.startsWith('Checking') ? 'Check failed' : 'Installation failed') + (error ? ': ' + error : '');
    }
}

registerActions('click', {
    showRuntimeDetails: (el) => showRuntimeDetails(el.dataset.runtimeId),
    installRuntime: (el) => installRuntime(el.dataset.runtimeId),
    checkRuntime: (el) => checkRuntime(el.dataset.runtimeId),
    switchToLspServer: (el) => switchTabCallback?.('lsp-servers', null, {serverId: el.dataset.serverId}),
    switchToDapServer: (el) => switchTabCallback?.('dap-servers', null, {serverId: el.dataset.serverId}),
    switchToBspServer: (el) => switchTabCallback?.('bsp-servers', null, {serverId: el.dataset.serverId}),
    navigateToExtension: (el) => switchTabCallback?.('extensions', null, {extensionId: el.dataset.extensionId}),
});
