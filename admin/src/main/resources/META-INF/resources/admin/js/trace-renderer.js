import { registerActions } from './event-delegation.js';

let searchMatches = [];
let currentMatchIndex = -1;
let currentSearchQuery = '';
let renderCallback = null;

function getTraceColorClass(trace) {
    if (trace.messageType === 'ERROR') return 'trace-type-error';
    if (trace.messageType === 'INFO') return 'trace-type-info';
    if (trace.messageType === 'UPDATE') return 'trace-type-update';
    return '';
}

export function renderTrace(trace, index, traceLevel, searchQuery) {
    const content = trace.content;
    const firstNewline = content.indexOf('\n');
    const colorClass = getTraceColorClass(trace);
    const lineClass = 'trace-line' + (colorClass ? ' ' + colorClass : '');

    if (firstNewline === -1) {
        return `
            <div class="${lineClass}">
                <div class="text-primary p-xs font-mono-sm">${highlightText(content, searchQuery)}</div>
            </div>
        `;
    }

    const headerLine = content.substring(0, firstNewline);
    const body = content.substring(firstNewline + 1).trim();

    const hasMatch = searchQuery && content.toLowerCase().includes(searchQuery.toLowerCase());

    if (traceLevel === 'messages') {
        return `
            <div class="${lineClass}">
                <div class="text-primary p-xs font-mono-sm">${highlightText(headerLine, searchQuery)}</div>
            </div>
        `;
    }

    if (!body) {
        return `
            <div class="${lineClass}">
                <div class="text-primary p-xs font-mono-sm">${highlightText(headerLine, searchQuery)}</div>
            </div>
        `;
    }

    const foldState = hasMatch ? 'expanded' : 'collapsed';
    const toggleIcon = hasMatch ? '▼' : '▶';
    const headerClass = hasMatch ? 'trace-header' : 'trace-header folded';
    const fullContent = headerLine + '\n' + body;

    return `
        <div class="${lineClass}" data-trace-tooltip="${index}" data-folded="${!hasMatch}">
            <div class="${headerClass} p-xs font-mono-sm" id="header-${index}"
                 data-trace-toggle="${index}">
                <span class="trace-toggle mr-sm" id="toggle-${index}">${toggleIcon}</span>
                <span class="trace-header-text text-primary">${highlightText(headerLine, searchQuery)}</span>
            </div>
            <div class="trace-body ${foldState} text-primary font-mono-sm text-pre-wrap" id="body-${index}">${highlightText(body, searchQuery)}</div>
            <div class="trace-tooltip" id="tooltip-${index}">${escapeHtml(fullContent)}</div>
        </div>
    `;
}

export function toggleTrace(index) {
    const body = document.getElementById('body-' + index);
    const toggle = document.getElementById('toggle-' + index);
    const header = document.getElementById('header-' + index);

    if (!header || !body || !toggle) return;

    const traceLine = header.parentElement;

    if (body.classList.contains('expanded')) {
        body.classList.remove('expanded');
        body.classList.add('collapsed');
        toggle.textContent = '▶';
        header.classList.add('folded');
        if (traceLine) traceLine.dataset.folded = 'true';

        if (!traceLine.querySelector('.trace-tooltip')) {
            const headerText = header.querySelector('.trace-header-text').textContent;
            const bodyText = body.textContent;
            const fullContent = headerText + '\n' + bodyText;

            const tooltip = document.createElement('div');
            tooltip.className = 'trace-tooltip';
            tooltip.id = 'tooltip-' + index;
            tooltip.textContent = fullContent;
            traceLine.appendChild(tooltip);
        }
    } else {
        body.classList.remove('collapsed');
        body.classList.add('expanded');
        toggle.textContent = '▼';
        header.classList.remove('folded');
        if (traceLine) traceLine.dataset.folded = 'false';
        hideTooltip(index);
    }
}

export function toggleAllTraces(containerId, expand) {
    const container = document.getElementById(containerId);
    if (!container) return;

    container.querySelectorAll('.trace-body').forEach(body => {
        const id = body.id.replace('body-', '');
        const toggle = document.getElementById('toggle-' + id);
        const header = document.getElementById('header-' + id);

        if (!toggle || !header) return;

        if (expand) {
            body.classList.remove('collapsed');
            body.classList.add('expanded');
            toggle.textContent = '▼';
            header.classList.remove('folded');
        } else {
            body.classList.remove('expanded');
            body.classList.add('collapsed');
            toggle.textContent = '▶';
            header.classList.add('folded');
        }
    });
}

let activeTooltipIndex = null;

export function showTooltip(event, index, isFolded) {
    if (!isFolded) return;

    const body = document.getElementById('body-' + index);
    if (!body || !body.classList.contains('collapsed')) return;

    const tooltip = document.getElementById('tooltip-' + index);
    if (!tooltip) return;

    hideActiveTooltip();
    activeTooltipIndex = index;
    tooltip.style.display = 'block';
    tooltip.style.left = event.clientX + 'px';
    tooltip.style.top = (event.clientY + 20) + 'px';
}

export function hideTooltip(index) {
    const tooltip = document.getElementById('tooltip-' + index);
    if (tooltip) {
        tooltip.style.display = 'none';
    }
    if (activeTooltipIndex === index) {
        activeTooltipIndex = null;
    }
}

function hideActiveTooltip() {
    if (activeTooltipIndex != null) {
        hideTooltip(activeTooltipIndex);
    }
}

export function highlightText(text, query) {
    if (!query) return escapeHtml(text);

    const escaped = escapeHtml(text);
    const regex = new RegExp(escapeRegex(query), 'gi');
    return escaped.replace(regex, match => `<span class="highlight">${match}</span>`);
}

export function escapeHtml(text) {
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
}

export function escapeRegex(string) {
    return string.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

// ========== Search Functions ==========

export function openSearch() {
    const searchBox = document.getElementById('search-box');
    const searchInput = document.getElementById('search-input');
    if (searchBox && searchInput) {
        const consoleContainer = document.getElementById('console-container');
        const consoleArea = document.getElementById('console-area');

        if (consoleContainer && consoleArea) {
            const rect = consoleArea.getBoundingClientRect();
            searchBox.style.top = (rect.top + 10) + 'px';
        } else {
            searchBox.style.top = '50px';
        }

        searchBox.classList.add('visible');
        searchInput.focus();
        searchInput.select();
    }
}

export function closeSearch() {
    const searchBox = document.getElementById('search-box');
    const searchInput = document.getElementById('search-input');
    if (searchBox && searchInput) {
        searchBox.classList.remove('visible');
        searchInput.value = '';
        clearHighlights();

        if (renderCallback) {
            renderCallback('');
        }
    }
}

export function performSearch(query, cb) {
    currentSearchQuery = query;
    searchMatches = [];
    currentMatchIndex = -1;

    if (cb) {
        cb(query);
    }

    document.querySelectorAll('.highlight').forEach(el => {
        searchMatches.push({ element: el });
    });

    if (searchMatches.length > 0) {
        currentMatchIndex = 0;
        highlightCurrentMatch();
    }

    updateSearchCount();
}

export function searchNext() {
    if (searchMatches.length === 0) return;
    currentMatchIndex = (currentMatchIndex + 1) % searchMatches.length;
    highlightCurrentMatch();
    updateSearchCount();
}

export function searchPrev() {
    if (searchMatches.length === 0) return;
    currentMatchIndex = (currentMatchIndex - 1 + searchMatches.length) % searchMatches.length;
    highlightCurrentMatch();
    updateSearchCount();
}

export function clearHighlights() {
    currentSearchQuery = '';
    searchMatches = [];
    currentMatchIndex = -1;
    updateSearchCount();
}

function highlightCurrentMatch() {
    document.querySelectorAll('.highlight.current').forEach(el => {
        el.classList.remove('current');
    });

    if (searchMatches[currentMatchIndex]) {
        const match = searchMatches[currentMatchIndex].element;
        match.classList.add('current');

        match.scrollIntoView({
            behavior: 'smooth',
            block: 'center'
        });
    }
}

function updateSearchCount() {
    const searchCount = document.getElementById('search-count');
    if (searchCount) {
        if (searchMatches.length === 0) {
            searchCount.textContent = '0/0';
        } else {
            searchCount.textContent = `${currentMatchIndex + 1}/${searchMatches.length}`;
        }
    }
}

export function getCurrentSearchQuery() {
    return currentSearchQuery;
}

export function initSearchListeners(callback) {
    renderCallback = callback;

    const searchInput = document.getElementById('search-input');
    if (searchInput) {
        searchInput.addEventListener('input', (e) => {
            performSearch(e.target.value, renderCallback);
        });

        searchInput.addEventListener('keydown', (e) => {
            if (e.key === 'Enter') {
                e.preventDefault();
                if (e.shiftKey) {
                    searchPrev();
                } else {
                    searchNext();
                }
            }
        });
    }
}

// ========== Trace Controls ==========

export function renderTraceControls(id, level, changeAction, buttons) {
    let innerHtml = `<label class="text-primary font-md">
            Trace Level:
            <select id="${id}-level" data-action="${changeAction}" class="select-field ml-sm">
                <option value="off" ${level === 'off' ? 'selected' : ''}>Off</option>
                <option value="messages" ${level === 'messages' ? 'selected' : ''}>Messages</option>
                <option value="verbose" ${level === 'verbose' ? 'selected' : ''}>Verbose</option>
            </select>
        </label>`;
    if (buttons) {
        innerHtml += `<button data-action="${buttons.foldAction}" id="${id}-fold-button" ${level !== 'verbose' ? 'disabled' : ''}>Unfold All</button>`
            + `<button data-action="${buttons.clearAction}" id="${id}-clear-button" ${level === 'off' ? 'disabled' : ''}>Clear</button>`;
    }
    if (buttons && buttons.wrapperId) {
        return `<span id="${buttons.wrapperId}" style="display: ${buttons.wrapperDisplay || 'contents'}">${innerHtml}</span>`;
    }
    return innerHtml;
}

export function updateTraceControls(id, level) {
    const select = document.getElementById(`${id}-level`);
    if (select) select.value = level;
    const foldButton = document.getElementById(`${id}-fold-button`);
    if (foldButton) foldButton.disabled = level !== 'verbose';
    const clearButton = document.getElementById(`${id}-clear-button`);
    if (clearButton) clearButton.disabled = level === 'off';
}

// ========== Scroll & State ==========

export function isScrolledToBottom(container, threshold) {
    if (!container) return true;
    if (typeof threshold !== 'number') threshold = 30;
    return container.scrollHeight - container.scrollTop - container.clientHeight <= threshold;
}

export function saveExpandedState(container) {
    const ids = new Set();
    if (!container) return ids;
    container.querySelectorAll('.trace-body.expanded').forEach(el => {
        if (el.id) ids.add(el.id);
    });
    return ids;
}

export function restoreExpandedState(container, expandedIds) {
    if (!container || !expandedIds || expandedIds.size === 0) return;
    expandedIds.forEach(bodyId => {
        const body = document.getElementById(bodyId);
        if (!body) return;
        body.classList.remove('collapsed');
        body.classList.add('expanded');
        const idx = bodyId.replace('body-', '');
        const toggle = document.getElementById('toggle-' + idx);
        if (toggle) toggle.textContent = '▼';
        const header = document.getElementById('header-' + idx);
        if (header) header.classList.remove('folded');
    });
}

// ========== Render Traces in Container ==========

export function renderTracesInContainer(containerId, traces, traceLevel, searchQuery, emptyMessage) {
    hideActiveTooltip();
    const container = document.getElementById(containerId);
    if (!container) return;

    if (traceLevel === 'off') {
        container.innerHTML = `<div class="text-secondary text-center p-2xl">Traces are disabled (level: off)</div>`;
        return;
    }

    if (traces.length === 0) {
        container.innerHTML = `<div class="text-secondary text-center p-2xl">${emptyMessage || 'No traces yet.'}</div>`;
        return;
    }

    const wasAtBottom = isScrolledToBottom(container);
    const expandedIds = saveExpandedState(container);

    container.innerHTML = traces.map((trace, index) =>
        renderTrace(trace, index, traceLevel, searchQuery)
    ).join('');

    restoreExpandedState(container, expandedIds);

    if (wasAtBottom) {
        container.scrollTop = container.scrollHeight;
    }
}

// ========== Container Event Delegation ==========

export function initTraceContainer(containerId) {
    const container = document.getElementById(containerId);
    if (!container) return;
    if (container._traceContainerInitialized) return;
    container._traceContainerInitialized = true;

    let mouseDownData = null;

    container.addEventListener('mousedown', (e) => {
        const header = e.target.closest('[data-trace-toggle]');
        if (header) {
            mouseDownData = { index: parseInt(header.dataset.traceToggle), x: e.clientX, y: e.clientY };
        }
    });

    container.addEventListener('mouseup', (e) => {
        if (!mouseDownData) return;
        const header = e.target.closest('[data-trace-toggle]');
        if (header && parseInt(header.dataset.traceToggle) === mouseDownData.index) {
            const dx = Math.abs(e.clientX - mouseDownData.x);
            const dy = Math.abs(e.clientY - mouseDownData.y);
            if (dx < 5 && dy < 5) {
                toggleTrace(mouseDownData.index);
            }
        }
        mouseDownData = null;
    });

    container.addEventListener('mouseover', (e) => {
        const el = e.target.closest('[data-trace-tooltip]');
        if (!el || el._tooltipActive) return;
        el._tooltipActive = true;
        const index = parseInt(el.dataset.traceTooltip);
        const isFolded = el.dataset.folded === 'true';
        el._tooltipTimer = setTimeout(() => {
            if (el._tooltipActive) {
                showTooltip(e, index, isFolded);
            }
        }, 800);
    });

    container.addEventListener('mouseout', (e) => {
        const el = e.target.closest('[data-trace-tooltip]');
        if (!el) return;
        if (el.contains(e.relatedTarget)) return;
        el._tooltipActive = false;
        clearTimeout(el._tooltipTimer);
        hideTooltip(parseInt(el.dataset.traceTooltip));
    });
}

// Register search actions for static HTML buttons
registerActions('click', {
    closeSearch: () => closeSearch(),
    searchNext: () => searchNext(),
    searchPrev: () => searchPrev(),
});
