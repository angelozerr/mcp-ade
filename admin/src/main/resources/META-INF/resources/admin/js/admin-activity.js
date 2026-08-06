import { escapeHtml } from './trace-renderer.js';
import { registerActions } from './event-delegation.js';

let operations = [];
// let activityFilter = 'all';
const expandedOps = new Set();
const collapsedServers = new Set();
const collapsedWorkspaces = new Set();
let allFolded = false;
let tickInterval = null;
let mcpToolsMap = null;
let activityEnabled = false;
const activeReplays = new Map();

async function ensureToolsLoaded() {
    if (mcpToolsMap) return;
    try {
        const response = await fetch('/api/admin/mcp/tools');
        const tools = await response.json();
        mcpToolsMap = {};
        for (const tool of tools) {
            mcpToolsMap[tool.name] = tool;
        }
    } catch (e) {
        mcpToolsMap = {};
    }
}

function getToolDescription(toolName) {
    if (!mcpToolsMap) return null;
    const tool = mcpToolsMap[toolName];
    return tool ? tool.description : null;
}

function startTick() {
    if (tickInterval) return;
    tickInterval = setInterval(() => {
        const hasRunning = operations.some(op => op.status === 'RUNNING');
        if (hasRunning) {
            renderActivity();
        } else {
            clearInterval(tickInterval);
            tickInterval = null;
        }
    }, 500);
}

export function handleOperationUpdate(msg) {
    const idx = operations.findIndex(op => op.id === msg.id);
    if (idx >= 0) {
        operations[idx] = msg;
    } else {
        operations.push(msg);
        if (msg.status === 'RUNNING') {
            expandedOps.add(msg.id);
        }
    }

    if (operations.length > 500) {
        operations = operations.slice(-500);
    }

    ensureToolsLoaded().then(() => renderActivity());

    if (msg.status === 'RUNNING') {
        startTick();
    }

    if (msg.status === 'COMPLETED' || msg.status === 'FAILED') {
        for (const [opId, replayId] of activeReplays) {
            const origOp = operations.find(o => o.id === opId);
            if (origOp && origOp.name === msg.name && msg.id !== opId) {
                activeReplays.delete(opId);
            }
        }
    }
}

export function renderActivity() {
    const container = document.getElementById('mcp-activity-content')
        || document.getElementById('mcp-activity-tab');
    if (!container) return;

    const filtered = operations;

    if (filtered.length === 0) {
        container.innerHTML = '<div class="text-secondary p-lg">No operations recorded yet</div>';
        return;
    }

    const grouped = {};
    for (const op of filtered) {
        const key = op.workspaceUri || 'global';
        if (!grouped[key]) grouped[key] = [];
        grouped[key].push(op);
    }

    const esc = escapeHtml;
    let html = '';
    for (const [ws, ops] of Object.entries(grouped)) {
        const wsLabel = ws === 'global' ? 'Global' : ws.split('/').pop().split('\\').pop();
        const wsExpanded = allFolded ? false : !collapsedWorkspaces.has(ws);
        html += `<div class="activity-workspace-group">`;
        html += `<div class="activity-workspace-header" data-action="toggleWorkspace" data-ws-key="${esc(ws)}">`;
        html += `<span class="activity-toggle">${wsExpanded ? '&#9660;' : '&#9654;'}</span>`;
        html += `${esc(wsLabel)} <span class="activity-workspace-count">${ops.length}</span>`;
        html += `</div>`;
        html += `<div class="activity-workspace-body" style="display: ${wsExpanded ? 'block' : 'none'};">`;
        for (const op of ops.slice().reverse()) {
            html += renderOperation(op, esc);
        }
        html += `</div>`;
        html += `</div>`;
    }

    container.innerHTML = html;
}

function liveDuration(item) {
    if (item.status === 'RUNNING') {
        return Date.now() - item.startTime;
    }
    return item.durationMs;
}

function shortenValue(v) {
    if (typeof v === 'number') return String(v);
    const s = typeof v === 'string' ? v : JSON.stringify(v);
    if (s.startsWith('file:///') || s.startsWith('file:\\\\')) {
        const name = s.split(/[/\\]/).pop();
        return name || s;
    }
    if (s.length > 30) return s.substring(0, 27) + '...';
    return s;
}

function formatArgsSummary(args) {
    if (!args) return '';
    const parts = [];
    for (const [key, value] of Object.entries(args)) {
        if (key === 'cwd' || key === 'commandId') continue;
        if (key === 'arguments' && typeof value === 'object' && value !== null) {
            flattenArgs(value, parts);
            continue;
        }
        parts.push(shortenValue(value));
    }
    return parts.length > 0 ? `(${parts.join(', ')})` : '';
}

function flattenArgs(obj, parts) {
    if (Array.isArray(obj)) {
        for (const item of obj) {
            if (typeof item === 'object' && item !== null) {
                flattenArgs(item, parts);
            } else {
                parts.push(shortenValue(item));
            }
        }
    } else {
        for (const v of Object.values(obj)) {
            parts.push(shortenValue(v));
        }
    }
}

function renderOperation(op, esc) {
    const statusIcon = getStatusIcon(op.status);
    const duration = formatDuration(liveDuration(op));
    const hasEntries = op.entries && op.entries.length > 0;
    const desc = getToolDescription(op.name);
    const titleAttr = desc ? ` title="${esc(desc)}"` : '';
    const argsSummary = formatArgsSummary(op.arguments);

    let html = `<div class="activity-operation" data-op-id="${op.id}">`;
    html += `<div class="activity-operation-header" data-action="toggleActivityOperation">`;
    const expanded = expandedOps.has(op.id);
    if (hasEntries) {
        html += `<span class="activity-toggle">${expanded ? '&#9660;' : '&#9654;'}</span>`;
    } else {
        html += `<span class="activity-toggle-spacer"></span>`;
    }
    html += `<span class="activity-status-icon">${statusIcon}</span>`;
    html += `<span class="activity-operation-info"${titleAttr}>`;
    html += `<span class="activity-operation-name">${esc(op.name)}</span>`;
    if (argsSummary) {
        html += ` <span class="activity-operation-args">${esc(argsSummary)}</span>`;
    }
    html += `</span>`;
    html += `<span class="activity-duration">${duration}</span>`;
    if (op.status !== 'RUNNING' && op.arguments) {
        if (activeReplays.has(op.id)) {
            html += `<span class="activity-replay replaying" data-action="cancelReplay" data-op-id="${op.id}" title="Cancel replay">&#10007;</span>`;
        } else {
            html += `<span class="activity-replay" data-action="replayOperation" data-op-id="${op.id}" title="Replay">&#8635;</span>`;
        }
    }
    html += `</div>`;

    if (hasEntries) {
        html += `<div class="activity-entries" style="display: ${expanded ? 'block' : 'none'};">`;
        for (const entry of op.entries) {
            html += renderEntry(entry, esc, 1, op.id);
        }
        html += `</div>`;
    }
    html += `</div>`;
    return html;
}

function renderEntry(entry, esc, depth, opId) {
    const statusIcon = getStatusIcon(entry.status);
    const duration = formatDuration(liveDuration(entry));
    const hasChildren = entry.children && entry.children.length > 0;

    if (depth === 1 && hasChildren) {
        const serverKey = opId + ':' + entry.name;
        const serverExpanded = allFolded ? false : !collapsedServers.has(serverKey);
        let html = `<div class="activity-server-group">`;
        html += `<div class="activity-server-entry" data-action="toggleServerEntry" data-server-key="${esc(serverKey)}">`;
        html += `<span class="activity-toggle">${serverExpanded ? '&#9660;' : '&#9654;'}</span>`;
        html += `<span class="activity-status-icon">${statusIcon}</span>`;
        html += `<span class="activity-server-name">${esc(entry.name)}</span>`;
        html += `<span class="activity-duration">${duration}</span>`;
        html += `</div>`;
        html += `<div class="activity-entry-children" style="display: ${serverExpanded ? 'block' : 'none'};">`;
        for (const child of entry.children) {
            html += renderEntry(child, esc, depth + 1, opId);
        }
        html += `</div></div>`;
        return html;
    }

    let html = `<div class="activity-entry">`;
    if (depth > 1) {
        html += `<span class="activity-entry-connector"></span>`;
    }
    html += `<span class="activity-status-icon">${statusIcon}</span>`;
    html += `<span class="activity-entry-name">${esc(entry.name)}</span>`;
    html += `<span class="activity-duration">${duration}</span>`;
    html += `</div>`;

    if (hasChildren) {
        for (const child of entry.children) {
            html += renderEntry(child, esc, depth + 1, opId);
        }
    }
    return html;
}

function getStatusIcon(status) {
    switch (status) {
        case 'RUNNING': return '<span class="activity-spinner"></span>';
        case 'COMPLETED': return '<span class="text-success">&#10003;</span>';
        case 'FAILED': return '<span class="text-error">&#10007;</span>';
        default: return '';
    }
}

function formatDuration(ms) {
    if (ms < 1) return '<1ms';
    if (ms < 1000) return ms + 'ms';
    return (ms / 1000).toFixed(1) + 's';
}

function toggleActivityOperation(el) {
    const opEl = el.closest('.activity-operation');
    if (!opEl) return;
    const entries = opEl.querySelector('.activity-entries');
    if (!entries) return;
    const isVisible = entries.style.display !== 'none';
    entries.style.display = isVisible ? 'none' : 'block';
    const toggle = opEl.querySelector('.activity-toggle');
    if (toggle) {
        toggle.innerHTML = isVisible ? '&#9654;' : '&#9660;';
    }
    const opId = opEl.dataset.opId;
    if (opId) {
        if (isVisible) {
            expandedOps.delete(opId);
        } else {
            expandedOps.add(opId);
        }
    }
}

function toggleServerEntry(el) {
    const serverEl = el.closest('.activity-server-group');
    if (!serverEl) return;
    const key = el.dataset.serverKey || el.closest('[data-server-key]')?.dataset.serverKey;
    if (!key) return;
    const children = serverEl.querySelector('.activity-entry-children');
    if (!children) return;
    const isVisible = children.style.display !== 'none';
    children.style.display = isVisible ? 'none' : 'block';
    const toggle = serverEl.querySelector('.activity-toggle');
    if (toggle) {
        toggle.innerHTML = isVisible ? '&#9654;' : '&#9660;';
    }
    allFolded = false;
    if (isVisible) {
        collapsedServers.add(key);
    } else {
        collapsedServers.delete(key);
    }
}

function toggleWorkspace(el) {
    const key = el.dataset.wsKey || el.closest('[data-ws-key]')?.dataset.wsKey;
    if (!key) return;
    const group = el.closest('.activity-workspace-group');
    if (!group) return;
    const body = group.querySelector('.activity-workspace-body');
    if (!body) return;
    const isVisible = body.style.display !== 'none';
    body.style.display = isVisible ? 'none' : 'block';
    const toggle = el.querySelector('.activity-toggle') || el.closest('.activity-workspace-header')?.querySelector('.activity-toggle');
    if (toggle) {
        toggle.innerHTML = isVisible ? '&#9654;' : '&#9660;';
    }
    allFolded = false;
    if (isVisible) {
        collapsedWorkspaces.add(key);
    } else {
        collapsedWorkspaces.delete(key);
    }
}

function foldAllActivity() {
    expandedOps.clear();
    collapsedServers.clear();
    collapsedWorkspaces.clear();
    allFolded = true;
    renderActivity();
}

function clearActivity() {
    operations = [];
    expandedOps.clear();
    renderActivity();
}

export function handleActivityState(msg) {
    activityEnabled = msg.enabled;
    updateActivityToggleUI();
}

export function updateActivityToggleUI() {
    const checkbox = document.getElementById('activity-toggle-checkbox');
    if (checkbox) {
        checkbox.checked = activityEnabled;
    }
}

async function toggleActivity(el) {
    const newState = el.checked;
    try {
        const resp = await fetch('/api/admin/activity/enabled', {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ enabled: newState })
        });
        if (resp.ok) {
            activityEnabled = newState;
        } else {
            el.checked = !newState;
        }
    } catch (e) {
        el.checked = !newState;
    }
}

async function replayOperation(el) {
    const opEl = el.closest('[data-op-id]');
    if (!opEl) return;
    const opId = opEl.dataset.opId;
    const op = operations.find(o => o.id === opId);
    if (!op || !op.arguments) return;
    if (activeReplays.has(opId)) return;

    try {
        const resp = await fetch('/api/admin/activity/replay', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ toolName: op.name, arguments: op.arguments })
        });
        if (resp.ok) {
            const data = await resp.json();
            activeReplays.set(opId, data.replayId);
            renderActivity();
        }
    } catch (e) {
        // ignore
    }
}

async function cancelReplay(el) {
    const opEl = el.closest('[data-op-id]');
    if (!opEl) return;
    const opId = opEl.dataset.opId;
    const replayId = activeReplays.get(opId);
    if (!replayId) return;

    try {
        await fetch(`/api/admin/activity/replay/${replayId}`, { method: 'DELETE' });
    } catch (e) {
        // ignore
    }
    activeReplays.delete(opId);
    renderActivity();
}

registerActions('click', {
    toggleActivityOperation: (el) => toggleActivityOperation(el),
    toggleServerEntry: (el) => toggleServerEntry(el),
    toggleWorkspace: (el) => toggleWorkspace(el),
    foldAllActivity: () => foldAllActivity(),
    clearActivity: () => clearActivity(),
    replayOperation: (el) => { el.stopPropagation?.(); replayOperation(el); },
    cancelReplay: (el) => { el.stopPropagation?.(); cancelReplay(el); },
});

registerActions('change', {
    toggleActivity: (el) => toggleActivity(el),
});
