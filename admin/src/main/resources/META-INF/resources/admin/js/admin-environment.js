/**
 * Admin UI - Environment
 *
 * PATH sub-tab: application PATH entries with source and status.
 * Environment sub-tab: runtime environment variables.
 * Terminal sub-tab: command execution with the application PATH.
 */

import { registerActions } from './event-delegation.js';
import { isOnRuntimesTab } from './shared-state.js';

let environmentData = null;
let terminalEnabled = null;
let terminalHistory = [];
let historyIndex = -1;
const VIEW_PATH = 'path';
const VIEW_ENVIRONMENT = 'environment';
const VIEW_TERMINAL = 'terminal';
let activeView = null;

async function ensureEnvironmentData() {
    if (environmentData && terminalEnabled !== null) return true;

    const consoleArea = document.getElementById('console-area');
    if (!consoleArea) return false;

    consoleArea.innerHTML = '<div class="placeholder">Loading environment...</div>';

    try {
        const [envRes, termRes] = await Promise.all([
            environmentData ? Promise.resolve(null) : fetch('/api/admin/environment'),
            terminalEnabled === null ? fetch('/api/admin/environment/terminal-enabled') : Promise.resolve(null)
        ]);

        if (envRes) environmentData = await envRes.json();
        if (termRes) {
            const termData = await termRes.json();
            terminalEnabled = termData.enabled;
        }
        return true;
    } catch (e) {
        consoleArea.innerHTML = '<div class="placeholder text-error">Failed to load environment</div>';
        return false;
    }
}

export async function loadEnvironmentPath() {
    activeView = VIEW_PATH;
    if (!await ensureEnvironmentData()) return;
    renderPath();
}

export async function loadEnvironmentVars() {
    activeView = VIEW_ENVIRONMENT;
    if (!await ensureEnvironmentData()) return;
    renderVars();
}

export async function loadTerminal() {
    activeView = VIEW_TERMINAL;
    if (!await ensureEnvironmentData()) return;
    renderTerminalView();
}

export function resetEnvironmentView() {
    activeView = null;
}

export function onEnvironmentChanged() {
    const oldData = environmentData;
    fetch('/api/admin/environment')
        .then(r => r.json())
        .then(data => {
            environmentData = data;
            if (!isOnRuntimesTab()) return;
            if (activeView === VIEW_PATH) updatePath(oldData);
            else if (activeView === VIEW_ENVIRONMENT) updateVars(oldData);
        })
        .catch(() => {});
}

// ========== PATH view ==========

function pathRowKey(entry) {
    return entry.directory;
}

function pathRowHtml(entry) {
    if (entry.sourceType === 'system') {
        return `<td><code class="env-path">${esc(entry.directory)}</code></td>` +
               `<td><span class="env-source-badge system">System</span></td>` +
               `<td>${renderStatus(entry.exists, false)}</td>`;
    }
    return `<td><code class="env-path">${esc(entry.directory)}</code></td>` +
           `<td>${renderSourceBadge(entry)}</td>` +
           `<td>${renderStatus(entry.exists, true)}</td>`;
}

function pathRowClass(entry) {
    return entry.sourceType === 'system' ? 'env-system-row' : 'env-runtime-row';
}

function renderPath() {
    const consoleArea = document.getElementById('console-area');
    if (!consoleArea) return;

    const entries = environmentData.path;
    const runtimeEntries = entries.filter(e => e.sourceType !== 'system');
    const systemEntries = entries.filter(e => e.sourceType === 'system');

    let html = `
        <div class="details-panel text-primary detail-content" style="overflow-y: auto; height: 100%;">
            <h3 class="text-success mt-0">Application PATH</h3>
            <p class="text-dimmed mb-lg">Directories added by installed runtimes, followed by system PATH entries.</p>
            <table class="env-table" id="env-path-table">
                <thead>
                    <tr>
                        <th>Directory</th>
                        <th>Source</th>
                        <th>Status</th>
                    </tr>
                </thead>
                <tbody>
    `;

    if (runtimeEntries.length === 0) {
        html += '<tr id="env-path-empty"><td colspan="3" class="text-dimmed" style="padding:0.75rem">No runtime PATH entries (no runtimes installed via MCP)</td></tr>';
    }

    for (const entry of runtimeEntries) {
        html += `<tr class="${pathRowClass(entry)}" data-path-key="${esc(pathRowKey(entry))}">${pathRowHtml(entry)}</tr>`;
    }

    if (systemEntries.length > 0) {
        html += `<tr class="env-separator-row" id="env-path-separator"><td colspan="3" class="text-dimmed env-separator">System PATH (${systemEntries.length} entries)</td></tr>`;
        for (const entry of systemEntries) {
            html += `<tr class="${pathRowClass(entry)}" data-path-key="${esc(pathRowKey(entry))}">${pathRowHtml(entry)}</tr>`;
        }
    }

    html += '</tbody></table></div>';
    consoleArea.innerHTML = html;
}

function updatePath(oldData) {
    const table = document.getElementById('env-path-table');
    if (!table || !oldData) {
        renderPath();
        return;
    }

    const oldMap = new Map();
    for (const e of oldData.path) {
        oldMap.set(pathRowKey(e), e);
    }

    const newMap = new Map();
    for (const e of environmentData.path) {
        newMap.set(pathRowKey(e), e);
    }

    const changedKeys = new Set();
    const addedKeys = new Set();
    const removedKeys = new Set();

    for (const [key, newEntry] of newMap) {
        const oldEntry = oldMap.get(key);
        if (!oldEntry) {
            addedKeys.add(key);
        } else if (oldEntry.source !== newEntry.source || oldEntry.sourceType !== newEntry.sourceType || oldEntry.exists !== newEntry.exists) {
            changedKeys.add(key);
        }
    }
    for (const key of oldMap.keys()) {
        if (!newMap.has(key)) {
            removedKeys.add(key);
        }
    }

    if (addedKeys.size === 0 && removedKeys.size === 0 && changedKeys.size === 0) {
        return;
    }

    // Full re-render then highlight changed/added rows
    renderPath();
    highlightRows('data-path-key', changedKeys, addedKeys);
}

// ========== Environment vars view ==========

function envRowKey(entry) {
    return entry.name;
}

function envRowHtml(entry) {
    return `<td><code>${esc(entry.name)}</code></td>` +
           `<td><code class="env-path">${esc(entry.value)}</code></td>` +
           `<td>${renderSourceBadge(entry)}</td>`;
}

function renderVars() {
    const consoleArea = document.getElementById('console-area');
    if (!consoleArea) return;

    let html = '<div class="details-panel text-primary detail-content" style="overflow-y: auto; height: 100%;">';

    if (environmentData.env.length > 0) {
        html += `
            <h3 class="text-success mt-0">Environment Variables</h3>
            <p class="text-dimmed mb-lg">Variables set by installed runtimes.</p>
            <table class="env-table" id="env-vars-table">
                <thead>
                    <tr>
                        <th>Variable</th>
                        <th>Value</th>
                        <th>Source</th>
                    </tr>
                </thead>
                <tbody>
        `;

        for (const entry of environmentData.env) {
            html += `<tr class="env-runtime-row" data-env-key="${esc(envRowKey(entry))}">${envRowHtml(entry)}</tr>`;
        }

        html += '</tbody></table>';
    } else {
        html += `
            <h3 class="text-success mt-0">Environment Variables</h3>
            <p class="text-dimmed">No runtime environment variables configured.</p>
        `;
    }

    html += '</div>';
    consoleArea.innerHTML = html;
}

function updateVars(oldData) {
    const table = document.getElementById('env-vars-table');
    if (!table || !oldData) {
        renderVars();
        return;
    }

    const oldMap = new Map();
    for (const e of oldData.env) {
        oldMap.set(envRowKey(e), e);
    }

    const newMap = new Map();
    for (const e of environmentData.env) {
        newMap.set(envRowKey(e), e);
    }

    const changedKeys = new Set();
    const addedKeys = new Set();
    const removedKeys = new Set();

    for (const [key, newEntry] of newMap) {
        const oldEntry = oldMap.get(key);
        if (!oldEntry) {
            addedKeys.add(key);
        } else if (oldEntry.value !== newEntry.value || oldEntry.source !== newEntry.source || oldEntry.sourceType !== newEntry.sourceType) {
            changedKeys.add(key);
        }
    }
    for (const key of oldMap.keys()) {
        if (!newMap.has(key)) {
            removedKeys.add(key);
        }
    }

    if (addedKeys.size === 0 && removedKeys.size === 0 && changedKeys.size === 0) {
        return;
    }

    renderVars();
    highlightRows('data-env-key', changedKeys, addedKeys);
}

// ========== Highlight ==========

function highlightRows(attr, changedKeys, addedKeys) {
    for (const key of changedKeys) {
        const row = document.querySelector(`tr[${attr}="${CSS.escape(key)}"]`);
        if (row) {
            row.classList.add('env-row-changed');
            scheduleRemoveHighlight(row);
        }
    }
    for (const key of addedKeys) {
        const row = document.querySelector(`tr[${attr}="${CSS.escape(key)}"]`);
        if (row) {
            row.classList.add('env-row-added');
            scheduleRemoveHighlight(row);
        }
    }
}

function scheduleRemoveHighlight(row) {
    setTimeout(() => {
        row.classList.add('env-row-highlight-fade');
        row.addEventListener('animationend', () => {
            row.classList.remove('env-row-changed', 'env-row-added', 'env-row-highlight-fade');
        }, { once: true });
    }, 1500);
}

// ========== Terminal view ==========

function renderTerminalView() {
    const consoleArea = document.getElementById('console-area');
    if (!consoleArea) return;

    if (!terminalEnabled) {
        consoleArea.innerHTML = `
            <div class="details-panel text-primary detail-content" style="overflow-y: auto; height: 100%;">
                <h3 class="text-success mt-0">Terminal</h3>
                <p class="text-dimmed">Terminal is disabled. Set <code>mcp.admin.terminal.enabled=true</code> to enable.</p>
            </div>
        `;
        return;
    }

    consoleArea.innerHTML = `
        <div class="details-panel text-primary detail-content" style="overflow-y: auto; height: 100%;">
            <h3 class="text-success mt-0">Terminal</h3>
            <p class="text-dimmed mb-lg">Execute commands using the application PATH. Try <code>where go</code> or <code>node --version</code>.</p>
            <div class="terminal-container">
                <div class="terminal-input-row">
                    <span class="terminal-prompt">$</span>
                    <input type="text" id="terminal-input" class="terminal-input"
                           placeholder="Type a command..." autocomplete="off" spellcheck="false" />
                    <button data-action="execTerminalCommand" class="terminal-run-btn">Run</button>
                </div>
                <pre class="terminal-output" id="terminal-output"></pre>
            </div>
        </div>
    `;

    const input = document.getElementById('terminal-input');
    if (input) {
        input.addEventListener('keydown', handleTerminalKeydown);
        input.focus();
    }
}

// ========== Terminal input ==========

function handleTerminalKeydown(e) {
    if (e.key === 'Enter') {
        execTerminalCommand();
    } else if (e.key === 'ArrowUp') {
        e.preventDefault();
        if (historyIndex < terminalHistory.length - 1) {
            historyIndex++;
            e.target.value = terminalHistory[terminalHistory.length - 1 - historyIndex];
        }
    } else if (e.key === 'ArrowDown') {
        e.preventDefault();
        if (historyIndex > 0) {
            historyIndex--;
            e.target.value = terminalHistory[terminalHistory.length - 1 - historyIndex];
        } else {
            historyIndex = -1;
            e.target.value = '';
        }
    }
}

async function execTerminalCommand() {
    const input = document.getElementById('terminal-input');
    const output = document.getElementById('terminal-output');
    if (!input || !output) return;

    const command = input.value.trim();
    if (!command) return;

    terminalHistory.push(command);
    historyIndex = -1;

    output.textContent = '$ ' + command + '\nExecuting...\n';
    input.value = '';

    try {
        const res = await fetch('/api/admin/environment/exec', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ command })
        });

        const data = await res.json();

        if (res.ok) {
            const exitInfo = data.exitCode === 0 ? '' : `\n[Exit code: ${data.exitCode}]`;
            output.textContent = '$ ' + command + '\n' + (data.output || '(no output)') + exitInfo;
        } else {
            output.textContent = '$ ' + command + '\nError: ' + (data.message || 'Unknown error');
        }
    } catch (e) {
        output.textContent = '$ ' + command + '\nFailed to execute: ' + e.message;
    }

    input.focus();
}

// ========== Helpers ==========

function renderSourceBadge(entry) {
    if (entry.sourceType === 'system') {
        return '<span class="env-source-badge system">System</span>';
    }
    const runtimeId = entry.sourceType.replace('runtime:', '');
    return `<a class="env-source-badge runtime nav-link" data-action="navigateToRuntime" data-runtime-id="${esc(runtimeId)}">${esc(entry.source)}</a>`;
}

function renderStatus(exists, isRuntime) {
    if (exists) return '<span class="text-success">OK</span>';
    return isRuntime ? '<span class="text-error">Missing</span>' : '<span class="text-warning">Missing</span>';
}

function esc(str) {
    if (!str) return '';
    return str.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}

registerActions('click', {
    execTerminalCommand: () => execTerminalCommand()
});
