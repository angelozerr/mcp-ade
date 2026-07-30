/**
 * Admin UI - Extensions Management
 *
 * Handles listing, adding, removing, and enabling/disabling extensions
 * and their individual LSP/DAP servers.
 */

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
        const serverCount = (ext.lspServers?.length || 0) + (ext.dapServers?.length || 0);

        return `
            <div class="extension-item ${isActive} ${disabledClass}" onclick="showExtensionDetails('${ext.id}')">
                <div style="display: flex; align-items: center; justify-content: space-between;">
                    <span class="extension-name">${ext.id}${sourceBadge}</span>
                    <label class="toggle-switch" onclick="event.stopPropagation()">
                        <input type="checkbox" ${ext.enabled ? 'checked' : ''} onchange="toggleExtensionEnabled('${ext.id}', this.checked)">
                        <span class="toggle-slider"></span>
                    </label>
                </div>
                <div class="extension-id">${serverCount} server${serverCount !== 1 ? 's' : ''}</div>
            </div>
        `;
    }).join('');

    container.innerHTML = html;
}

/**
 * Load extensions from API and render.
 */
async function loadAllExtensions(extensionIdToSelect) {
    try {
        const response = await fetch('/api/admin/extensions');
        if (!response.ok) throw new Error('Failed to load extensions');
        extensionsData = (await response.json()).sort((a, b) => (a.id || '').localeCompare(b.id || ''));

        if (extensionIdToSelect) {
            selectedExtension = extensionIdToSelect;
        }

        renderExtensionsList();

        // Auto-select
        if (extensionsData.length > 0) {
            const toSelect = extensionIdToSelect
                || (selectedExtension && extensionsData.find(e => e.id === selectedExtension) ? selectedExtension : null)
                || extensionsData[0].id;
            showExtensionDetails(toSelect);
        }
    } catch (error) {
        console.error('Failed to load extensions:', error);
    }
}

/**
 * Show details for an extension in the console panel.
 */
function showExtensionDetails(extensionId) {
    selectedExtension = extensionId;

    // Re-render list to update active state (no re-fetch)
    renderExtensionsList();

    const ext = extensionsData.find(e => e.id === extensionId);
    if (!ext) return;

    const appContainer = document.querySelector('.app-container');
    const consoleColumn = document.querySelector('.console-container');
    consoleColumn.style.display = 'flex';
    appContainer.style.gridTemplateColumns = '400px 1fr';
    consoleColumn.style.gridColumn = '2';

    const sourceBadge = `<span class="extension-source-badge ${ext.source.toLowerCase()}">${ext.source}</span>`;

    // Build servers list HTML
    let serversHTML = '';
    if (ext.lspServers && ext.lspServers.length > 0) {
        serversHTML += '<h4 class="text-label" style="margin-top: 1.5rem;">LSP Servers</h4>';
        serversHTML += ext.lspServers.map(server => {
            const disabledClass = !server.enabled ? 'server-disabled' : '';
            return `
                <div class="extension-server-item ${disabledClass}">
                    <span style="cursor: pointer;" onclick="switchTab('lsp-servers', null, {serverId: '${server.id}'})"><span class="server-source-icon">🚀</span> ${server.name} <span class="text-dimmed" style="font-size: 0.75rem;">(${server.id})</span></span>
                    <label class="toggle-switch">
                        <input type="checkbox" ${server.enabled ? 'checked' : ''} onchange="toggleExtensionServerEnabled('lsp', '${server.id}', this.checked)">
                        <span class="toggle-slider"></span>
                    </label>
                </div>
            `;
        }).join('');
    }

    if (ext.dapServers && ext.dapServers.length > 0) {
        serversHTML += '<h4 class="text-success" style="margin-top: 1.5rem;">DAP Servers</h4>';
        serversHTML += ext.dapServers.map(server => {
            const disabledClass = !server.enabled ? 'server-disabled' : '';
            return `
                <div class="extension-server-item ${disabledClass}">
                    <span style="cursor: pointer;" onclick="switchTab('dap-servers', null, {serverId: '${server.id}'})"><span class="server-source-icon">🐛</span> ${server.name} <span class="text-dimmed" style="font-size: 0.75rem;">(${server.id})</span></span>
                    <label class="toggle-switch">
                        <input type="checkbox" ${server.enabled ? 'checked' : ''} onchange="toggleExtensionServerEnabled('dap', '${server.id}', this.checked)">
                        <span class="toggle-slider"></span>
                    </label>
                </div>
            `;
        }).join('');
    }

    if (!serversHTML) {
        serversHTML = '<p class="text-dimmed" style="margin-top: 1rem;">No servers in this extension.</p>';
    }

    // Remove button (only for USER extensions)
    const removeButton = ext.source === 'USER' ? `
        <div style="margin-top: 2rem; padding-top: 1.5rem; border-top: 1px solid var(--border-subtle);">
            <button class="btn-danger" onclick="removeExtension('${ext.id}')">
                Remove Extension
            </button>
        </div>
    ` : '';

    const consoleArea = document.getElementById('console-area');
    consoleArea.innerHTML = `
        <div class="console-header">
            <div class="console-title">
                <span class="server-source-icon">🧩</span>
                ${ext.id} ${sourceBadge}
            </div>
        </div>
        <div class="details-panel text-primary" style="padding: 2rem; overflow-y: auto;">
            <h3 class="text-label" style="margin-top: 0;">Extension Information</h3>

            <div style="margin-bottom: 1rem;">
                <strong class="text-label">ID:</strong>
                <span class="text-value" style="margin-left: 0.5rem;">${ext.id}</span>
            </div>

            <div style="margin-bottom: 1rem;">
                <strong class="text-label">Source:</strong>
                <span style="margin-left: 0.5rem;">${sourceBadge}</span>
            </div>

            <div style="margin-bottom: 1rem;">
                <strong class="text-label">Status:</strong>
                <span class="${ext.enabled ? 'text-success' : 'text-error'}" style="margin-left: 0.5rem;">${ext.enabled ? 'Enabled' : 'Disabled'}</span>
            </div>

            ${serversHTML}
            ${removeButton}
        </div>
    `;
}

/**
 * Show the add extension form in the console panel.
 */
function showAddExtensionForm() {
    selectedExtension = null;
    renderExtensionsList();

    const appContainer = document.querySelector('.app-container');
    const consoleColumn = document.querySelector('.console-container');
    consoleColumn.style.display = 'flex';
    appContainer.style.gridTemplateColumns = '400px 1fr';
    consoleColumn.style.gridColumn = '2';

    const consoleArea = document.getElementById('console-area');
    consoleArea.innerHTML = `
        <div class="console-header">
            <div class="console-title">
                <span class="server-source-icon">🧩</span>
                Add Extension
            </div>
        </div>
        <div class="details-panel text-primary" style="padding: 2rem;">
            <h3 class="text-label" style="margin-top: 0;">Add a New Extension</h3>
            <p class="text-secondary" style="margin-bottom: 1.5rem;">
                Upload a ZIP or JAR file containing lsp/ and/or dap/ subdirectories with server configurations.
            </p>

            <div style="margin-bottom: 1.25rem;">
                <label class="text-label" style="display: block; margin-bottom: 0.35rem; font-weight: 500;">Extension ID</label>
                <input type="text" id="add-ext-id" placeholder="e.g. my-extension"
                       class="input-field" style="width: 100%; max-width: 400px; font-size: 0.9rem;">
            </div>

            <div id="drop-zone" class="drop-zone" onclick="document.getElementById('add-ext-file').click()">
                <input type="file" id="add-ext-file" accept=".zip,.jar" style="display: none;" onchange="handleFileSelect(this)">
                <div class="drop-zone-icon">📦</div>
                <div class="drop-zone-text">Drop a ZIP or JAR file here</div>
                <div class="drop-zone-hint">or click to browse</div>
            </div>

            <div id="selected-file-info" style="display: none; margin-bottom: 1.25rem;">
                <div style="display: flex; align-items: center; gap: 0.5rem; padding: 0.5rem 0.75rem; background: var(--bg-card-alt); border: 1px solid var(--border-subtle); border-radius: 3px; max-width: 400px;">
                    <span class="text-success">📄</span>
                    <span id="selected-file-name" class="text-value" style="flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;"></span>
                    <span class="text-dimmed" style="cursor: pointer; font-size: 1.1rem;" onclick="clearSelectedFile()" title="Remove file">×</span>
                </div>
            </div>

            <button class="btn-primary" onclick="addExtension()">
                Add Extension
            </button>

            <div id="add-ext-result" style="margin-top: 1rem;"></div>
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
            if (window.loadLspConfigs) await window.loadLspConfigs();
            if (window.loadDapConfigs) await window.loadDapConfigs();
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
    if (!window.confirmAction) return;

    const confirmed = await window.confirmAction(
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
            if (window.loadLspConfigs) await window.loadLspConfigs();
            if (window.loadDapConfigs) await window.loadDapConfigs();
            loadAllExtensions();
        } else {
            if (window.showAlert) window.showAlert('Error', result.error || 'Failed to remove extension');
        }
    } catch (error) {
        console.error('Failed to remove extension:', error);
        if (window.showAlert) window.showAlert('Error', error.message);
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
                const serverList = type === 'lsp' ? ext.lspServers : ext.dapServers;
                const srv = serverList?.find(s => s.id === serverId);
                if (srv) { srv.enabled = enabled; break; }
            }
            if (selectedExtension) showExtensionDetails(selectedExtension);
        }
    } catch (error) {
        console.error(`Failed to ${action} ${type} server:`, error);
    }
}

// Expose functions globally
window.loadAllExtensions = loadAllExtensions;
window.showExtensionDetails = showExtensionDetails;
window.showAddExtensionForm = showAddExtensionForm;
window.addExtension = addExtension;
window.removeExtension = removeExtension;
window.toggleExtensionEnabled = toggleExtensionEnabled;
window.toggleExtensionServerEnabled = toggleExtensionServerEnabled;
