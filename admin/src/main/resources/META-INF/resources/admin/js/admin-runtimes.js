/**
 * Admin UI - Runtimes Management
 *
 * Handles runtime listing with status, dependent servers, and install/check actions.
 */

import { state, updateSearchBoxVisibility, ensureRuntimeConfigs, ensureRuntimeConfigDetail } from './shared-state.js';
import { getRuntimeStatusInfo, renderLoadingPlaceholder, renderServerLink, renderExtensionLink, selectListItem,
    buildInstallerControlsHTML, getInstallStatusBadge, updateInstallBadgeInList,
    buildInstallOutputHTML, runServerInstaller, restoreInstallOutput } from './shared-ui.js';
import { registerActions } from './event-delegation.js';

let selectedRuntime = null;
let switchTabCallback = null;
export function setSwitchTabCallback(cb) { switchTabCallback = cb; }

function getStatusIconHTML(runtime) {
    const info = getRuntimeStatusInfo(runtime.status, runtime.autoInstallable);
    const animClass = info.animate ? ' status-checking' : '';
    return `<span class="server-source-icon${animClass}" title="${info.label}">${info.icon}</span>`;
}

function getSourceIcon(runtime) {
    if (!runtime.activeSource || runtime.activeSource === 'UNKNOWN') return '';
    if (runtime.activeSource === 'PATH') {
        return `<span class="source-icon" title="Found on system PATH">💻</span>`;
    }
    return `<span class="source-icon" title="Provided by MCP installer">📦</span>`;
}

function renderRuntimeItem(runtime) {
    const isActive = selectedRuntime === runtime.id ? 'active' : '';
    const dependentCount = runtime.dependentServerCount ?? countDependents(runtime.dependentServers);
    const sourceIcon = getSourceIcon(runtime);
    const installBadge = getInstallStatusBadge(runtime);
    return `
        <div class="server-item ${isActive}" data-action="showRuntimeDetails" data-runtime-id="${runtime.id}">
            <div class="server-name d-flex align-center justify-between">
                <span>
                    ${getStatusIconHTML(runtime)}
                    ${runtime.name}
                </span>
                <span class="d-flex gap-xs align-center">
                    <span class="source-icon-container">${sourceIcon}</span>
                    <span class="install-badge-container">${installBadge}</span>
                </span>
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

export async function loadAllRuntimes(runtimeIdToSelect) {
    const container = document.getElementById('runtimes-list');
    if (!container) {
        console.error('runtimes-list container not found');
        return;
    }

    if (!state.runtimeConfigs) {
        container.innerHTML = renderLoadingPlaceholder();
    }

    await ensureRuntimeConfigs();
    const runtimes = Object.values(state.runtimeConfigs || {}).sort((a, b) => (a.name || '').localeCompare(b.name || ''));

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
    showRuntimeDetails(runtimeToShow, true);
}

export async function showRuntimeDetails(runtimeId, scroll) {
    const previousRuntime = selectedRuntime;
    selectedRuntime = runtimeId;

    updateSearchBoxVisibility(false);

    selectListItem(document.getElementById('runtimes-list'),
        '.server-item[data-runtime-id', previousRuntime, runtimeId, scroll);

    const contentArea = document.querySelector('.content-area');
    const consoleColumn = document.querySelector('.console-container');
    consoleColumn.style.display = 'flex';
    contentArea.style.gridTemplateColumns = '400px 1fr';
    consoleColumn.style.gridColumn = '2';

    const runtime = state.runtimeConfigs?.[runtimeId];
    if (!runtime) {
        console.error('Runtime not found:', runtimeId);
        return;
    }

    renderRuntimeDetailsPage(runtime);

    if (!runtime._detailLoaded) {
        await ensureRuntimeConfigDetail(runtimeId);
        if (selectedRuntime !== runtimeId) return;
        const detailSection = document.getElementById('runtime-detail-section');
        if (detailSection) {
            detailSection.innerHTML = buildRuntimeDetailHTML(runtime);
        }
    }

    restoreInstallOutput(runtimeId, 'server-install-output');
}

function renderRuntimeDetailsPage(runtime) {
    const info = getRuntimeStatusInfo(runtime.status, runtime.autoInstallable);
    let statusLabel = (runtime.status === 'NOT_INSTALLED' || !runtime.status) ? 'Not checked' : info.label;
    let statusHTML = `<span class="text-${info.cssClass}">${statusLabel}</span>`;

    const html = `
        <div class="console-header">
            <div class="console-title">
                ${getStatusIconHTML(runtime)}
                ${runtime.name || runtime.id}
                <span class="console-install-badge" data-server-id="${runtime.id}">${getInstallStatusBadge(runtime)}</span>
            </div>
            ${runtime.autoInstallable ? buildInstallerControlsHTML(runtime.id, 'installRuntime') : ''}
        </div>
        <div class="details-panel text-primary detail-content">
            <h3 class="text-success mt-0">Runtime Information</h3>
            <div class="detail-row">
                <span class="detail-label">Runtime ID:</span>
                <span class="detail-value"><code>${runtime.id}</code></span>
            </div>
            <div class="detail-row">
                <span class="detail-label">Status:</span>
                <span id="runtime-status-value" class="detail-value">${statusHTML}</span>
            </div>
            <div id="runtime-detail-section">
                ${runtime._detailLoaded ? buildRuntimeDetailHTML(runtime) : renderLoadingPlaceholder()}
            </div>
        </div>
    `;

    document.getElementById('console-area').innerHTML = html;
}

function buildSourceHTML(runtime) {
    if (!runtime.activeSource || runtime.activeSource === 'UNKNOWN') {
        return '<span class="text-dimmed">Not resolved</span>';
    }
    if (runtime.activeSource === 'PATH') {
        return '<span>💻 System PATH</span>';
    }
    return '<span>📦 MCP Installer</span>';
}

function buildRuntimeDetailHTML(runtime) {
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
        typeHTML = `<span class="text-warning">Manual</span> — must be installed manually`;
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
        ${runtime.description ? `
        <div class="detail-row">
            <span class="detail-label">Description:</span>
            <span class="detail-value">${runtime.description}</span>
        </div>
        ` : ''}

        ${runtime.resolvedPath ? `
        <div class="detail-row">
            <span class="detail-label">Path:</span>
            <span id="runtime-resolved-path" class="detail-value"><code>${runtime.resolvedPath}</code></span>
        </div>
        ` : '<div class="detail-row"><span class="detail-label">Path:</span><span id="runtime-resolved-path" class="detail-value text-dimmed">Not resolved</span></div>'}

        <div class="detail-row">
            <span class="detail-label">Source:</span>
            <span id="runtime-active-source" class="detail-value">${buildSourceHTML(runtime)}</span>
        </div>

        <div class="detail-row">
            <span class="detail-label">Preference:</span>
            <span class="detail-value">
                <select class="select-field font-md" data-action="changeRuntimeSourcePreference" data-runtime-id="${runtime.id}" style="padding: 0.2rem 0.4rem;">
                    <option value="AUTO" ${(runtime.sourcePreference || 'AUTO') === 'AUTO' ? 'selected' : ''}>Auto (PATH first, then installer)</option>
                    <option value="PATH" ${runtime.sourcePreference === 'PATH' ? 'selected' : ''}>System PATH only</option>
                    <option value="INSTALLER" ${runtime.sourcePreference === 'INSTALLER' ? 'selected' : ''}>MCP installer only</option>
                </select>
            </span>
        </div>

        ${runtime.fallbackUsed ? `
        <div id="runtime-fallback-banner" class="p-lg bg-panel rounded mt-lg border-left-warning">
            <strong>Fallback Active:</strong>
            <p class="mt-xs mb-0">You selected "System PATH" but the runtime was not found on PATH. Using the MCP-installed version instead.</p>
        </div>
        ` : '<div id="runtime-fallback-banner"></div>'}

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
            <span class="detail-value">${renderExtensionLink(runtime.extensionId, runtime.extensionName)}</span>
        </div>
        ` : ''}

        ${dependentsHTML}

        ${!runtime.autoInstallable ? `
        <div class="p-lg bg-panel rounded mt-2xl border-left-warning">
            <strong>Manual Installation Required:</strong>
            <p class="mt-xs mb-0">This runtime must be installed manually. ${runtime.url ? `Visit <a href="${runtime.url}" target="_blank" class="link-accent">${runtime.url}</a> for installation instructions.` : ''}</p>
        </div>
        ` : ''}

        ${buildInstallOutputHTML()}
    `;
}

async function installRuntime(runtimeId) {
    const installUrl = `/api/admin/runtimes/${encodeURIComponent(runtimeId)}/install`;
    return runServerInstaller(runtimeId, true, 'server-install-output', installUrl);
}

/**
 * Update a runtime's status from a WebSocket message.
 * Updates the cached config and refreshes the UI for that runtime.
 */
export function updateRuntimeStatus(runtimeId, status, error, resolvedPath, activeSource, fallbackUsed, sourcePreference) {
    const runtime = state.runtimeConfigs?.[runtimeId];
    if (!runtime) return;

    runtime.status = status;
    runtime.error = error || null;
    if (resolvedPath !== undefined) runtime.resolvedPath = resolvedPath;
    if (activeSource !== undefined) runtime.activeSource = activeSource;
    if (fallbackUsed !== undefined) runtime.fallbackUsed = fallbackUsed;
    if (sourcePreference !== undefined) runtime.sourcePreference = sourcePreference;

    if (status === 'INSTALLING') {
        state.installStatus[runtimeId] = 'installing';
    } else if (status === 'INSTALLED' || status === 'ALREADY_INSTALLED') {
        state.installStatus[runtimeId] = 'completed';
        delete state.installProgress[runtimeId];
    } else if (status === 'FAILED' || status === 'ERROR') {
        state.installStatus[runtimeId] = 'failed';
        delete state.installProgress[runtimeId];
    }

    const container = document.getElementById('runtimes-list');
    if (container) {
        const item = container.querySelector(`.server-item[data-runtime-id="${runtimeId}"]`);
        if (item) {
            const iconEl = item.querySelector('.server-source-icon');
            if (iconEl) iconEl.outerHTML = getStatusIconHTML(runtime);
            const sourceEl = item.querySelector('.source-icon-container');
            if (sourceEl) sourceEl.innerHTML = getSourceIcon(runtime);
        }
    }
    updateInstallBadgeInList(runtimeId);

    if (selectedRuntime === runtimeId) {
        if (status !== 'INSTALLING') {
            updateRuntimeDetailsStatus(runtime);
            updateRuntimeDetailsPath(runtime);
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

function updateRuntimeDetailsPath(runtime) {
    const pathEl = document.getElementById('runtime-resolved-path');
    if (pathEl) {
        if (runtime.resolvedPath) {
            pathEl.innerHTML = `<code>${runtime.resolvedPath}</code>`;
            pathEl.classList.remove('text-dimmed');
        } else {
            pathEl.textContent = 'Not resolved';
            pathEl.classList.add('text-dimmed');
        }
    }

    const sourceEl = document.getElementById('runtime-active-source');
    if (sourceEl) {
        sourceEl.innerHTML = buildSourceHTML(runtime);
    }

    const fallbackBanner = document.getElementById('runtime-fallback-banner');
    if (fallbackBanner) {
        if (runtime.fallbackUsed) {
            fallbackBanner.innerHTML = `
                <div class="p-lg bg-panel rounded border-left-warning">
                    <strong>Fallback Active:</strong>
                    <p class="mt-xs mb-0">You selected "System PATH" but the runtime was not found on PATH. Using the MCP-installed version instead.</p>
                </div>
            `;
        } else {
            fallbackBanner.innerHTML = '';
        }
    }
}

registerActions('click', {
    showRuntimeDetails: (el) => showRuntimeDetails(el.dataset.runtimeId),
    installRuntime: (el) => installRuntime(el.dataset.serverId || el.dataset.runtimeId),

    switchToLspServer: (el) => switchTabCallback?.('lsp-servers', null, {serverId: el.dataset.serverId}),
    switchToDapServer: (el) => switchTabCallback?.('dap-servers', null, {serverId: el.dataset.serverId}),
    switchToBspServer: (el) => switchTabCallback?.('bsp-servers', null, {serverId: el.dataset.serverId}),
    navigateToExtension: (el) => switchTabCallback?.('extensions', null, {extensionId: el.dataset.extensionId}),
});

registerActions('change', {
    changeRuntimeSourcePreference: async (el) => {
        const runtimeId = el.dataset.runtimeId;
        const value = el.value;
        try {
            await fetch(`/api/admin/runtimes/${encodeURIComponent(runtimeId)}/source-preference`, {
                method: 'PUT',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ sourcePreference: value })
            });
        } catch (error) {
            console.error('Failed to change source preference:', error);
        }
    }
});
