import { state, getServerApiBase } from './shared-state.js';
import { renderSettingsPanel, renderServerSetting } from './admin-settings.js';
import { renderServerDiagram } from './diagram.js';

export function renderDocumentSelector(selectors) {
    if (!selectors || selectors.length === 0) {
        return '<p class="text-secondary">None configured</p>';
    }
    return selectors.map(selector => `
        <div class="selector-item">
            ${selector.language ? `<span class="selector-tag">language: ${selector.language}</span>` : ''}
            ${selector.scheme ? `<span class="selector-tag">scheme: ${selector.scheme}</span>` : ''}
            ${selector.pattern ? `<span class="selector-tag">pattern: ${selector.pattern}</span>` : ''}
        </div>
    `).join('');
}

export function showModal(title, message, buttons) {
    const modal = document.getElementById('modal-overlay');
    const modalTitle = document.getElementById('modal-title');
    const modalMessage = document.getElementById('modal-message');
    const modalButtons = document.getElementById('modal-buttons');

    modalTitle.textContent = title;
    modalMessage.textContent = message;

    modalButtons.innerHTML = buttons.map(btn =>
        `<button class="modal-button ${btn.type || 'secondary'}" data-action="${btn.action}">${btn.label}</button>`
    ).join('');

    modalButtons.querySelectorAll('[data-action]').forEach(el => {
        el.addEventListener('click', () => {
            if (el.dataset.action === 'modal-cancel') {
                hideModal();
                if (state.modalResolve) state.modalResolve(false);
            } else if (el.dataset.action === 'modal-confirm') {
                hideModal();
                if (state.modalResolve) state.modalResolve(true);
            } else if (el.dataset.action === 'modal-ok') {
                hideModal();
            }
        });
    });

    modal.classList.add('visible');
}

export function hideModal() {
    document.getElementById('modal-overlay').classList.remove('visible');
}

export async function confirmAction(title, message, confirmLabel, isDanger = false) {
    return new Promise((resolve) => {
        state.modalResolve = resolve;
        showModal(title, message, [
            { label: 'Cancel', type: 'secondary', action: 'modal-cancel' },
            { label: confirmLabel, type: isDanger ? 'danger' : 'primary', action: 'modal-confirm' }
        ]);
    });
}

export function showAlert(title, message) {
    showModal(title, message, [
        { label: 'OK', type: 'primary', action: 'modal-ok' }
    ]);
}

export function showConfirmModal(title, message, onConfirm) {
    const titleEl = document.getElementById('confirm-modal-title');
    const messageEl = document.getElementById('confirm-modal-message');
    const modalEl = document.getElementById('confirm-modal');

    if (!titleEl || !messageEl || !modalEl) {
        console.error('Confirm modal elements not found!');
        return;
    }

    titleEl.textContent = title;
    messageEl.innerHTML = message;
    modalEl.classList.add('visible');

    const confirmBtn = document.getElementById('modal-confirm-btn');
    confirmBtn.onclick = () => {
        hideConfirmModal();
        onConfirm();
    };
}

export function hideConfirmModal() {
    document.getElementById('confirm-modal').classList.remove('visible');
}

// ========== Shared Installer Functions (LSP, DAP, BSP) ==========

export async function runServerInstaller(serverId, force, outputDivId, installUrl) {
    const outputDiv = document.getElementById(outputDivId);
    if (!outputDiv) return;

    const label = force ? 'Force installing' : 'Installing';
    outputDiv.innerHTML = `
        <div class="install-output-header text-success mb-sm">${label} ${serverId}...</div>
        <div id="install-progress-bar" class="bg-input mb-sm d-none" style="height: 4px; border-radius: 2px;">
            <div id="install-progress-fill" style="height: 100%; background: var(--color-success); border-radius: 2px; width: 0%; transition: width 0.3s;"></div>
        </div>
        <div id="install-traces" class="font-mono bg-card p-sm rounded-sm font-sm overflow-auto" style="max-height: 300px;"></div>
    `;

    state.installOutputServerId = serverId;

    try {
        const url = force ? `${installUrl}${installUrl.includes('?') ? '&' : '?'}force=true` : installUrl;
        const response = await fetch(url, { method: 'POST' });

        if (!response.ok) {
            state.installOutputServerId = null;
            throw new Error('Installation failed');
        }
    } catch (error) {
        console.error('Failed to run installer:', error);
        state.installOutputServerId = null;
        outputDiv.innerHTML = `<div class="text-error">Installation failed: ${error.message}</div>`;
    }
}

export function appendInstallTrace(trace) {
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

export function updateInstallProgress(msg) {
    const bar = document.getElementById('install-progress-bar');
    const fill = document.getElementById('install-progress-fill');
    const header = document.querySelector('.install-output-header');

    if (bar && fill) {
        bar.style.display = 'block';
        fill.style.width = `${Math.round((msg.progress || 0) * 100)}%`;
    }

    if (msg.status === 'completed') {
        state.installOutputServerId = null;
        if (fill) fill.style.background = 'var(--color-success)';
        if (header) {
            header.style.color = 'var(--color-success)';
            header.textContent = `Installation completed`;
        }
    } else if (msg.status === 'failed') {
        state.installOutputServerId = null;
        if (fill) fill.style.background = 'var(--color-error-text)';
        if (header) {
            header.style.color = 'var(--color-error-text)';
            header.textContent = `Installation failed`;
        }
    }
}

export function initModalOverlay() {
    const modalOverlay = document.getElementById('modal-overlay');
    if (modalOverlay) {
        modalOverlay.addEventListener('click', (e) => {
            if (e.target.id === 'modal-overlay') {
                hideModal();
                if (state.modalResolve) {
                    state.modalResolve(false);
                }
            }
        });
    }
}

// ========== Shared Server Management Functions ==========

export async function loadInstallerJsonEditor(serverId, editorId) {
    try {
        const response = await fetch(`${getServerApiBase(serverId)}/${serverId}/installer`);
        if (!response.ok) throw new Error('Failed to load installer.json');
        const installerJson = await response.json();
        const editor = document.getElementById(editorId);
        if (editor) editor.value = JSON.stringify(installerJson, null, 2);
    } catch (error) {
        console.error('Failed to load installer.json:', error);
        const editor = document.getElementById(editorId);
        if (editor) editor.value = '// No installer.json found';
    }
}

export async function saveInstallerJsonEditor(serverId, editorId) {
    const editor = document.getElementById(editorId);
    if (!editor) return;
    try {
        const installerJson = JSON.parse(editor.value);
        const response = await fetch(`${getServerApiBase(serverId)}/${serverId}/installer`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(installerJson)
        });
        if (!response.ok) throw new Error('Failed to save installer.json');
        showAlert('Success', 'Installer configuration saved successfully.');
    } catch (error) {
        console.error('Failed to save installer.json:', error);
        showAlert('Error', 'Failed to save installer.json: ' + error.message);
    }
}

export function switchServerTabs(panelPrefix, tab, onSwitch) {
    document.querySelectorAll('#console-area .tab-button').forEach(btn => {
        btn.classList.toggle('active', btn.dataset.tab === tab);
    });
    document.querySelectorAll('#console-area .tab-panel').forEach(panel => {
        panel.classList.toggle('active', panel.id === `${panelPrefix}-${tab}-tab`);
    });
    if (tab === 'contributions' && state.currentDiagramServers && state.currentDiagramServerId) {
        setTimeout(() => renderServerDiagram(state.currentDiagramServers, state.currentDiagramServerId), 100);
    }
    if (onSwitch) onSwitch(tab);
}

export async function toggleServerEnabled(serverType, serverId, enabled, configs, reloadFn) {
    const action = enabled ? 'enable' : 'disable';
    try {
        const response = await fetch(`/api/admin/extensions/${serverType}/servers/${serverId}/${action}`, { method: 'POST' });
        if (response.ok) {
            if (configs[serverId]) configs[serverId].enabled = enabled;
            reloadFn();
        }
    } catch (error) {
        console.error(`Failed to ${action} ${serverType.toUpperCase()} server:`, error);
    }
}

export async function changeServerTraceLevel(protocol, serverId, level) {
    if (state.traceLevels) {
        state.traceLevels[`${protocol}.${serverId}`] = level;
    }
    try {
        await fetch(`/api/admin/traces/${protocol}/${serverId}`, {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ traceLevel: level })
        });
    } catch (e) {
        console.error(`Failed to save ${protocol.toUpperCase()} trace level:`, e);
    }
}

export function buildServerSettingsHTML(protocol, server, changeAction, extraSettings) {
    const traceLevel = (state.traceLevels && state.traceLevels[`${protocol}.${server.id}`]) || 'off';
    const traceSetting = {
        key: 'trace',
        label: 'Trace Level',
        description: 'Controls protocol message tracing',
        type: 'enum',
        values: ['off', 'messages', 'verbose'],
        currentValue: traceLevel,
        source: null
    };
    const traceItems = [renderServerSetting(traceSetting, changeAction, null, { 'server-id': server.id })];
    const regularItems = (extraSettings || []).map(setting =>
        renderServerSetting({ ...setting, source: null }, changeAction, null, { 'server-id': server.id })
    );
    return renderSettingsPanel({
        title: 'Settings',
        itemsHtml: [...traceItems, ...regularItems]
    });
}

export function renderServerActions(serverId, server) {
    if (server.isBsp) {
        if (server.status === 'RUNNING' || server.status === 'STARTING' || server.status === 'INSTALLING') {
            return `<button class="server-action-btn" data-action="restartBspServerAction" data-server-id="${serverId}" data-stop-propagation title="Restart">↻</button>
                    <button class="server-action-btn" data-action="stopBspServerAction" data-server-id="${serverId}" data-stop-propagation title="Stop">■</button>`;
        }
        return `<button class="server-action-btn" data-action="startBspServerAction" data-server-id="${serverId}" data-stop-propagation title="Start">▶</button>`;
    }
    const isExternal = server.externalInstance != null &&
                       (server.status === 'CONNECTED_TO_IDE' || server.status === 'CONNECTING_TO_IDE');
    if (isExternal) {
        return `<button class="server-action-btn server-action-disconnect"
                        data-action="disconnectFromIdeAction" data-server-id="${serverId}" data-stop-propagation
                        title="Disconnect from IDE">⏏</button>`;
    }
    if (server.status === 'RUNNING' || server.status === 'STARTING' || server.status === 'INDEXING') {
        return `<button class="server-action-btn" data-action="restartServerAction" data-server-id="${serverId}" data-stop-propagation title="Restart">↻</button>
                <button class="server-action-btn" data-action="stopServerAction" data-server-id="${serverId}" data-stop-propagation title="Stop">■</button>`;
    }
    if (server.status === 'STOPPED' || server.status === 'START_FAILED' || server.status === 'INSTALL_FAILED' || server.status === 'ERROR') {
        return `<button class="server-action-btn" data-action="startManagedServerAction" data-server-id="${serverId}" data-stop-propagation title="Start MCP-managed server">▶</button>
                <button class="server-action-btn" data-action="connectToIdeAction" data-server-id="${serverId}" data-stop-propagation title="Try to connect to IDE instance">🔗</button>`;
    }
    return '';
}
