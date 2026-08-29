/**
 * Admin UI - Extensions Management
 *
 * Handles listing, adding, removing, and enabling/disabling extensions
 * and their individual LSP/DAP servers.
 */

import { confirmAction, showAlert, renderLoadingPlaceholder, renderServerLink, renderRuntimeLink, selectListItem, renderDocumentSelector } from './shared-ui.js';
import { state, loadLspConfigs, loadDapConfigs, loadBspConfigs, ensureExtensionConfigs, ensureExtensionConfigDetail } from './shared-state.js';
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

    serverEntries = [];
    selectedEntryIndex = -1;
    isReadonlyList = false;
    selectedFile = null;
    currentAddTab = 'zip';

    const consoleArea = document.getElementById('console-area');
    consoleArea.innerHTML = `
        <div class="console-header">
            <div class="console-title">
                <span class="server-source-icon">🧩</span>
                Add Extension
            </div>
            <div class="d-flex align-center gap-lg" style="margin-left: auto;">
                <div id="add-ext-result" style="font-size: 0.8rem;"></div>
                <button class="btn-primary" data-action="finishImport">Finish</button>
            </div>
        </div>
        <div style="display: grid; grid-template-columns: 280px 1fr; flex: 1; min-height: 0; overflow: hidden;">
            <div style="border-right: 1px solid var(--border-subtle); display: flex; flex-direction: column; overflow: hidden;">
                <div style="padding: 0.75rem 1rem; border-bottom: 1px solid var(--border-subtle);">
                    <label class="text-label d-block mb-xs font-sm" style="font-weight: 500;">Import Method</label>
                    <div class="d-flex gap-sm" id="add-ext-mode-selector">
                        <label class="add-ext-mode-card active" data-action="switchAddExtTab" data-tab="zip"
                               style="flex: 1; display: flex; align-items: center; gap: 0.5rem; padding: 0.5rem 0.6rem; border: 2px solid var(--accent-primary); border-radius: 6px; cursor: pointer; background: var(--bg-panel); transition: border-color 0.15s, background 0.15s; font-size: 0.8rem;">
                            <input type="radio" name="add-ext-mode" value="zip" checked style="accent-color: var(--color-accent); margin: 0;">
                            <span class="font-medium">Upload</span>
                        </label>
                        <label class="add-ext-mode-card" data-action="switchAddExtTab" data-tab="json"
                               style="flex: 1; display: flex; align-items: center; gap: 0.5rem; padding: 0.5rem 0.6rem; border: 2px solid var(--border-subtle); border-radius: 6px; cursor: pointer; background: var(--bg-card); transition: border-color 0.15s, background 0.15s; font-size: 0.8rem;">
                            <input type="radio" name="add-ext-mode" value="json" style="accent-color: var(--color-accent); margin: 0;">
                            <span class="font-medium">JSON</span>
                        </label>
                    </div>
                </div>

                <div id="add-ext-zip-area" style="padding: 0.75rem 1rem; border-bottom: 1px solid var(--border-subtle);">
                    <div id="drop-zone" class="drop-zone" style="max-width: none; padding: 1.25rem; margin-bottom: 0;" data-action="triggerFileInput">
                        <input type="file" id="add-ext-file" accept=".zip,.jar" class="d-none" data-action="handleFileSelect">
                        <div class="drop-zone-icon">📦</div>
                        <div class="drop-zone-text font-sm">Drop a ZIP or JAR here</div>
                        <div class="drop-zone-hint">or click to browse</div>
                    </div>
                    <div id="selected-file-info" class="d-none" style="margin-top: 0.5rem;">
                        <div class="d-flex align-center gap-sm rounded" style="padding: 0.4rem 0.6rem; border: 1px solid var(--border-subtle); background: var(--bg-card);">
                            <span class="text-success font-sm">📄</span>
                            <span id="selected-file-name" class="flex-1 truncate font-sm"></span>
                            <span class="text-dimmed cursor-pointer" data-action="clearSelectedFile" title="Remove" style="font-size: 1.1rem; line-height: 1;">×</span>
                        </div>
                    </div>
                </div>

                <div id="add-ext-id-section" style="padding: 0.75rem 1rem; border-bottom: 1px solid var(--border-subtle); display: none;">
                    <label class="text-label d-block mb-xs font-sm" style="font-weight: 500;">Extension ID</label>
                    <input type="text" id="add-ext-id" placeholder="e.g. my-extension"
                           class="input-field w-100" style="font-size: 0.85rem;" data-action="onExtensionIdInput">
                </div>

                <div style="flex: 1; overflow-y: auto;">
                    <div style="padding: 0.5rem 1rem; display: flex; align-items: center; justify-content: space-between; border-bottom: 1px solid var(--border-subtle); background: var(--bg-panel);">
                        <span class="text-label font-sm" style="font-weight: 500;">Servers</span>
                        <span id="server-entry-count" class="text-dimmed font-sm"></span>
                    </div>
                    <div id="add-server-actions" class="add-server-actions" style="display: none;">
                        <button class="add-server-btn" data-action="addServerEntry" data-type="lsp">+ LSP</button>
                        <button class="add-server-btn" data-action="addServerEntry" data-type="dap">+ DAP</button>
                        <button class="add-server-btn" data-action="addServerEntry" data-type="bsp">+ BSP</button>
                    </div>
                    <div id="server-entry-list">
                        <div class="text-dimmed font-sm" style="padding: 1.5rem; text-align: center;">No servers added</div>
                    </div>
                </div>
            </div>

            <div style="overflow-y: auto; padding: 1.25rem;">
                <div id="server-editor-area" style="display: none;">
                    <div class="d-flex align-center gap-sm mb-sm">
                        <span id="editor-type-badge" class="server-type-badge lsp">LSP</span>
                        <span id="editor-server-name" class="font-medium font-sm">New Server</span>
                    </div>
                    <textarea id="add-server-json" class="font-mono" spellcheck="false"
                              style="width: 100%; min-height: 220px; padding: 0.6rem; border: 1px solid var(--border-subtle); border-radius: 4px; resize: vertical; font-size: 0.78rem; tab-size: 2; background: var(--bg-card); color: var(--text-code); line-height: 1.5;"
                              data-action="onJsonEditorInput"></textarea>
                    <p class="text-dimmed font-sm mt-xs mb-lg" style="font-size: 0.7rem;">
                        Required: <code>id</code>, <code>name</code>, <code>documentSelector</code>
                    </p>
                </div>

                <div id="add-server-preview">
                    <p class="text-dimmed" style="text-align: center; padding: 2rem 0;">
                        Select or add a server to see its preview
                    </p>
                </div>
            </div>
        </div>
    `;

    setupDropZone();
}

let selectedFile = null;
let currentAddTab = 'zip';
let previewTimer = null;
let serverEntries = [];
let selectedEntryIndex = -1;
let isReadonlyList = false;

const SERVER_TEMPLATES = {
    lsp: JSON.stringify({
        id: "my-server",
        name: "My Language Server",
        documentSelector: [{ language: "java" }],
        command: {
            windows: "${vscodeExtension:publisher.extension-id}/server/bin/server.exe --stdio",
            default: "${vscodeExtension:publisher.extension-id}/server/bin/server --stdio"
        }
    }, null, 2),
    dap: JSON.stringify({
        id: "my-debugger",
        name: "My Debug Adapter",
        documentSelector: [{ language: "java" }],
        launch: {
            windows: "${vscodeExtension:publisher.extension-id}/adapter/bin/adapter.exe",
            default: "adapter"
        }
    }, null, 2),
    bsp: JSON.stringify({
        id: "my-build-server",
        name: "My Build Server",
        documentSelector: [{ language: "java" }],
        command: {
            windows: "path/to/server.exe",
            default: "path/to/server"
        }
    }, null, 2)
};

function escapeHtml(str) {
    if (!str) return '';
    return String(str).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}

function upsertExtension(ext) {
    if (!state.extensionConfigs) {
        state.extensionConfigs = [ext];
    } else {
        const idx = state.extensionConfigs.findIndex(e => e.id === ext.id);
        if (idx >= 0) state.extensionConfigs[idx] = ext;
        else state.extensionConfigs.push(ext);
        state.extensionConfigs.sort((a, b) => (a.id || '').localeCompare(b.id || ''));
    }
    extensionsData = state.extensionConfigs;
}

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

async function finishImport() {
    if (currentAddTab === 'zip') {
        await addExtensionFromZip();
    } else {
        await addAllServersFromJson();
    }
}

async function addExtensionFromZip() {
    const resultDiv = document.getElementById('add-ext-result');

    if (!selectedFile) {
        if (resultDiv) resultDiv.innerHTML = '<span class="text-error">Please select a file.</span>';
        return;
    }

    const extensionId = selectedFile.name.replace(/\.(zip|jar)$/i, '');

    if (resultDiv) resultDiv.innerHTML = '<span class="text-success">Uploading...</span>';

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
            selectedFile = null;
            const addedExt = result.extension;
            const addedExtId = addedExt?.id || extensionId;
            addedExt._detailLoaded = true;
            upsertExtension(addedExt);
            state.languageConfigs = null;
            await loadLspConfigs();
            await loadDapConfigs();
            await loadBspConfigs();
            renderExtensionsList();

            serverEntries = [];
            if (addedExt.lspServers) {
                addedExt.lspServers.forEach(s => serverEntries.push({ type: 'lsp', id: s.id, name: s.name }));
            }
            if (addedExt.dapServers) {
                addedExt.dapServers.forEach(s => serverEntries.push({ type: 'dap', id: s.id, name: s.name }));
            }
            if (addedExt.bspServers) {
                addedExt.bspServers.forEach(s => serverEntries.push({ type: 'bsp', id: s.id, name: s.name }));
            }
            isReadonlyList = true;
            selectedEntryIndex = serverEntries.length > 0 ? 0 : -1;

            renderServerEntryList();
            updateRightPanel();

            if (resultDiv) resultDiv.innerHTML = `<span class="text-success">Imported ${serverEntries.length} server(s)</span>`;
        } else {
            if (resultDiv) resultDiv.innerHTML = `<span class="text-error">${escapeHtml(result.error || 'Failed')}</span>`;
        }
    } catch (error) {
        console.error('Failed to add extension:', error);
        if (resultDiv) resultDiv.innerHTML = `<span class="text-error">${escapeHtml(error.message)}</span>`;
    }
}

async function addAllServersFromJson() {
    saveCurrentEntry();

    const resultDiv = document.getElementById('add-ext-result');
    const extensionId = document.getElementById('add-ext-id')?.value?.trim() || undefined;

    if (serverEntries.length === 0) {
        if (resultDiv) resultDiv.innerHTML = '<span class="text-error">Add at least one server.</span>';
        return;
    }

    for (let i = 0; i < serverEntries.length; i++) {
        try {
            JSON.parse(serverEntries[i].json);
        } catch (e) {
            if (resultDiv) resultDiv.innerHTML = `<span class="text-error">Invalid JSON in server ${i + 1}</span>`;
            selectServerEntry(i);
            return;
        }
    }

    if (resultDiv) resultDiv.innerHTML = '<span class="text-success">Adding...</span>';

    try {
        let lastResult = null;
        for (const entry of serverEntries) {
            const body = { serverType: entry.type, serverJson: entry.json };
            if (extensionId) body.extensionId = extensionId;

            const response = await fetch('/api/admin/extensions/server/json', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(body)
            });
            const result = await response.json();
            if (!response.ok) {
                if (resultDiv) resultDiv.innerHTML = `<span class="text-error">${escapeHtml(result.error || 'Failed')}</span>`;
                return;
            }
            lastResult = result;
        }

        if (lastResult) {
            const addedExt = lastResult.extension;
            const addedExtId = addedExt?.id || extensionId;
            addedExt._detailLoaded = true;
            upsertExtension(addedExt);
            state.languageConfigs = null;
            await loadLspConfigs();
            await loadDapConfigs();
            await loadBspConfigs();
            renderExtensionsList();
            showExtensionDetails(addedExtId, true);
            showAlert('Extension Added', `${serverEntries.length} server(s) added to extension "${addedExtId}".`);
        }
    } catch (error) {
        console.error('Failed to add servers:', error);
        if (resultDiv) resultDiv.innerHTML = `<span class="text-error">${escapeHtml(error.message)}</span>`;
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

// ========== Server entry management ==========

function switchAddExtTab(tab) {
    currentAddTab = tab;

    document.querySelectorAll('.add-ext-mode-card').forEach(card => {
        const isActive = card.dataset.tab === tab;
        card.classList.toggle('active', isActive);
        card.style.borderColor = isActive ? 'var(--accent-primary)' : 'var(--border-subtle)';
        card.style.background = isActive ? 'var(--bg-panel)' : 'var(--bg-card)';
        const radio = card.querySelector('input[type="radio"]');
        if (radio) radio.checked = isActive;
    });

    const zipArea = document.getElementById('add-ext-zip-area');
    if (zipArea) zipArea.style.display = tab === 'zip' ? '' : 'none';

    const extIdSection = document.getElementById('add-ext-id-section');
    if (extIdSection) extIdSection.style.display = tab === 'json' ? '' : 'none';

    const addActions = document.getElementById('add-server-actions');
    if (addActions) addActions.style.display = tab === 'json' ? 'flex' : 'none';

    updateRightPanel();
}

function addServerEntry(type) {
    saveCurrentEntry();
    serverEntries.push({ type, json: SERVER_TEMPLATES[type] });
    selectedEntryIndex = serverEntries.length - 1;
    isReadonlyList = false;
    renderServerEntryList();
    updateRightPanel();
}

function removeServerEntry(index) {
    serverEntries.splice(index, 1);
    if (serverEntries.length === 0) {
        selectedEntryIndex = -1;
    } else if (selectedEntryIndex >= serverEntries.length) {
        selectedEntryIndex = serverEntries.length - 1;
    } else if (selectedEntryIndex > index) {
        selectedEntryIndex--;
    } else if (selectedEntryIndex === index) {
        selectedEntryIndex = Math.min(index, serverEntries.length - 1);
    }
    renderServerEntryList();
    updateRightPanel();
}

function selectServerEntry(index) {
    if (index === selectedEntryIndex) return;
    saveCurrentEntry();
    selectedEntryIndex = index;
    renderServerEntryList();
    updateRightPanel();
}

function saveCurrentEntry() {
    if (selectedEntryIndex < 0 || selectedEntryIndex >= serverEntries.length || isReadonlyList) return;
    const textarea = document.getElementById('add-server-json');
    if (textarea) {
        serverEntries[selectedEntryIndex].json = textarea.value;
    }
}

function renderServerEntryList() {
    const listEl = document.getElementById('server-entry-list');
    const countEl = document.getElementById('server-entry-count');
    if (!listEl) return;

    if (serverEntries.length === 0) {
        listEl.innerHTML = '<div class="text-dimmed font-sm" style="padding: 1.5rem; text-align: center;">No servers added</div>';
        if (countEl) countEl.textContent = '';
        return;
    }

    if (countEl) countEl.textContent = serverEntries.length;

    listEl.innerHTML = serverEntries.map((entry, i) => {
        const isActive = i === selectedEntryIndex ? 'active' : '';
        const label = getEntryLabel(entry);
        const removeBtn = isReadonlyList ? '' :
            `<span class="entry-remove" data-action="removeServerEntry" data-index="${i}" data-stop-propagation title="Remove">×</span>`;
        return `
            <div class="server-entry-item ${isActive}" data-action="selectServerEntry" data-index="${i}">
                <span class="server-type-badge ${entry.type}">${entry.type.toUpperCase()}</span>
                <span class="entry-name">${escapeHtml(label)}</span>
                ${removeBtn}
            </div>
        `;
    }).join('');
}

function getEntryLabel(entry) {
    if (entry.name) return entry.name;
    if (entry.id) return entry.id;
    try {
        const json = JSON.parse(entry.json);
        return json.name || json.id || 'New Server';
    } catch {
        return 'New Server';
    }
}

function updateRightPanel() {
    const editorArea = document.getElementById('server-editor-area');
    const previewDiv = document.getElementById('add-server-preview');

    if (selectedEntryIndex < 0 || selectedEntryIndex >= serverEntries.length) {
        if (editorArea) editorArea.style.display = 'none';
        if (previewDiv) previewDiv.innerHTML = '<p class="text-dimmed" style="text-align: center; padding: 2rem 0;">Select or add a server to see its preview</p>';
        return;
    }

    const entry = serverEntries[selectedEntryIndex];

    if (currentAddTab === 'json' && !isReadonlyList) {
        if (editorArea) {
            editorArea.style.display = '';
            const textarea = document.getElementById('add-server-json');
            const badge = document.getElementById('editor-type-badge');
            const nameEl = document.getElementById('editor-server-name');
            if (textarea) textarea.value = entry.json || '';
            if (badge) { badge.textContent = entry.type.toUpperCase(); badge.className = `server-type-badge ${entry.type}`; }
            if (nameEl) nameEl.textContent = getEntryLabel(entry);
        }
        updateJsonPreview();
    } else {
        if (editorArea) editorArea.style.display = 'none';
        if (previewDiv) previewDiv.innerHTML = buildReadonlyPreviewHTML(entry);
    }
}

function buildReadonlyPreviewHTML(entry) {
    const name = entry.name || entry.id || 'Unknown';
    const extId = getExtensionIdForPreview();
    return `
        ${extId ? `
        <div class="detail-row" style="margin-bottom: 0.75rem; padding-bottom: 0.75rem; border-bottom: 1px solid var(--border-subtle);">
            <span class="detail-label">Extension:</span>
            <span class="detail-value"><code>${escapeHtml(extId)}</code></span>
        </div>` : ''}
        <h3 class="text-label mt-0">Server Information</h3>
        <div class="detail-row">
            <span class="detail-label">Server ID:</span>
            <span class="detail-value"><code>${escapeHtml(entry.id || '')}</code></span>
        </div>
        <div class="detail-row">
            <span class="detail-label">Name:</span>
            <span class="detail-value">${escapeHtml(name)}</span>
        </div>
        <div class="detail-row">
            <span class="detail-label">Type:</span>
            <span class="detail-value"><span class="server-type-badge ${entry.type}">${entry.type.toUpperCase()}</span></span>
        </div>
    `;
}

// ========== JSON editor helpers ==========

function getExtensionIdForPreview() {
    if (currentAddTab === 'zip') {
        return selectedFile ? selectedFile.name.replace(/\.(zip|jar)$/i, '') : '';
    }
    return document.getElementById('add-ext-id')?.value?.trim() || '';
}

function onExtensionIdInput() {
    const previewEl = document.getElementById('preview-ext-id');
    if (previewEl) {
        const extId = getExtensionIdForPreview();
        previewEl.innerHTML = extId ? escapeHtml(extId) : '<span class="text-dimmed">auto-generated from server id</span>';
    }
}

function schedulePreviewUpdate() {
    if (previewTimer) clearTimeout(previewTimer);
    previewTimer = setTimeout(updateJsonPreview, 250);
}

function onJsonEditorInput() {
    if (selectedEntryIndex >= 0 && selectedEntryIndex < serverEntries.length) {
        const textarea = document.getElementById('add-server-json');
        if (textarea) serverEntries[selectedEntryIndex].json = textarea.value;
    }

    const entry = serverEntries[selectedEntryIndex];
    if (entry) {
        const label = getEntryLabel(entry);
        const nameEl = document.getElementById('editor-server-name');
        if (nameEl) nameEl.textContent = label;
        const activeItem = document.querySelector('.server-entry-item.active .entry-name');
        if (activeItem) activeItem.textContent = label;
    }

    const textarea = document.getElementById('add-server-json');
    const extIdInput = document.getElementById('add-ext-id');
    if (textarea && extIdInput && !extIdInput.value.trim()) {
        try {
            const json = JSON.parse(textarea.value);
            if (json.id && typeof json.id === 'string') {
                extIdInput.placeholder = json.id;
            }
        } catch (_) { /* ignore */ }
    }

    schedulePreviewUpdate();
}

function updateJsonPreview() {
    const previewDiv = document.getElementById('add-server-preview');
    if (!previewDiv) return;

    if (selectedEntryIndex < 0 || selectedEntryIndex >= serverEntries.length) {
        previewDiv.innerHTML = '<p class="text-dimmed" style="text-align: center; padding: 2rem 0;">Select or add a server to see its preview</p>';
        return;
    }

    const textarea = document.getElementById('add-server-json');
    const content = textarea?.value?.trim();
    if (!content) {
        previewDiv.innerHTML = '<p class="text-dimmed">Edit the JSON to see a preview.</p>';
        return;
    }

    try {
        const json = JSON.parse(content);
        const entry = serverEntries[selectedEntryIndex];
        previewDiv.innerHTML = buildJsonPreviewHTML(json, entry.type);
        resolvePreviewCommands(json);
    } catch (e) {
        previewDiv.innerHTML = `<div class="text-error font-sm">Invalid JSON: ${escapeHtml(e.message)}</div>`;
    }
}

function buildJsonPreviewHTML(json, serverType) {
    const id = json.id || '';
    const name = json.name || '';
    const description = json.description || '';
    const url = json.url || '';
    const docSelector = json.documentSelector || [];
    const command = json.command || json.launch || null;

    const issues = [];
    if (!id) issues.push('<code>id</code> is required');
    if (!name) issues.push('<code>name</code> is required');
    if (!docSelector.length) issues.push('<code>documentSelector</code> is required');

    let validationHTML = '';
    if (issues.length > 0) {
        validationHTML = `<div class="text-error mb-lg font-sm">${issues.map(i => `<div>&#9888; ${i}</div>`).join('')}</div>`;
    } else {
        validationHTML = '<div class="text-success mb-lg font-sm">&#10003; All required fields present</div>';
    }

    const selectorHTML = renderDocumentSelector(docSelector);

    let commandHTML = '<p class="text-secondary">None configured</p>';
    if (command) {
        if (typeof command === 'string') {
            commandHTML = `<code>${escapeHtml(command)}</code>`;
        } else {
            commandHTML = Object.entries(command).map(([os, cmd]) =>
                `<div class="mb-xs"><strong>${os}:</strong> <code class="font-sm">${escapeHtml(String(cmd))}</code></div>`
            ).join('');
        }
    }

    const commandLabel = serverType === 'dap' ? 'Launch:' : 'Command:';

    const extId = getExtensionIdForPreview();

    return `
        ${validationHTML}
        <div class="detail-row" style="margin-bottom: 0.75rem; padding-bottom: 0.75rem; border-bottom: 1px solid var(--border-subtle);">
            <span class="detail-label">Extension:</span>
            <span class="detail-value"><code id="preview-ext-id">${escapeHtml(extId) || '<span class="text-dimmed">auto-generated from server id</span>'}</code></span>
        </div>
        <h3 class="text-label mt-0">Server Information</h3>
        <div class="detail-row">
            <span class="detail-label">Server ID:</span>
            <span class="detail-value"><code>${escapeHtml(id) || '<span class="text-dimmed">—</span>'}</code></span>
        </div>
        <div class="detail-row">
            <span class="detail-label">Name:</span>
            <span class="detail-value">${escapeHtml(name) || '<span class="text-dimmed">—</span>'}</span>
        </div>
        ${description ? `
        <div class="detail-row">
            <span class="detail-label">Description:</span>
            <span class="detail-value">${escapeHtml(description)}</span>
        </div>` : ''}
        ${url ? `
        <div class="detail-row">
            <span class="detail-label">URL:</span>
            <span class="detail-value"><a href="${escapeHtml(url)}" target="_blank" class="link-accent">${escapeHtml(url)}</a></span>
        </div>` : ''}
        <div class="mb-lg">
            <strong class="text-label">Supported Languages/Files:</strong>
            ${selectorHTML}
        </div>
        <div class="detail-row">
            <span class="detail-label">${commandLabel}</span>
            <span class="detail-value">${commandHTML}<div id="preview-resolved-command"></div></span>
        </div>
    `;
}

async function resolvePreviewCommands(json) {
    const command = json.command || json.launch;
    if (!command) return;

    const templates = {};
    if (typeof command === 'string') {
        if (command.includes('${')) templates['default'] = command;
    } else {
        for (const [os, cmd] of Object.entries(command)) {
            if (typeof cmd === 'string' && cmd.includes('${')) {
                templates[os] = cmd;
            }
        }
    }

    if (Object.keys(templates).length === 0) return;

    try {
        const response = await fetch('/api/admin/extensions/resolve-variables', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(templates)
        });
        if (!response.ok) return;
        const resolved = await response.json();

        const el = document.getElementById('preview-resolved-command');
        if (!el) return;

        let html = '';
        for (const [os, cmd] of Object.entries(resolved)) {
            if (cmd !== templates[os]) {
                html += `<div class="mb-xs"><strong>${os}:</strong> <code class="font-sm">${escapeHtml(cmd)}</code></div>`;
            }
        }
        if (html) {
            el.innerHTML = `<div class="mt-sm p-sm rounded-sm" style="background: var(--bg-panel); border-left: 3px solid var(--color-success);"><strong class="text-label font-sm">Resolved path:</strong>${html}</div>`;
        }
    } catch (_) { /* ignore network errors */ }
}

registerActions('click', {
    showExtensionDetails: (el) => showExtensionDetails(el.dataset.extensionId),
    removeExtension: (el) => removeExtension(el.dataset.extensionId),
    switchToLspServer: (el) => switchTabCallback?.('lsp-servers', null, {serverId: el.dataset.serverId}),
    switchToDapServer: (el) => switchTabCallback?.('dap-servers', null, {serverId: el.dataset.serverId}),
    switchToBspServer: (el) => switchTabCallback?.('bsp-servers', null, {serverId: el.dataset.serverId}),
    clearSelectedFile: () => clearSelectedFile(),
    handleFileSelect: (el) => handleFileSelect(el),
    triggerFileInput: () => document.getElementById('add-ext-file').click(),
    showAddExtensionForm: () => showAddExtensionForm(),
    switchAddExtTab: (el) => switchAddExtTab(el.dataset.tab),
    finishImport: () => finishImport(),
    addServerEntry: (el) => addServerEntry(el.dataset.type),
    removeServerEntry: (el) => removeServerEntry(parseInt(el.dataset.index)),
    selectServerEntry: (el) => selectServerEntry(parseInt(el.dataset.index)),
});

registerActions('change', {
    handleFileSelect: (el) => handleFileSelect(el),
    toggleExtensionEnabled: (el) => toggleExtensionEnabled(el.dataset.extensionId, el.checked),
    toggleExtensionServerEnabled: (el) => toggleExtensionServerEnabled(el.dataset.serverType, el.dataset.serverId, el.checked),
});

registerActions('input', {
    onJsonEditorInput: () => onJsonEditorInput(),
    onExtensionIdInput: () => onExtensionIdInput(),
});
