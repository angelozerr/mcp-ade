import { state, getServerName } from './shared-state.js';
import { escapeHtml } from './trace-renderer.js';
import { registerActions } from './event-delegation.js';

const activeTasks = new Map();
const taskSteps = new Map();
const taskDetailExpanded = new Set();

let installProgressCallback = null;
let taskCompletedCallback = null;
let taskStartedCallback = null;
let installTaskRestoredCallback = null;
let installBadgeUpdateCallback = null;
let refreshScheduled = false;

export function setInstallProgressCallback(cb) {
    installProgressCallback = cb;
}

export function setTaskCompletedCallback(cb) {
    taskCompletedCallback = cb;
}

export function setTaskStartedCallback(cb) {
    taskStartedCallback = cb;
}

export function setInstallTaskRestoredCallback(cb) {
    installTaskRestoredCallback = cb;
}

export function setInstallBadgeUpdateCallback(cb) {
    installBadgeUpdateCallback = cb;
}

function scheduleRefresh() {
    if (refreshScheduled) return;
    refreshScheduled = true;
    requestAnimationFrame(() => {
        refreshScheduled = false;
        refreshProgressFooter();
        refreshProgressPanel();
    });
}

export function updateTask(task) {
    if (!task || !task.id) return;

    const existing = activeTasks.get(task.id);
    activeTasks.set(task.id, {
        ...task,
        createdAt: existing?.createdAt || Date.now(),
        lastUpdate: Date.now()
    });

    scheduleRefresh();
}

export function removeTask(taskId) {
    activeTasks.delete(taskId);
    taskSteps.delete(taskId);
    taskDetailExpanded.delete(taskId);
    scheduleRefresh();
}

export function clearAllTasks() {
    activeTasks.clear();
    taskSteps.clear();
    taskDetailExpanded.clear();
    scheduleRefresh();
}

function getTaskDisplayName(task) {
    if (task.title) {
        return task.title;
    }
    if (task.serverId) {
        return getServerName(task.serverId);
    }
    return task.id;
}

function refreshProgressFooter() {
    const statusEl = document.getElementById('progress-status');
    const countEl = document.getElementById('progress-count');
    const iconEl = document.getElementById('progress-icon');

    const count = activeTasks.size;

    if (count === 0) {
        statusEl.textContent = 'No tasks running';
        countEl.textContent = '0';
        iconEl.textContent = '⏸';
    } else if (count === 1) {
        const task = Array.from(activeTasks.values())[0];
        const name = getTaskDisplayName(task);
        const stepInfo = task.stepId ? ` [${getStepLabel(task.id, task.stepId)}]` : '';
        statusEl.textContent = `${name}${stepInfo} - ${Math.round(task.percent || 0)}%`;
        countEl.textContent = '1';
        iconEl.textContent = '⏵';
    } else {
        statusEl.textContent = `${count} tasks running`;
        countEl.textContent = String(count);
        iconEl.textContent = '⏵';
    }
}

function renderTaskContent(task) {
    const percent = Math.round(task.percent || 0);
    const name = getTaskDisplayName(task);
    const expanded = taskDetailExpanded.has(task.id);
    const stepDefs = taskSteps.get(task.id);
    const hasSteps = stepDefs && stepDefs.steps && stepDefs.steps.length > 0;

    let stepsHtml = '';
    if (hasSteps && expanded) {
        const currentStepId = task.stepId;
        const currentStepIndex = stepDefs.steps.findIndex(s => s.id === currentStepId);

        const stepsListHtml = stepDefs.steps.map((step, idx) => {
            let status, stepPercent;
            if (currentStepIndex < 0) {
                status = 'pending';
                stepPercent = 0;
            } else if (idx < currentStepIndex) {
                status = 'completed';
                stepPercent = 100;
            } else if (idx === currentStepIndex) {
                status = 'active';
                stepPercent = task.stepProgress != null ? Math.round(task.stepProgress * 100) : 0;
            } else {
                status = 'pending';
                stepPercent = 0;
            }

            const icon = status === 'completed' ? '✓'
                : status === 'active' ? '▸'
                : '○';
            const iconClass = `step-icon step-icon-${status}`;

            return `
                <div class="progress-step-row ${status}">
                    <span class="${iconClass}">${icon}</span>
                    <span class="progress-step-row-label">${escapeHtml(step.title || step.id)}</span>
                    <span class="progress-step-row-percent">${status !== 'pending' ? stepPercent + '%' : ''}</span>
                    <div class="progress-step-row-bar">
                        <div class="progress-step-row-bar-fill ${status}" style="width: ${stepPercent}%"></div>
                    </div>
                </div>
            `;
        }).join('');

        stepsHtml = `<div class="progress-steps-list">${stepsListHtml}</div>`;
    }

    const cancellable = hasSteps && stepDefs.cancellable;
    const cancelBtn = cancellable
        ? `<button class="progress-task-cancel" data-action="cancelProgressTask" data-task-id="${task.id}" title="Cancel this task">Cancel</button>`
        : '';

    const stepInfo = hasSteps && task.stepId
        ? `<div class="progress-step-name">${escapeHtml(getStepLabel(task.id, task.stepId))}</div>`
        : '';

    const expandToggle = hasSteps
        ? `<span class="progress-task-toggle" data-action="toggleTaskDetail" data-task-id="${task.id}">${expanded ? '▼' : '▶'}</span>`
        : '';

    return `
        <div class="progress-task-header">
            <div class="progress-task-title">${expandToggle}${escapeHtml(name)}</div>
            <div class="d-flex align-center gap-sm">
                <div class="progress-task-percent">${percent}%</div>
                ${cancelBtn}
            </div>
        </div>
        <div class="progress-task-bar">
            <div class="progress-task-bar-fill" style="width: ${percent}%"></div>
        </div>
        ${stepInfo}
        ${!stepInfo && task.message ? `<div class="progress-task-message">${escapeHtml(task.message)}</div>` : ''}
        ${stepsHtml}
    `;
}

function getTaskFingerprint(task) {
    const stepDefs = taskSteps.get(task.id);
    const stepCount = stepDefs?.steps?.length || 0;
    const cancellable = stepDefs?.cancellable || false;
    const expanded = taskDetailExpanded.has(task.id);
    return `${task.title}|${task.stepId || ''}|${stepCount}|${cancellable}|${task.status || ''}|${expanded}`;
}

function updateTaskInPlace(el, task) {
    const percent = Math.round(task.percent || 0);

    const percentEl = el.querySelector('.progress-task-percent');
    if (percentEl) percentEl.textContent = `${percent}%`;

    const barFill = el.querySelector(':scope > .progress-task-bar > .progress-task-bar-fill');
    if (barFill) barFill.style.width = `${percent}%`;

    const hasStepInfo = task.stepId && taskSteps.has(task.id);
    const msgEl = el.querySelector(':scope > .progress-task-message');
    if (task.message && !hasStepInfo) {
        if (msgEl) {
            msgEl.textContent = task.message;
        } else {
            const newMsg = document.createElement('div');
            newMsg.className = 'progress-task-message';
            newMsg.textContent = task.message;
            const bar = el.querySelector(':scope > .progress-task-bar');
            if (bar) bar.insertAdjacentElement('afterend', newMsg);
        }
    } else if (msgEl) {
        msgEl.remove();
    }

    if (taskDetailExpanded.has(task.id)) {
        const stepDefs = taskSteps.get(task.id);
        if (stepDefs?.steps) {
            const currentStepIndex = stepDefs.steps.findIndex(s => s.id === task.stepId);
            const stepRows = el.querySelectorAll('.progress-step-row');

            stepRows.forEach((row, idx) => {
                let status, stepPercent;
                if (currentStepIndex < 0) {
                    status = 'pending';
                    stepPercent = 0;
                } else if (idx < currentStepIndex) {
                    status = 'completed';
                    stepPercent = 100;
                } else if (idx === currentStepIndex) {
                    status = 'active';
                    stepPercent = task.stepProgress != null ? Math.round(task.stepProgress * 100) : 0;
                } else {
                    status = 'pending';
                    stepPercent = 0;
                }

                row.className = `progress-step-row ${status}`;

                const iconEl = row.querySelector('.step-icon');
                if (iconEl) {
                    iconEl.textContent = status === 'completed' ? '✓' : status === 'active' ? '▸' : '○';
                    iconEl.className = `step-icon step-icon-${status}`;
                }

                const pctEl = row.querySelector('.progress-step-row-percent');
                if (pctEl) pctEl.textContent = status !== 'pending' ? stepPercent + '%' : '';

                const fill = row.querySelector('.progress-step-row-bar-fill');
                if (fill) {
                    fill.style.width = `${stepPercent}%`;
                    fill.className = `progress-step-row-bar-fill ${status}`;
                }
            });
        }
    }

    const stepNameEl = el.querySelector('.progress-step-name');
    if (task.stepId && stepNameEl) {
        stepNameEl.innerHTML = escapeHtml(getStepLabel(task.id, task.stepId));
    }
}

function refreshProgressPanel() {
    const content = document.getElementById('progress-panel-content');

    if (activeTasks.size === 0) {
        content.innerHTML = '<div class="text-secondary text-center p-2xl">No active tasks</div>';
        return;
    }

    // Sort by creation time for stable order
    const sortedTasks = Array.from(activeTasks.values())
        .sort((a, b) => (a.createdAt || 0) - (b.createdAt || 0));

    const currentTaskIds = new Set(sortedTasks.map(t => t.id));

    for (const child of [...content.children]) {
        const tid = child.dataset?.taskId;
        if (!tid || !currentTaskIds.has(tid)) {
            child.remove();
        }
    }

    for (const task of sortedTasks) {
        let el = null;
        for (const child of content.children) {
            if (child.dataset?.taskId === task.id) {
                el = child;
                break;
            }
        }

        const newFingerprint = getTaskFingerprint(task);

        if (el) {
            if (el.dataset.fingerprint !== newFingerprint) {
                el.innerHTML = renderTaskContent(task);
                el.dataset.fingerprint = newFingerprint;
            } else {
                updateTaskInPlace(el, task);
            }
        } else {
            el = document.createElement('div');
            el.className = 'progress-task-item';
            el.dataset.taskId = task.id;
            el.dataset.fingerprint = newFingerprint;
            el.innerHTML = renderTaskContent(task);
            content.appendChild(el);
        }
    }

    // Ensure DOM order matches sorted order
    for (let i = 0; i < sortedTasks.length; i++) {
        const expected = sortedTasks[i].id;
        const actual = content.children[i]?.dataset?.taskId;
        if (actual !== expected) {
            for (const child of content.children) {
                if (child.dataset?.taskId === expected) {
                    content.insertBefore(child, content.children[i]);
                    break;
                }
            }
        }
    }
}

function toggleTaskDetail(taskId) {
    if (taskDetailExpanded.has(taskId)) {
        taskDetailExpanded.delete(taskId);
    } else {
        taskDetailExpanded.add(taskId);
    }
    scheduleRefresh();
}

export function toggleProgressPanel() {
    const panel = document.getElementById('progress-panel');
    panel.classList.toggle('visible');
}

export function handleProgressInit(msg) {
    if (msg.taskId && msg.steps) {
        taskSteps.set(msg.taskId, {
            steps: msg.steps,
            title: msg.title,
            serverId: msg.serverId,
            cancellable: msg.cancellable || false
        });
    }
}

function getStepLabel(taskId, stepId) {
    const stepDefs = taskSteps.get(taskId);
    if (stepDefs && stepDefs.steps) {
        const total = stepDefs.steps.length;
        const index = stepDefs.steps.findIndex(s => s.id === stepId);
        if (index >= 0) {
            const title = stepDefs.steps[index].title || stepId;
            return `${title} (${index + 1}/${total})`;
        }
    }
    return stepId;
}

export function handleProgressUpdate(msg) {
    // Restore install state from progress messages (e.g., after page refresh)
    if (msg.taskId && msg.taskId.startsWith('install-') && msg.serverId &&
        msg.status !== 'completed' && msg.status !== 'failed' &&
        !state.installingServers.has(msg.serverId)) {
        state.installingServers.add(msg.serverId);
        state.installStatus[msg.serverId] = 'installing';
        if (installTaskRestoredCallback) {
            installTaskRestoredCallback(msg.serverId);
        }
    }

    if (state.installOutputServerId === msg.serverId && installProgressCallback) {
        installProgressCallback(msg);
    }

    if (msg.taskId && msg.taskId.startsWith('install-') && msg.serverId) {
        state.installProgress[msg.serverId] = (msg.progress || 0) * 100;
        if (installBadgeUpdateCallback) {
            installBadgeUpdateCallback(msg.serverId);
        }
    }

    if (msg.status === 'completed' || msg.status === 'failed') {
        if (msg.serverId && state.installStatus[msg.serverId] === 'installing') {
            state.installStatus[msg.serverId] = msg.status;
            delete state.installProgress[msg.serverId];
        }
        updateTask({
            id: msg.taskId,
            serverId: msg.serverId,
            title: msg.title,
            percent: msg.status === 'completed' ? 100 : (msg.progress || 0) * 100,
            message: msg.status === 'completed' ? 'Done' : msg.message,
            status: msg.status
        });
        if (taskCompletedCallback) {
            taskCompletedCallback(msg.taskId, msg.status);
        }
        setTimeout(() => removeTask(msg.taskId), 2000);
    } else {
        if (msg.status === 'running' && taskStartedCallback) {
            taskStartedCallback(msg.taskId, msg.serverId);
        }
        updateTask({
            id: msg.taskId,
            serverId: msg.serverId,
            title: msg.title,
            percent: (msg.progress || 0) * 100,
            message: msg.message,
            status: msg.status,
            stepId: msg.stepId || null,
            stepProgress: msg.stepProgress != null ? msg.stepProgress : null
        });
    }
}

export async function cancelProgressTask(taskId) {
    const serverId = taskId.replace(/^(install|start|restart)-/, '');
    let apiPath;
    if (state.runtimeConfigs?.[serverId]) {
        apiPath = `/api/admin/runtimes/progress/${encodeURIComponent(taskId)}/cancel`;
    } else {
        const apiType = state.bspConfigs?.[serverId] ? 'bsp' : state.dapConfigs?.[serverId] ? 'dap' : 'lsp';
        apiPath = `/api/admin/${apiType}/progress/${encodeURIComponent(taskId)}/cancel`;
    }
    try {
        const response = await fetch(apiPath, { method: 'POST' });
        if (!response.ok) {
            const error = await response.json();
            console.error('Failed to cancel task:', error);
        }
    } catch (e) {
        console.error('Failed to cancel task:', e);
    }
}

registerActions('click', {
    cancelProgressTask: (el) => cancelProgressTask(el.dataset.taskId),
    toggleProgressPanel: () => toggleProgressPanel(),
    toggleTaskDetail: (el) => toggleTaskDetail(el.dataset.taskId),
});
