/**
 * Admin UI - Extensions Management
 *
 * Handles listing, adding, removing, and enabling/disabling extensions
 * and their individual LSP/DAP servers.
 */

import { confirmAction, showAlert, renderLoadingPlaceholder, renderServerLink, renderRuntimeLink, selectListItem } from './shared-ui.js';
import { state, loadLspConfigs, loadDapConfigs, ensureExtensionConfigs, ensureExtensionConfigDetail } from './shared-state.js';
import { registerActions } from './event-delegation.js';

let switchTabCallback = null;
export function setSwitchTabCallback(cb) { switchTabCallback = cb; }

let selectedExtension = null;
let extensionsData = [];

/**
 * Render the extensions list (without fetching).
 */
function renderExtensionsList() {
    const container = document.getElementById('extensions-list');
    if (!container) return;

    let html = '';

    if (extensionsData.length === 0) {
        html += '<div class="servers-placeholder">No extensions installed</div>';
        container.innerHTML = html;
        return;
    }

    html += extensionsData.map(ext => {
        const isActive = selectedExtension === ext.id ? 'active' : '';
        const disabledClass = !ext.enabled ? 'extension-disabled' : '';
        const sourceBadge = `<span class="extension-source-badge ${ext.source.toLowerCase()}">${ext.source}</span>`;
        const counts = [];
        const lspCount = ext.lspCount ?? ext.lspServers?.length ?? 0;
        const dapCount = ext.dapCount ?? ext.dapServers?.length ?? 0;
        const bspCount = ext.bspCount ?? ext.bspServers?.length ?? 0;
        const runtimeCount = Object.values(state.runtimeConfigs || {}).filter(rt => rt.extensionId === ext.id).length;
        if (lspCount > 0) counts.push(`${lspCount} lsp`);
        if (dapCount > 0) counts.push(`${dapCount} dap`);
        if (bspCount > 0) counts.push(`${bspCount} bsp`);
        if (runtimeCount > 0) counts.push(`${runtimeCount} runtime${runtimeCount !== 1 ? 's' : ''}`);

        return `
            <div class="extension-item ${isActive} ${disabledClass}" data-action="showExtensionDetails" data-extension-id="${ext.id}">
                <div class="d-flex align-center justify-between">
                    <span class="extension-name">${ext.id}${sourceBadge}</span>
                    <label class="toggle-switch" onclick="event.stopPropagation()">
                        <input type="checkbox" ${ext.enabled ? 'checked' : ''} data-action="toggleExtensionEnabled" data-extension-id="${ext.id}">
                        <span class="toggle-slider"></span>
                    </label>
                </div>
                <div class="extension-id">${counts.length > 0 ? counts.join(', ') : 'No servers'}</div>
            </div>
        `;
    }).join('');

    container.innerHTML = html;
}

/**
 * Load extensions from API and render.
 */
export async function loadAllExtensions(extensionIdToSelect) {
    try {
        const container = document.getElementById('extensions-list');
        if (!state.extensionConfigs) {
            if (container) container.innerHTML = renderLoadingPlaceholder();
        }

        await ensureExtensionConfigs();
        extensionsData = state.extensionConfigs || [];

        if (extensionIdToSelect) {
            selectedExtension = extensionIdToSelect;
        }

        renderExtensionsList();

        if (extensionsData.length > 0) {
            const toSelect = extensionIdToSelect
                || (selectedExtension && extensionsData.find(e => e.id === selectedExtension) ? selectedExtension : null)
                || extensionsData[0].id;
            showExtensionDetails(toSelect, true);
        }
    } catch (error) {
        console.error('Failed to load extensions:', error);
    }
}

/**
 * Show details for an extension in the console panel.
 */
export async function showExtensionDetails(extensionId, scroll) {
    const previousExtension = selectedExtension;
    selectedExtension = extensionId;

    selectListItem(document.getElementById('extensions-list'),
        '.extension-item[data-extension-id', previousExtension, extensionId, scroll);

    const contentArea = document.querySelector('.content-area');
    const consoleColumn = document.querySelector('.console-container');
    consoleColumn.style.display = 'flex';
    contentArea.style.gridTemplateColumns = '400px 1fr';
    consoleColumn.style.gridColumn = '2';

    const ext = extensionsData.find(e => e.id === extensionId);
    if (!ext) return;

    const sourceBadge = `<span class="extension-source-badge ${ext.source.toLowerCase()}">${ext.source}</span>`;

    document.getElementById('console-area').innerHTML = `
        <div class="console-header">
            <div class="console-title">
                <span class="server-source-icon">🧩</span>
                ${ext.id} ${sourceBadge}
            </div>
        </div>
        <div class="details-panel text-primary detail-content">
            <h3 class="text-label mt-0">Extension Information</h3>

            <div class="detail-row">
                <span class="detail-label">ID:</span>
                <span class="detail-value">${ext.id}</span>
            </div>

            <div class="detail-row">
                <span class="detail-label">Source:</span>
                <span class="detail-value">${sourceBadge}</span>
            </div>

            <div class="detail-row">
                <span class="detail-label">Status:</span>
                <span class="detail-value ${ext.enabled ? 'text-success' : 'text-error'}">${ext.enabled ? 'Enabled' : 'Disabled'}</span>
            </div>

            <div id="extension-detail-section">
                ${ext._detailLoaded ? buildExtensionDetailHTML(ext) : renderLoadingPlaceholder()}
            </div>
        </div>
    `;

    if (!ext._detailLoaded) {
        await ensureExtensionConfigDetail(extensionId);
        if (selectedExtension !== extensionId) return;
        const detailSection = document.getElementById('extension-detail-section');
        if (detailSection) {
            detailSection.innerHTML = buildExtensionDetailHTML(ext);
        }
    }
}

function buildExtensionDetailHTML(ext) {
    let serversHTML = '';
    if (ext.lspServers && ext.lspServers.length > 0) {
        serversHTML += '<h4 class="text-label mt-xl">LSP Servers</h4>';
        serversHTML += ext.lspServers.map(server => {
            const toggle = `<label class="toggle-switch"><input type="checkbox" ${server.enabled ? 'checked' : ''} data-action="toggleExtensionServerEnabled" data-server-type="lsp" data-server-id="${server.id}"><span class="toggle-slider"></span></label>`;
            return renderServerLink('lsp', server.id, { name: server.name, cssClass: !server.enabled ? 'server-disabled' : '', extra: toggle });
        }).join('');
    }

    if (ext.dapServers && ext.dapServers.length > 0) {
        serversHTML += '<h4 class="text-label mt-xl">DAP Servers</h4>';
        serversHTML += ext.dapServers.map(server => {
            const toggle = `<label class="toggle-switch"><input type="checkbox" ${server.enabled ? 'checked' : ''} data-action="toggleExtensionServerEnabled" data-server-type="dap" data-server-id="${server.id}"><span class="toggle-slider"></span></label>`;
            return renderServerLink('dap', server.id, { name: server.name, cssClass: !server.enabled ? 'server-disabled' : '', extra: toggle });
        }).join('');
    }

    if (ext.bspServers && ext.bspServers.length > 0) {
        serversHTML += '<h4 class="text-label mt-xl">BSP Servers</h4>';
        serversHTML += ext.bspServers.map(server => {
            const toggle = `<label class="toggle-switch"><input type="checkbox" ${server.enabled ? 'checked' : ''} data-action="toggleExtensionServerEnabled" data-server-type="bsp" data-server-id="${server.id}"><span class="toggle-slider"></span></label>`;
            return renderServerLink('bsp', server.id, { name: server.name, cssClass: !server.enabled ? 'server-disabled' : '', extra: toggle });
        }).join('');
    }

    const extRuntimes = Object.values(state.runtimeConfigs || {}).filter(rt => rt.extensionId === ext.id);
    let runtimesHTML = '';
    if (extRuntimes.length > 0) {
        runtimesHTML += '<h4 class="text-label mt-xl">Runtimes</h4>';
        runtimesHTML += extRuntimes.map(rt => {
            return `<div class="extension-server-item">
                <span>${renderRuntimeLink(rt.id, rt.name)}</span>
            </div>`;
        }).join('');
    }

    const hasContent = serversHTML || runtimesHTML;
    if (!hasContent) {
        serversHTML = '<p class="text-dimmed mt-lg">No servers or runtimes in this extension.</p>';
    }

    const removeButton = ext.source === 'USER' ? `
        <div class="mt-2xl border-top-subtle" style="padding-top: 1.5rem;">
            <button class="btn-danger" data-action="removeExtension" data-extension-id="${ext.id}">
                Remove Extension
            </button>
        </div>
    ` : '';

    return `${serversHTML}${runtimesHTML}${removeButton}`;
}

/**
 * Show the add extension form in the console panel.
 */
export function showAddExtensionForm() {
    selectedExtension = null;
    renderExtensionsList();

    const contentArea = document.querySelector('.content-area');
    const consoleColumn = document.querySelector('.console-container');
    consoleColumn.style.display = 'flex';
    contentArea.style.gridTemplateColumns = '400px 1fr';
    consoleColumn.style.gridColumn = '2';

    const consoleArea = document.getElementById('console-area');
    consoleArea.innerHTML = `
        <div class="console-header">
            <div class="console-title">
                <span class="server-source-icon">🧩</span>
                Add Extension
            </div>
        </div>
        <div class="details-panel text-primary p-2xl">
            <h3 class="text-label mt-0">Add a New Extension</h3>
            <p class="text-secondary mb-xl">
                Upload a ZIP or JAR file containing lsp/ and/or dap/ subdirectories with server configurations.
            </p>

            <div class="mb-xl">
                <label class="text-label d-block mb-xs font-medium">Extension ID</label>
                <input type="text" id="add-ext-id" placeholder="e.g. my-extension"
                       class="input-field w-100 font-base" style="max-width: 400px;">
            </div>

            <div id="drop-zone" class="drop-zone" data-action="triggerFileInput">
                <input type="file" id="add-ext-file" accept=".zip,.jar" class="d-none" data-action="handleFileSelect">
                <div class="drop-zone-icon">📦</div>
                <div class="drop-zone-text">Drop a ZIP or JAR file here</div>
                <div class="drop-zone-hint">or click to browse</div>
            </div>

            <div id="selected-file-info" class="d-none mb-xl">
                <div class="d-flex align-center gap-sm bg-card-alt rounded" style="padding: 0.5rem 0.75rem; border: 1px solid var(--border-subtle); max-width: 400px;">
                    <span class="text-success">📄</span>
                    <span id="selected-file-name" class="text-value flex-1 truncate"></span>
                    <span class="text-dimmed cursor-pointer font-xl" data-action="clearSelectedFile" title="Remove file">×</span>
                </div>
            </div>

            <button class="btn-primary" data-action="addExtension">
                Add Extension
            </button>

            <div id="add-ext-result" class="mt-lg"></div>
        </div>
    `;

    setupDropZone();
}

let selectedFile = null;

function setupDropZone() {
    const dropZone = document.getElementById('drop-zone');
    if (!dropZone) return;

    dropZone.addEventListener('dragover', (e) => {
        e.preventDefault();
        e.stopPropagation();
        dropZone.classList.add('drop-zone-active');
    });

    dropZone.addEventListener('dragleave', (e) => {
        e.preventDefault();
        e.stopPropagation();
        dropZone.classList.remove('drop-zone-active');
    });

    dropZone.addEventListener('drop', (e) => {
        e.preventDefault();
        e.stopPropagation();
        dropZone.classList.remove('drop-zone-active');

        const files = e.dataTransfer.files;
        if (files.length > 0) {
            const file = files[0];
            if (file.name.endsWith('.zip') || file.name.endsWith('.jar')) {
                setSelectedFile(file);
            } else {
                const resultDiv = document.getElementById('add-ext-result');
                if (resultDiv) resultDiv.innerHTML = '<div class="text-error">Only ZIP and JAR files are accepted.</div>';
            }
        }
    });
}

function handleFileSelect(input) {
    if (input.files && input.files.length > 0) {
        setSelectedFile(input.files[0]);
    }
}

function setSelectedFile(file) {
    selectedFile = file;
    const dropZone = document.getElementById('drop-zone');
    const fileInfo = document.getElementById('selected-file-info');
    const fileName = document.getElementById('selected-file-name');
    if (dropZone) dropZone.style.display = 'none';
    if (fileInfo) fileInfo.style.display = 'block';
    if (fileName) fileName.textContent = file.name;

    // Auto-fill extension ID from filename if empty
    const extIdInput = document.getElementById('add-ext-id');
    if (extIdInput && !extIdInput.value.trim()) {
        const name = file.name.replace(/\.(zip|jar)$/i, '');
        extIdInput.value = name;
    }
}

function clearSelectedFile() {
    selectedFile = null;
    const dropZone = document.getElementById('drop-zone');
    const fileInfo = document.getElementById('selected-file-info');
    const fileInput = document.getElementById('add-ext-file');
    if (dropZone) dropZone.style.display = 'flex';
    if (fileInfo) fileInfo.style.display = 'none';
    if (fileInput) fileInput.value = '';
}

/**
 * Add a new extension via file upload.
 */
async function addExtension() {
    const extensionId = document.getElementById('add-ext-id')?.value?.trim();
    const resultDiv = document.getElementById('add-ext-result');

    if (!extensionId) {
        if (resultDiv) resultDiv.innerHTML = '<div class="text-error">Extension ID is required.</div>';
        return;
    }

    if (!selectedFile) {
        if (resultDiv) resultDiv.innerHTML = '<div class="text-error">Please select a ZIP or JAR file.</div>';
        return;
    }

    if (resultDiv) resultDiv.innerHTML = '<div class="text-success">Uploading extension...</div>';

    try {
        const formData = new FormData();
        formData.append('extensionId', extensionId);
        formData.append('file', selectedFile);

        const response = await fetch('/api/admin/extensions/upload', {
            method: 'POST',
            body: formData
        });

        const result = await response.json();

        if (response.ok) {
            if (resultDiv) resultDiv.innerHTML = '<div class="text-success">Extension added successfully.</div>';
            selectedFile = null;
            state.extensionConfigs = null;
            state.languageConfigs = null;
            await loadLspConfigs();
            await loadDapConfigs();
            loadAllExtensions(extensionId);
        } else {
            if (resultDiv) resultDiv.innerHTML = `<div class="text-error">Failed: ${result.error || 'Unknown error'}</div>`;
        }
    } catch (error) {
        console.error('Failed to add extension:', error);
        if (resultDiv) resultDiv.innerHTML = `<div class="text-error">Error: ${error.message}</div>`;
    }
}

/**
 * Remove an extension (USER only).
 */
async function removeExtension(extensionId) {
    const confirmed = await confirmAction(
        'Remove Extension',
        `Remove extension "${extensionId}"?\n\nAll its servers will be unregistered.`,
        'Remove',
        true
    );
    if (!confirmed) return;

    try {
        const response = await fetch(`/api/admin/extensions/${encodeURIComponent(extensionId)}`, { method: 'DELETE' });
        const result = await response.json();

        if (response.ok) {
            selectedExtension = null;
            state.extensionConfigs = null;
            state.languageConfigs = null;
            await loadLspConfigs();
            await loadDapConfigs();
            loadAllExtensions();
        } else {
            showAlert('Error', result.error || 'Failed to remove extension');
        }
    } catch (error) {
        console.error('Failed to remove extension:', error);
        showAlert('Error', error.message);
    }
}

/**
 * Toggle enable/disable for an extension.
 */
async function toggleExtensionEnabled(extensionId, enabled) {
    const action = enabled ? 'enable' : 'disable';
    try {
        const response = await fetch(`/api/admin/extensions/${encodeURIComponent(extensionId)}/${action}`, { method: 'POST' });
        if (response.ok) {
            // Update local data
            const ext = extensionsData.find(e => e.id === extensionId);
            if (ext) ext.enabled = enabled;
            renderExtensionsList();
            if (selectedExtension === extensionId) showExtensionDetails(extensionId);
        }
    } catch (error) {
        console.error(`Failed to ${action} extension:`, error);
    }
}

/**
 * Toggle enable/disable for an individual server within an extension.
 */
async function toggleExtensionServerEnabled(type, serverId, enabled) {
    const action = enabled ? 'enable' : 'disable';
    try {
        const response = await fetch(`/api/admin/extensions/${type}/servers/${serverId}/${action}`, { method: 'POST' });
        if (response.ok) {
            // Update local data
            for (const ext of extensionsData) {
                const serverList = type === 'lsp' ? ext.lspServers : type === 'dap' ? ext.dapServers : ext.bspServers;
                const srv = serverList?.find(s => s.id === serverId);
                if (srv) { srv.enabled = enabled; break; }
            }
            if (selectedExtension) showExtensionDetails(selectedExtension);
        }
    } catch (error) {
        console.error(`Failed to ${action} ${type} server:`, error);
    }
}

registerActions('click', {
    showExtensionDetails: (el) => showExtensionDetails(el.dataset.extensionId),
    removeExtension: (el) => removeExtension(el.dataset.extensionId),
    switchToLspServer: (el) => switchTabCallback?.('lsp-servers', null, {serverId: el.dataset.serverId}),
    switchToDapServer: (el) => switchTabCallback?.('dap-servers', null, {serverId: el.dataset.serverId}),
    switchToBspServer: (el) => switchTabCallback?.('bsp-servers', null, {serverId: el.dataset.serverId}),
    clearSelectedFile: () => clearSelectedFile(),
    addExtension: () => addExtension(),
    handleFileSelect: (el) => handleFileSelect(el),
    triggerFileInput: () => document.getElementById('add-ext-file').click(),
    showAddExtensionForm: () => showAddExtensionForm(),
});

registerActions('change', {
    toggleExtensionEnabled: (el) => toggleExtensionEnabled(el.dataset.extensionId, el.checked),
    toggleExtensionServerEnabled: (el) => toggleExtensionServerEnabled(el.dataset.serverType, el.dataset.serverId, el.checked),
});
