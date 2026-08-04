import { state } from './shared-state.js';
import { escapeHtml } from './trace-renderer.js';
import { registerActions } from './event-delegation.js';

const activeTasks = new Map();
const taskSteps = new Map();
const taskDetailExpanded = new Set();

let installProgressCallback = null;

export function setInstallProgressCallback(cb) {
    installProgressCallback = cb;
}

export function updateTask(task) {
    if (!task || !task.id) return;

    activeTasks.set(task.id, {
        ...task,
        lastUpdate: Date.now()
    });

    refreshProgressFooter();
    refreshProgressPanel();
}

export function removeTask(taskId) {
    activeTasks.delete(taskId);
    taskSteps.delete(taskId);
    taskDetailExpanded.delete(taskId);
    refreshProgressFooter();
    refreshProgressPanel();
}

export function clearAllTasks() {
    activeTasks.clear();
    taskSteps.clear();
    taskDetailExpanded.clear();
    refreshProgressFooter();
    refreshProgressPanel();
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
        const stepInfo = task.stepId ? ` [${getStepLabel(task.id, task.stepId)}]` : '';
        statusEl.textContent = `${task.title}${stepInfo} - ${Math.round(task.percent || 0)}%`;
        countEl.textContent = '1';
        iconEl.textContent = '⏵';
    } else {
        statusEl.textContent = `${count} tasks running`;
        countEl.textContent = String(count);
        iconEl.textContent = '⏵';
    }
}

function refreshProgressPanel() {
    const content = document.getElementById('progress-panel-content');

    if (activeTasks.size === 0) {
        content.innerHTML = '<div class="text-secondary" style="text-align: center; padding: 2rem;">No active tasks</div>';
        return;
    }

    const tasksHtml = Array.from(activeTasks.values())
        .sort((a, b) => b.lastUpdate - a.lastUpdate)
        .map(task => {
            const percent = Math.round(task.percent || 0);
            const stepDefs = taskSteps.get(task.id);
            const hasSteps = stepDefs && stepDefs.steps && stepDefs.steps.length > 0;

            let stepsHtml = '';
            let stepLabel = '';

            if (hasSteps) {
                const currentStepId = task.stepId;
                const currentStepIndex = stepDefs.steps.findIndex(s => s.id === currentStepId);
                stepLabel = currentStepId
                    ? `<div class="progress-step-name">${escapeHtml(getStepLabel(task.id, currentStepId))}</div>`
                    : '';

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

            return `
                <div class="progress-task-item">
                    <div class="progress-task-header">
                        <div class="progress-task-title">${escapeHtml(task.title)}</div>
                        <div style="display: flex; align-items: center; gap: 0.5rem;">
                            ${stepLabel}
                            <div class="progress-task-percent">${percent}%</div>
                            ${cancelBtn}
                        </div>
                    </div>
                    <div class="progress-task-bar">
                        <div class="progress-task-bar-fill" style="width: ${percent}%"></div>
                    </div>
                    ${task.message ? `<div class="progress-task-message">${escapeHtml(task.message)}</div>` : ''}
                    ${stepsHtml}
                </div>
            `;
        })
        .join('');

    content.innerHTML = tasksHtml;
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
    if (state.installOutputServerId === msg.serverId && installProgressCallback) {
        installProgressCallback(msg);
    }

    if (msg.status === 'completed' || msg.status === 'failed') {
        setTimeout(() => removeTask(msg.taskId), 2000);
    } else {
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
    try {
        const response = await fetch(`/api/admin/lsp/progress/${encodeURIComponent(taskId)}/cancel`, {
            method: 'POST'
        });
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
});
