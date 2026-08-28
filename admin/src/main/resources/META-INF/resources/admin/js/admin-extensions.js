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

    currentAddTab = null;

    const consoleArea = document.getElementById('console-area');
    consoleArea.innerHTML = `
        <div class="console-header">
            <div class="console-title">
                <span class="server-source-icon">🧩</span>
                Add Extension
            </div>
        </div>
        <div class="details-panel text-primary overflow-auto p-2xl">
            <h3 class="text-label mt-0">How do you want to add?</h3>
            <div class="d-flex gap-lg mb-xl" id="add-ext-mode-selector">
                <label class="add-ext-mode-card" data-action="switchAddExtTab" data-tab="zip"
                       style="flex: 1; display: flex; align-items: flex-start; gap: 0.75rem; padding: 1rem 1.25rem; border: 2px solid var(--border-subtle); border-radius: 8px; cursor: pointer; background: var(--bg-card); transition: border-color 0.15s, background 0.15s;">
                    <input type="radio" name="add-ext-mode" value="zip" style="margin-top: 2px; accent-color: var(--color-accent);">
                    <div>
                        <div class="font-medium">Upload ZIP / JAR</div>
                        <div class="text-secondary font-sm">Upload a file containing lsp/, dap/ and/or bsp/ server configurations.</div>
                    </div>
                </label>
                <label class="add-ext-mode-card" data-action="switchAddExtTab" data-tab="json"
                       style="flex: 1; display: flex; align-items: flex-start; gap: 0.75rem; padding: 1rem 1.25rem; border: 2px solid var(--border-subtle); border-radius: 8px; cursor: pointer; background: var(--bg-card); transition: border-color 0.15s, background 0.15s;">
                    <input type="radio" name="add-ext-mode" value="json" style="margin-top: 2px; accent-color: var(--color-accent);">
                    <div>
                        <div class="font-medium">Paste server.json</div>
                        <div class="text-secondary font-sm">Add a single LSP, DAP or BSP server by pasting its JSON configuration.</div>
                    </div>
                </label>
            </div>

            <div id="add-ext-zip-content" style="display: none;">
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

                <button class="btn-primary" data-action="addExtension">Add Extension</button>
                <div id="add-ext-result" class="mt-lg"></div>
            </div>

            <div id="add-ext-json-content" style="display: none;">
                <div style="display: grid; grid-template-columns: 1fr 1fr; gap: 1.5rem;">
                    <div>
                        <div class="mb-lg">
                            <label class="text-label d-block mb-xs font-medium">Server Type</label>
                            <div class="d-flex gap-xs">
                                <button class="tab-button ${currentServerType === 'lsp' ? 'active' : ''}" data-action="changeServerType" data-type="lsp">LSP</button>
                                <button class="tab-button ${currentServerType === 'dap' ? 'active' : ''}" data-action="changeServerType" data-type="dap">DAP</button>
                                <button class="tab-button ${currentServerType === 'bsp' ? 'active' : ''}" data-action="changeServerType" data-type="bsp">BSP</button>
                            </div>
                        </div>

                        <div class="mb-lg">
                            <label class="text-label d-block mb-xs font-medium">Extension ID <span class="text-dimmed font-sm">(optional, defaults to server ID)</span></label>
                            <input type="text" id="add-server-ext-id" placeholder="e.g. my-extension"
                                   class="input-field w-100 font-base" style="max-width: 400px;">
                        </div>

                        <div class="mb-lg">
                            <label class="text-label d-block mb-xs font-medium">server.json</label>
                            <p class="text-secondary font-sm mb-xs">
                                Required: <code>id</code>, <code>name</code>, <code>documentSelector</code>.
                                Use <code>\${vscodeExtension:publisher.id}</code> in commands to reference VS Code extension paths.
                            </p>
                            <textarea id="add-server-json"
                                      class="font-mono"
                                      spellcheck="false"
                                      style="width: 100%; min-height: 320px; padding: 0.75rem; border: 1px solid var(--border-subtle); border-radius: 4px; resize: vertical; font-size: 0.8rem; tab-size: 2; background: var(--bg-card); color: var(--text-code); line-height: 1.5;"
                                      data-action="onJsonEditorInput">${SERVER_TEMPLATES[currentServerType]}</textarea>
                        </div>

                        <button class="btn-primary" data-action="addServerFromJson">Add Server</button>
                        <div id="add-server-result" class="mt-lg"></div>
                    </div>

                    <div style="border-left: 1px solid var(--border-subtle); padding-left: 1.5rem;">
                        <h3 class="text-label mt-0">Preview</h3>
                        <div id="add-server-preview">
                            <p class="text-dimmed">Edit the JSON to see a preview.</p>
                        </div>
                    </div>
                </div>
            </div>
        </div>
    `;

    setupDropZone();
    updateJsonPreview();
}

let selectedFile = null;
let currentAddTab = 'zip';
let currentServerType = 'lsp';
let previewTimer = null;

const SERVER_TEMPLATES = {
    lsp: JSON.stringify({
        id: "my-server",
        name: "My Language Server",
        description: "",
        documentSelector: [{ language: "java" }],
        command: {
            windows: "${vscodeExtension:publisher.extension-id}/server/bin/server.exe --stdio",
            default: "server --stdio"
        }
    }, null, 2),
    dap: JSON.stringify({
        id: "my-debugger",
        name: "My Debug Adapter",
        description: "",
        documentSelector: [{ language: "java" }],
        launch: {
            windows: "${vscodeExtension:publisher.extension-id}/adapter/bin/adapter.exe",
            default: "adapter"
        }
    }, null, 2),
    bsp: JSON.stringify({
        id: "my-build-server",
        name: "My Build Server",
        description: "",
        documentSelector: [{ language: "java" }],
        command: {
            windows: "path/to/server.exe",
            default: "path/to/server"
        }
    }, null, 2)
};

function isTemplateContent(content) {
    const trimmed = content.trim();
    return Object.values(SERVER_TEMPLATES).some(t => t.trim() === trimmed);
}

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
            showExtensionDetails(addedExtId, true);
            showAlert('Extension Added', `Extension "${addedExtId}" has been added successfully.`);
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

// ========== JSON Editor: tab switching, preview, submit ==========

function switchAddExtTab(tab) {
    currentAddTab = tab;

    document.querySelectorAll('.add-ext-mode-card').forEach(card => {
        const isActive = card.dataset.tab === tab;
        card.classList.toggle('active', isActive);
        card.style.borderColor = isActive ? 'var(--color-accent)' : 'var(--border-subtle)';
        card.style.background = isActive ? 'var(--bg-panel)' : 'var(--bg-card)';
        const radio = card.querySelector('input[type="radio"]');
        if (radio) radio.checked = isActive;
    });

    const zipContent = document.getElementById('add-ext-zip-content');
    const jsonContent = document.getElementById('add-ext-json-content');
    if (zipContent) zipContent.style.display = tab === 'zip' ? '' : 'none';
    if (jsonContent) jsonContent.style.display = tab === 'json' ? '' : 'none';

    if (tab === 'json') {
        updateJsonPreview();
    }
}

function changeServerType(type) {
    const prev = currentServerType;
    currentServerType = type;

    document.querySelectorAll('[data-action="changeServerType"]').forEach(btn => {
        btn.classList.toggle('active', btn.dataset.type === type);
    });

    const textarea = document.getElementById('add-server-json');
    if (textarea) {
        const content = textarea.value;
        if (!content.trim() || isTemplateContent(content)) {
            textarea.value = SERVER_TEMPLATES[type];
            updateJsonPreview();
        }
    }
}

function schedulePreviewUpdate() {
    if (previewTimer) clearTimeout(previewTimer);
    previewTimer = setTimeout(updateJsonPreview, 250);
}

function onJsonEditorInput() {
    schedulePreviewUpdate();

    const textarea = document.getElementById('add-server-json');
    const extIdInput = document.getElementById('add-server-ext-id');
    if (textarea && extIdInput && !extIdInput.value.trim()) {
        try {
            const json = JSON.parse(textarea.value);
            if (json.id && typeof json.id === 'string') {
                extIdInput.placeholder = json.id;
            }
        } catch (_) { /* ignore parse errors during typing */ }
    }
}

function updateJsonPreview() {
    const textarea = document.getElementById('add-server-json');
    const previewDiv = document.getElementById('add-server-preview');
    if (!textarea || !previewDiv) return;

    const content = textarea.value.trim();
    if (!content) {
        previewDiv.innerHTML = '<p class="text-dimmed">Edit the JSON to see a preview.</p>';
        return;
    }

    try {
        const json = JSON.parse(content);
        previewDiv.innerHTML = buildJsonPreviewHTML(json);
        resolvePreviewCommands(json);
    } catch (e) {
        previewDiv.innerHTML = `<div class="text-error font-sm">Invalid JSON: ${escapeHtml(e.message)}</div>`;
    }
}

function buildJsonPreviewHTML(json) {
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

    const commandLabel = currentServerType === 'dap' ? 'Launch:' : 'Command:';

    return `
        ${validationHTML}
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

async function addServerFromJson() {
    const textarea = document.getElementById('add-server-json');
    const extensionIdInput = document.getElementById('add-server-ext-id');
    const resultDiv = document.getElementById('add-server-result');

    const jsonContent = textarea?.value?.trim();
    const extensionId = extensionIdInput?.value?.trim() || undefined;

    if (!jsonContent) {
        if (resultDiv) resultDiv.innerHTML = '<div class="text-error">server.json content is required.</div>';
        return;
    }

    try {
        JSON.parse(jsonContent);
    } catch (e) {
        if (resultDiv) resultDiv.innerHTML = `<div class="text-error">Invalid JSON: ${escapeHtml(e.message)}</div>`;
        return;
    }

    if (resultDiv) resultDiv.innerHTML = '<div class="text-success">Adding server...</div>';

    try {
        const body = { serverType: currentServerType, serverJson: jsonContent };
        if (extensionId) body.extensionId = extensionId;

        const response = await fetch('/api/admin/extensions/server/json', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(body)
        });

        const result = await response.json();

        if (response.ok) {
            const addedExt = result.extension;
            const addedExtId = addedExt?.id || extensionId;
            addedExt._detailLoaded = true;
            upsertExtension(addedExt);
            state.languageConfigs = null;
            await loadLspConfigs();
            await loadDapConfigs();
            await loadBspConfigs();
            renderExtensionsList();
            showExtensionDetails(addedExtId, true);
            showAlert('Server Added', `Server successfully added to extension "${addedExtId}".`);
        } else {
            if (resultDiv) resultDiv.innerHTML = `<div class="text-error">Failed: ${escapeHtml(result.error || 'Unknown error')}</div>`;
        }
    } catch (error) {
        console.error('Failed to add server:', error);
        if (resultDiv) resultDiv.innerHTML = `<div class="text-error">Error: ${escapeHtml(error.message)}</div>`;
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
    addServerFromJson: () => addServerFromJson(),
    handleFileSelect: (el) => handleFileSelect(el),
    triggerFileInput: () => document.getElementById('add-ext-file').click(),
    showAddExtensionForm: () => showAddExtensionForm(),
    switchAddExtTab: (el) => switchAddExtTab(el.dataset.tab),
    changeServerType: (el) => changeServerType(el.dataset.type),
});

registerActions('change', {
    toggleExtensionEnabled: (el) => toggleExtensionEnabled(el.dataset.extensionId, el.checked),
    toggleExtensionServerEnabled: (el) => toggleExtensionServerEnabled(el.dataset.serverType, el.dataset.serverId, el.checked),
});

registerActions('input', {
    onJsonEditorInput: () => onJsonEditorInput(),
});
