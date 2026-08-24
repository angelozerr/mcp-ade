import { escapeHtml } from './trace-renderer.js';
import { renderBadge } from './shared-ui.js';

export function renderProgressBadge(label, statusClass, progressPercent, message) {
    if (progressPercent == null) {
        return renderBadge(statusClass.replace('status-', ''), label);
    }

    const percent = Math.round(progressPercent);

    return `
        <div class="progress-badge ${statusClass}">
            <div class="progress-badge-header">
                <span class="progress-badge-label">${label}</span>
                <span class="progress-badge-percent">${percent}%</span>
            </div>
            <div class="progress-bar-container">
                <div class="progress-bar-fill" style="width: ${percent}%"></div>
            </div>
            ${message ? `<div class="progress-badge-message">${escapeHtml(message)}</div>` : ''}
        </div>
    `;
}

export function updateProgressBadge(elementId, label, statusClass, progressPercent, message) {
    const element = document.getElementById(elementId);
    if (!element) return;
    element.outerHTML = renderProgressBadge(label, statusClass, progressPercent, message);
}
