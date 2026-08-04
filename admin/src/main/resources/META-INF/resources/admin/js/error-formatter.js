import { registerActions } from './event-delegation.js';

export function formatErrorWithFolding(title, errorData) {
    const message = errorData.message || 'Unknown error';
    const type = errorData.type || '';
    const stackTrace = errorData.stackTrace || '';

    const traceId = 'error-' + Date.now();

    const body = (message === type || !message || message === 'null') ? stackTrace.trim() : (message + '\n' + stackTrace).trim();

    return `
        <div class="mb-lg font-mono">
            <div class="trace-header folded p-xs cursor-pointer d-flex align-center user-select-none" data-action="toggleErrorTrace" data-trace-id="${traceId}">
                <span class="trace-toggle text-error mr-xs">&#x25B6;</span>
                <span class="trace-header-text text-error font-bold">${title} - ${type}</span>
            </div>
            <div id="${traceId}" class="trace-body collapsed text-error font-md text-pre-wrap word-break-all" style="padding-left: 1.5rem;">${body}</div>
        </div>
    `;
}

export function toggleErrorTrace(traceId) {
    const body = document.getElementById(traceId);
    if (!body) return;

    const header = body.previousElementSibling;
    const toggle = header ? header.querySelector('.trace-toggle') : null;

    if (body.classList.contains('collapsed')) {
        body.classList.remove('collapsed');
        body.classList.add('expanded');
        if (header) header.classList.remove('folded');
        if (toggle) toggle.textContent = '▼';
    } else {
        body.classList.add('collapsed');
        body.classList.remove('expanded');
        if (header) header.classList.add('folded');
        if (toggle) toggle.textContent = '▶';
    }
}

registerActions('click', {
    toggleErrorTrace: (el) => toggleErrorTrace(el.dataset.traceId)
});
