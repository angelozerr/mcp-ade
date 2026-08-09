import { escapeHtml } from './trace-renderer.js';
import { registerActions } from './event-delegation.js';

let operations = [];
const expandedOps = new Set();
const userExpandedOps = new Set();
const collapsedServers = new Set();
const collapsedWorkspaces = new Set();
const collapsedSessions = new Set();
let allFolded = false;
let tickHandle = null;
let mcpToolsMap = null;
let activityEnabled = false;
const activeReplays = new Map();
const toggledSections = new Set();

const pendingUpdates = new Set();
const pendingNewOps = new Set();
let updateRAF = null;
let scrollToOpAfterFlush = null;
let pendingReplayToolName = null;
let visibilityObserver = null;

function getVisibilityObserver() {
    if (visibilityObserver) return visibilityObserver;
    const root = document.getElementById('mcp-activity-content')
        || document.getElementById('mcp-activity-tab');
    const scrollRoot = root ? (root.closest('.activity-list') || root) : null;
    visibilityObserver = new IntersectionObserver((entries) => {
        for (const entry of entries) {
            const opEl = entry.target;
            const opId = opEl.dataset.opId;
            if (!opId || !expandedOps.has(opId) || userExpandedOps.has(opId) || !entry.target.isConnected) continue;
            if (!entry.isIntersecting) {
                expandedOps.delete(opId);
                const body = opEl.querySelector('.activity-operation-body');
                if (body) body.style.display = 'none';
                const toggle = opEl.querySelector('.activity-toggle');
                if (toggle) toggle.innerHTML = '&#9654;';
            }
        }
    }, { root: scrollRoot, rootMargin: '0px' });
    return visibilityObserver;
}

function observeOperation(opEl) {
    if (!opEl) return;
    getVisibilityObserver().observe(opEl);
}

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

function scheduleTick() {
    if (tickHandle) return;
    tickHandle = setTimeout(() => {
        tickHandle = null;
        if (operations.some(op => op.status === 'RUNNING')) {
            tickDurations();
            scheduleTick();
        }
    }, 500);
}

function tickDurations() {
    const container = document.getElementById('mcp-activity-content')
        || document.getElementById('mcp-activity-tab');
    if (!container) return;
    for (const op of operations) {
        if (op.status !== 'RUNNING') continue;
        const el = container.querySelector(`.activity-operation[data-op-id="${op.id}"]`);
        if (!el) continue;
        const durEl = el.querySelector(':scope > .activity-operation-header > .activity-duration');
        if (durEl) {
            durEl.textContent = formatDuration(liveDuration(op));
        }
        for (const dur of el.querySelectorAll('.activity-duration')) {
            if (dur === durEl) continue;
            const entry = dur.closest('.activity-entry, .activity-server-entry');
            if (!entry) continue;
            const name = entry.querySelector('.activity-entry-name, .activity-server-name');
            if (!name) continue;
            const entryData = findEntry(op.entries, name.textContent);
            if (entryData && entryData.status === 'RUNNING') {
                dur.textContent = formatDuration(liveDuration(entryData));
            }
        }
    }
}

function findEntry(entries, name) {
    if (!entries) return null;
    for (const e of entries) {
        if (e.name === name) return e;
        if (e.children) {
            const found = findEntry(e.children, name);
            if (found) return found;
        }
    }
    return null;
}

export function handleOperationUpdate(msg) {
    const idx = operations.findIndex(op => op.id === msg.id);
    if (idx >= 0) {
        operations[idx] = msg;
        pendingUpdates.add(msg.id);
    } else {
        operations.push(msg);
        if (msg.status === 'RUNNING') {
            expandedOps.add(msg.id);
        }
        pendingNewOps.add(msg.id);
        if (msg.status === 'RUNNING' && pendingReplayToolName && msg.name === pendingReplayToolName) {
            scrollToOpAfterFlush = msg.id;
            pendingReplayToolName = null;
        }
    }

    if (operations.length > 500) {
        operations = operations.slice(-500);
    }

    if (!updateRAF) {
        updateRAF = requestAnimationFrame(() => {
            updateRAF = null;
            ensureToolsLoaded().then(() => flushUpdates());
        });
    }

    if (msg.status === 'RUNNING') {
        scheduleTick();
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

function flushUpdates() {
    const container = document.getElementById('mcp-activity-content')
        || document.getElementById('mcp-activity-tab');
    const scrollParent = container ? (container.closest('.activity-list') || container) : null;
    const wasAtBottom = scrollParent
        ? scrollParent.scrollHeight - scrollParent.scrollTop - scrollParent.clientHeight < 50
        : true;

    if (pendingUpdates.size > 0) {
        const ids = new Set(pendingUpdates);
        pendingUpdates.clear();
        for (const opId of ids) {
            patchOperation(opId);
        }
    }
    if (pendingNewOps.size > 0) {
        const ids = [...pendingNewOps];
        pendingNewOps.clear();
        insertNewOperations(ids);
    }

    if (scrollToOpAfterFlush && container) {
        const targetEl = container.querySelector(`.activity-operation[data-op-id="${scrollToOpAfterFlush}"]`);
        scrollToOpAfterFlush = null;
        if (targetEl && scrollParent) {
            targetEl.scrollIntoView({ block: 'center' });
        }
    } else if (wasAtBottom && scrollParent) {
        scrollParent.scrollTop = scrollParent.scrollHeight;
    }
}

function patchOperation(opId) {
    const container = document.getElementById('mcp-activity-content')
        || document.getElementById('mcp-activity-tab');
    if (!container) return;
    const existingEl = container.querySelector(`.activity-operation[data-op-id="${opId}"]`);
    if (!existingEl) return;
    const op = operations.find(o => o.id === opId);
    if (!op) return;

    const esc = escapeHtml;
    const tmp = document.createElement('div');
    tmp.innerHTML = renderOperation(op, esc);
    const newEl = tmp.firstElementChild;
    if (visibilityObserver) visibilityObserver.unobserve(existingEl);
    existingEl.replaceWith(newEl);
    if (expandedOps.has(opId) && !userExpandedOps.has(opId)) {
        observeOperation(newEl);
    }
}

function insertNewOperations(opIds) {
    const container = document.getElementById('mcp-activity-content')
        || document.getElementById('mcp-activity-tab');
    if (!container || !container.querySelector('.activity-workspace-group')) {
        renderActivity();
        return;
    }

    const esc = escapeHtml;

    for (const opId of opIds) {
        const op = operations.find(o => o.id === opId);
        if (!op) continue;

        const wsKey = op.workspaceUri || 'global';
        const wsBody = getOrCreateWorkspaceBody(container, wsKey, esc);

        if (op.sessionId) {
            insertSessionOperation(wsBody, op, wsKey, esc);
        } else {
            wsBody.appendChild(createOpElement(op, esc));
        }

        updateWorkspaceCount(wsBody, wsKey);
    }
}

function getOrCreateWorkspaceBody(container, wsKey, esc) {
    for (const h of container.querySelectorAll('.activity-workspace-header')) {
        if (h.dataset.wsKey === wsKey) {
            return h.closest('.activity-workspace-group').querySelector('.activity-workspace-body');
        }
    }
    const label = wsKey === 'global' ? 'Global' : wsKey.split('/').pop().split('\\').pop();
    const expanded = !allFolded && !collapsedWorkspaces.has(wsKey);
    const html = `<div class="activity-workspace-group">` +
        `<div class="activity-workspace-header" data-action="toggleWorkspace" data-ws-key="${esc(wsKey)}">` +
        `<span class="activity-toggle">${expanded ? '&#9660;' : '&#9654;'}</span>` +
        `${esc(label)} <span class="activity-workspace-count">0</span>` +
        `</div>` +
        `<div class="activity-workspace-body" style="display: ${expanded ? 'block' : 'none'};"></div></div>`;
    const tmp = document.createElement('div');
    tmp.innerHTML = html;
    container.appendChild(tmp.firstElementChild);
    return container.lastElementChild.querySelector('.activity-workspace-body');
}

function insertSessionOperation(wsBody, op, wsKey, esc) {
    const sessionKey = `${wsKey}:${op.sessionId}`;
    for (const h of wsBody.querySelectorAll('.activity-session-header')) {
        if (h.dataset.sessionKey === sessionKey) {
            const sg = h.closest('.activity-session-group');
            const body = sg.querySelector('.activity-session-body');
            body.appendChild(createOpElement(op, esc));
            const sc = sg.querySelector('.activity-workspace-count');
            if (sc) sc.textContent = operations.filter(o => o.sessionId === op.sessionId).length;
            return;
        }
    }
    const item = { sessionId: op.sessionId, sessionName: op.sessionName || op.sessionId, ops: [op] };
    const sgHtml = renderSessionGroup(item, wsKey, esc);
    const tmp = document.createElement('div');
    tmp.innerHTML = sgHtml;
    wsBody.appendChild(tmp.firstElementChild);
}

function updateWorkspaceCount(wsBody, wsKey) {
    const wsGroup = wsBody.closest('.activity-workspace-group');
    const countEl = wsGroup.querySelector(':scope > .activity-workspace-header .activity-workspace-count');
    if (countEl) {
        countEl.textContent = operations.filter(o => (o.workspaceUri || 'global') === wsKey).length;
    }
}

function createOpElement(op, esc) {
    const tmp = document.createElement('div');
    tmp.innerHTML = renderOperation(op, esc);
    const el = tmp.firstElementChild;
    if (expandedOps.has(op.id) && !userExpandedOps.has(op.id)) {
        observeOperation(el);
    }
    return el;
}

export function renderActivity() {
    pendingUpdates.clear();
    pendingNewOps.clear();
    if (updateRAF) {
        cancelAnimationFrame(updateRAF);
        updateRAF = null;
    }

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

        const sessionGroups = {};
        const noSession = [];
        for (const op of ops) {
            if (op.sessionId) {
                if (!sessionGroups[op.sessionId]) {
                    sessionGroups[op.sessionId] = { name: op.sessionName || op.sessionId, ops: [] };
                }
                sessionGroups[op.sessionId].ops.push(op);
            } else {
                noSession.push(op);
            }
        }

        const renderItems = [];
        for (const [sid, group] of Object.entries(sessionGroups)) {
            const latestStart = Math.max(...group.ops.map(o => o.startTime));
            renderItems.push({ type: 'session', sessionId: sid, sessionName: group.name, ops: group.ops, sortTime: latestStart });
        }
        for (const op of noSession) {
            renderItems.push({ type: 'operation', op, sortTime: op.startTime });
        }
        renderItems.sort((a, b) => a.sortTime - b.sortTime);

        for (const item of renderItems) {
            if (item.type === 'session') {
                html += renderSessionGroup(item, ws, esc);
            } else {
                html += renderOperation(item.op, esc);
            }
        }

        html += `</div>`;
        html += `</div>`;
    }

    const scrollParent = container.closest('.activity-list') || container;
    const wasAtBottom = scrollParent.scrollHeight - scrollParent.scrollTop - scrollParent.clientHeight < 50;
    let anchorId = null;
    let anchorOffset = 0;
    if (!wasAtBottom && scrollParent.scrollTop > 0) {
        for (const opEl of container.querySelectorAll('.activity-operation[data-op-id]')) {
            const rect = opEl.getBoundingClientRect();
            const parentRect = scrollParent.getBoundingClientRect();
            if (rect.top >= parentRect.top - 10) {
                anchorId = opEl.dataset.opId;
                anchorOffset = rect.top - parentRect.top;
                break;
            }
        }
    }

    if (visibilityObserver) {
        visibilityObserver.disconnect();
        visibilityObserver = null;
    }
    container.innerHTML = html;

    if (wasAtBottom) {
        scrollParent.scrollTop = scrollParent.scrollHeight;
    } else if (anchorId) {
        const anchorEl = container.querySelector(`.activity-operation[data-op-id="${anchorId}"]`);
        if (anchorEl) {
            const parentRect = scrollParent.getBoundingClientRect();
            const drift = (anchorEl.getBoundingClientRect().top - parentRect.top) - anchorOffset;
            scrollParent.scrollTop += drift;
        }
    }

    for (const opEl of container.querySelectorAll('.activity-operation[data-op-id]')) {
        const opId = opEl.dataset.opId;
        if (opId && expandedOps.has(opId) && !userExpandedOps.has(opId)) {
            observeOperation(opEl);
        }
    }
}

function liveDuration(item) {
    if (item.status === 'RUNNING') {
        return Date.now() - item.startTime;
    }
    return item.durationMs;
}

function estimateTokens(text) {
    if (!text) return 0;
    const s = typeof text === 'string' ? text : JSON.stringify(text);
    return Math.ceil(s.length / 4);
}

function formatTokens(count) {
    if (count >= 1000) return (count / 1000).toFixed(1) + 'k';
    return String(count);
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

function renderSessionGroup(session, wsKey, esc) {
    const sessionKey = `${wsKey}:${session.sessionId}`;
    const sessionExpanded = allFolded ? false : !collapsedSessions.has(sessionKey);
    const count = session.ops.length;

    let html = `<div class="activity-session-group">`;
    html += `<div class="activity-session-header" data-action="toggleSession" data-session-key="${esc(sessionKey)}">`;
    html += `<span class="activity-toggle">${sessionExpanded ? '&#9660;' : '&#9654;'}</span>`;
    html += `<span class="activity-session-name">${esc(session.sessionName)}</span>`;
    html += ` <span class="activity-session-id">(${esc(session.sessionId)})</span>`;
    html += ` <span class="activity-workspace-count">${count}</span>`;
    html += `</div>`;
    html += `<div class="activity-session-body" style="display: ${sessionExpanded ? 'block' : 'none'};">`;

    for (const op of session.ops) {
        html += renderOperation(op, esc);
    }

    html += `</div>`;
    html += `</div>`;
    return html;
}

function renderOperation(op, esc) {
    const statusIcon = getStatusIcon(op.status);
    const duration = formatDuration(liveDuration(op));
    const hasEntries = op.entries && op.entries.length > 0;
    const desc = getToolDescription(op.name);
    const titleAttr = desc ? ` title="${esc(desc)}"` : '';
    const argsSummary = formatArgsSummary(op.arguments);

    const statusClass = op.status === 'RUNNING' ? ' running' : op.status === 'FAILED' ? ' failed' : ' completed';
    let html = `<div class="activity-operation${statusClass}" data-op-id="${op.id}">`;
    html += `<div class="activity-operation-header" data-action="toggleActivityOperation">`;
    const expanded = expandedOps.has(op.id);
    const hasBody = hasEntries || op.arguments || op.result || op.error;
    if (hasBody) {
        html += `<span class="activity-toggle">${expanded ? '&#9660;' : '&#9654;'}</span>`;
    } else {
        html += `<span class="activity-toggle-spacer"></span>`;
    }
    html += `<span class="activity-status-icon">${statusIcon}</span>`;
    if (op.actor === 'USER') {
        html += `<span class="activity-origin-badge origin-user" title="User">&#128100;</span>`;
    } else {
        html += `<span class="activity-origin-badge origin-agent" title="Agent">&#129302;</span>`;
    }
    html += `<span class="activity-operation-info"${titleAttr}>`;
    html += `<span class="activity-operation-name">${esc(op.name)}</span>`;
    if (argsSummary) {
        html += ` <span class="activity-operation-args">${esc(argsSummary)}</span>`;
    }
    html += `</span>`;
    html += `<span class="activity-time">${formatTime(op.startTime)}</span>`;
    html += `<span class="activity-duration">${duration}</span>`;
    const inputTokens = estimateTokens(op.arguments);
    const outputTokens = estimateTokens(op.result);
    const totalTokens = inputTokens + outputTokens;
    if (totalTokens > 0) {
        html += `<span class="activity-tokens" title="~${formatTokens(inputTokens)} in + ~${formatTokens(outputTokens)} out">~${formatTokens(totalTokens)} tok</span>`;
    }
    const isActiveReplay = Array.from(activeReplays.values()).includes(op.id);
    if (isActiveReplay && op.status === 'RUNNING') {
        html += `<span class="activity-replay replaying" data-action="cancelReplay" data-op-id="${op.id}" title="Cancel replay">&#9632;</span>`;
    } else if (op.status !== 'RUNNING' && op.arguments && !activeReplays.has(op.id)) {
        html += `<span class="activity-replay" data-action="replayOperation" data-op-id="${op.id}" title="Replay">&#8635;</span>`;
    }
    html += `</div>`;

    if (hasBody) {
        html += `<div class="activity-operation-body" style="display: ${expanded ? 'block' : 'none'};">`;
        if (op.arguments) {
            const argTokens = estimateTokens(op.arguments);
            html += renderSection(op.id, 'input', `Arguments <span class="activity-tokens-inline">~${formatTokens(argTokens)} tok</span>`, renderArgsForm(op, esc), esc);
        }
        if (hasEntries) {
            let entriesHtml = '';
            const maxDuration = findMaxDuration(op.entries);
            for (const entry of op.entries) {
                entriesHtml += renderEntry(entry, esc, 1, op.id, maxDuration);
            }
            html += renderSection(op.id, 'steps', 'Steps', entriesHtml, esc);
        }
        if (op.error) {
            const errorHtml = `<pre class="activity-output-content activity-error-content">${esc(op.error)}</pre>`;
            html += renderSection(op.id, 'error', 'Error', errorHtml, esc);
        }
        if (op.result) {
            const resTokens = estimateTokens(op.result);
            const outputHtml = `<pre class="activity-output-content">${esc(op.result)}</pre>`;
            html += renderSection(op.id, 'output', `Result <span class="activity-tokens-inline">~${formatTokens(resTokens)} tok</span>`, outputHtml, esc);
        }
        html += `</div>`;
    }
    html += `</div>`;
    return html;
}

function isSectionExpanded(opId, section) {
    if (allFolded) return false;
    const key = `${opId}:${section}`;
    const op = operations.find(o => o.id === opId);
    const finished = op && op.status !== 'RUNNING';
    let defaultExpanded;
    if (section === 'input') {
        defaultExpanded = false;
    } else if (section === 'steps') {
        defaultExpanded = !finished;
    } else {
        defaultExpanded = true;
    }
    return toggledSections.has(key) ? !defaultExpanded : defaultExpanded;
}

function renderSection(opId, section, label, content, esc) {
    const expanded = isSectionExpanded(opId, section);
    const key = `${opId}:${section}`;
    let html = `<div class="activity-section">`;
    html += `<div class="activity-section-header" data-action="toggleSection" data-section-key="${esc(key)}">`;
    html += `<span class="activity-toggle">${expanded ? '&#9660;' : '&#9654;'}</span>`;
    html += `<span class="activity-section-label">${label}</span>`;
    html += `</div>`;
    html += `<div class="activity-section-body" style="display: ${expanded ? 'block' : 'none'};">`;
    html += content;
    html += `</div>`;
    html += `</div>`;
    return html;
}

function renderArgsForm(op, esc) {
    const tool = mcpToolsMap ? mcpToolsMap[op.name] : null;
    const argDefs = tool ? tool.args : null;
    const args = op.arguments || {};

    let html = `<div class="activity-args-form" data-op-id="${op.id}">`;

    if (argDefs && argDefs.length > 0) {
        for (const argDef of argDefs) {
            const value = args[argDef.name];
            const displayValue = value !== undefined && value !== null
                ? (typeof value === 'object' ? JSON.stringify(value, null, 2) : String(value))
                : '';
            const requiredMark = argDef.required ? '<span class="activity-arg-required">*</span>' : '';
            const titleAttr = argDef.description ? ` title="${esc(argDef.description)}"` : '';

            html += `<div class="activity-arg-field"${titleAttr}>`;
            html += `<label class="activity-arg-label">${esc(argDef.name)}${requiredMark}</label>`;

            if (typeof value === 'object' && value !== null) {
                html += `<textarea class="activity-arg-input" data-arg-name="${esc(argDef.name)}" rows="3">${esc(displayValue)}</textarea>`;
            } else if (displayValue.length > 60) {
                html += `<textarea class="activity-arg-input" data-arg-name="${esc(argDef.name)}" rows="2">${esc(displayValue)}</textarea>`;
            } else {
                html += `<input class="activity-arg-input" data-arg-name="${esc(argDef.name)}" type="text" value="${esc(displayValue)}" />`;
            }
            html += `</div>`;
        }
    } else {
        for (const [key, value] of Object.entries(args)) {
            const displayValue = typeof value === 'object' ? JSON.stringify(value, null, 2) : String(value);
            html += `<div class="activity-arg-field">`;
            html += `<label class="activity-arg-label">${esc(key)}</label>`;
            if (typeof value === 'object' && value !== null) {
                html += `<textarea class="activity-arg-input" data-arg-name="${esc(key)}" rows="3">${esc(displayValue)}</textarea>`;
            } else {
                html += `<input class="activity-arg-input" data-arg-name="${esc(key)}" type="text" value="${esc(displayValue)}" />`;
            }
            html += `</div>`;
        }
    }
    html += `</div>`;
    return html;
}

function findMaxDuration(entries) {
    if (!entries || entries.length < 2) return -1;
    if (entries.some(e => e.status === 'RUNNING')) return -1;
    let max = -1;
    for (const e of entries) {
        const d = liveDuration(e);
        if (d > max) max = d;
    }
    return max;
}

function renderEntry(entry, esc, depth, opId, maxDuration) {
    const statusIcon = getStatusIcon(entry.status);
    const dur = liveDuration(entry);
    const duration = formatDuration(dur);
    const isSlowest = maxDuration > 0 && entry.status !== 'RUNNING' && dur === maxDuration;
    const durClass = isSlowest ? ' activity-duration-slowest' : '';
    const hasChildren = entry.children && entry.children.length > 0;

    if (depth === 1 && hasChildren) {
        const serverKey = opId + ':' + entry.name;
        const serverExpanded = allFolded ? false : !collapsedServers.has(serverKey);
        let html = `<div class="activity-server-group">`;
        html += `<div class="activity-server-entry" data-action="toggleServerEntry" data-server-key="${esc(serverKey)}">`;
        html += `<span class="activity-toggle">${serverExpanded ? '&#9660;' : '&#9654;'}</span>`;
        html += `<span class="activity-status-icon">${statusIcon}</span>`;
        html += `<span class="activity-server-name">${esc(entry.name)}</span>`;
        html += `<span class="activity-duration${durClass}">${duration}</span>`;
        html += `</div>`;
        html += `<div class="activity-entry-children" style="display: ${serverExpanded ? 'block' : 'none'};">`;
        for (const child of entry.children) {
            html += renderEntry(child, esc, depth + 1, opId, -1);
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
    html += `<span class="activity-duration${durClass}">${duration}</span>`;
    html += `</div>`;

    if (hasChildren) {
        for (const child of entry.children) {
            html += renderEntry(child, esc, depth + 1, opId, -1);
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

function formatTime(epochMs) {
    if (!epochMs) return '';
    const d = new Date(epochMs);
    const hh = String(d.getHours()).padStart(2, '0');
    const mm = String(d.getMinutes()).padStart(2, '0');
    const ss = String(d.getSeconds()).padStart(2, '0');
    return `${hh}:${mm}:${ss}`;
}

function toggleActivityOperation(el) {
    const opEl = el.closest('.activity-operation');
    if (!opEl) return;
    const body = opEl.querySelector('.activity-operation-body');
    if (!body) return;
    const isVisible = body.style.display !== 'none';
    body.style.display = isVisible ? 'none' : 'block';
    const toggle = opEl.querySelector('.activity-toggle');
    if (toggle) {
        toggle.innerHTML = isVisible ? '&#9654;' : '&#9660;';
    }
    const opId = opEl.dataset.opId;
    if (opId) {
        if (isVisible) {
            expandedOps.delete(opId);
            userExpandedOps.delete(opId);
        } else {
            expandedOps.add(opId);
            userExpandedOps.add(opId);
        }
    }
}

function toggleSection(el) {
    const key = el.dataset.sectionKey || el.closest('[data-section-key]')?.dataset.sectionKey;
    if (!key) return;
    const sectionEl = el.closest('.activity-section');
    if (!sectionEl) return;
    const body = sectionEl.querySelector('.activity-section-body');
    if (!body) return;
    const isVisible = body.style.display !== 'none';
    body.style.display = isVisible ? 'none' : 'block';
    const toggle = sectionEl.querySelector('.activity-section-header .activity-toggle');
    if (toggle) {
        toggle.innerHTML = isVisible ? '&#9654;' : '&#9660;';
    }
    allFolded = false;
    if (toggledSections.has(key)) {
        toggledSections.delete(key);
    } else {
        toggledSections.add(key);
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

function toggleSession(el) {
    const key = el.dataset.sessionKey || el.closest('[data-session-key]')?.dataset.sessionKey;
    if (!key) return;
    const group = el.closest('.activity-session-group');
    if (!group) return;
    const body = group.querySelector('.activity-session-body');
    if (!body) return;
    const isVisible = body.style.display !== 'none';
    body.style.display = isVisible ? 'none' : 'block';
    const toggle = el.querySelector('.activity-toggle') || el.closest('.activity-session-header')?.querySelector('.activity-toggle');
    if (toggle) {
        toggle.innerHTML = isVisible ? '&#9654;' : '&#9660;';
    }
    allFolded = false;
    if (isVisible) {
        collapsedSessions.add(key);
    } else {
        collapsedSessions.delete(key);
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
    userExpandedOps.clear();
    collapsedServers.clear();
    collapsedWorkspaces.clear();
    collapsedSessions.clear();
    toggledSections.clear();
    allFolded = true;
    renderActivity();
}

function clearActivity() {
    operations = [];
    expandedOps.clear();
    userExpandedOps.clear();
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

function gatherFormArgs(opId) {
    const form = document.querySelector(`.activity-args-form[data-op-id="${opId}"]`);
    if (!form) return null;
    const args = {};
    for (const input of form.querySelectorAll('.activity-arg-input')) {
        const name = input.dataset.argName;
        if (!name) continue;
        const val = input.value;
        try {
            args[name] = JSON.parse(val);
        } catch (e) {
            args[name] = val;
        }
    }
    return args;
}

async function replayOperation(el) {
    const opEl = el.closest('[data-op-id]');
    if (!opEl) return;
    const opId = opEl.dataset.opId;
    const op = operations.find(o => o.id === opId);
    if (!op || !op.arguments) return;
    if (activeReplays.has(opId)) return;

    const formArgs = gatherFormArgs(opId) || op.arguments;
    pendingReplayToolName = op.name;

    try {
        const resp = await fetch('/api/admin/activity/replay', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ toolName: op.name, arguments: formArgs })
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
    const replayOpId = opEl.dataset.opId;

    let originalOpId = null;
    for (const [origId, repId] of activeReplays) {
        if (repId === replayOpId) {
            originalOpId = origId;
            break;
        }
    }
    if (!originalOpId) return;

    try {
        await fetch(`/api/admin/activity/replay/${replayOpId}`, { method: 'DELETE' });
    } catch (e) {
        // ignore
    }
    activeReplays.delete(originalOpId);
    renderActivity();
}

registerActions('click', {
    toggleActivityOperation: (el) => toggleActivityOperation(el),
    toggleSection: (el) => toggleSection(el),
    toggleServerEntry: (el) => toggleServerEntry(el),
    toggleSession: (el) => toggleSession(el),
    toggleWorkspace: (el) => toggleWorkspace(el),
    foldAllActivity: () => foldAllActivity(),
    clearActivity: () => clearActivity(),
    replayOperation: (el) => { el.stopPropagation?.(); replayOperation(el); },
    cancelReplay: (el) => { el.stopPropagation?.(); cancelReplay(el); },
});

registerActions('change', {
    toggleActivity: (el) => toggleActivity(el),
});
