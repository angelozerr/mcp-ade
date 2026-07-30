/**
 * Shared trace rendering, folding, and search utilities for LSP, MCP, and DAP traces.
 */

const TraceRenderer = (function() {

    // Search state
    let searchMatches = [];
    let currentMatchIndex = -1;
    let currentSearchQuery = '';
    let renderCallback = null; // Callback to re-render traces

    /**
     * Render a single trace with folding support.
     *
     * @param {Object} trace - Trace object with content field
     * @param {number} index - Trace index for ID generation
     * @param {string} traceLevel - 'messages' or 'verbose'
     * @param {string} searchQuery - Current search query for highlighting
     * @returns {string} HTML string
     */
    function renderTrace(trace, index, traceLevel, searchQuery) {
        const content = trace.content;
        const firstNewline = content.indexOf('\n');

        if (firstNewline === -1) {
            // Single line trace
            return `
                <div class="trace-line">
                    <div class="text-primary" style="padding: 0.25rem; font-family: 'Consolas', 'Monaco', monospace; font-size: 0.85rem;">${highlightText(content, searchQuery)}</div>
                </div>
            `;
        }

        const headerLine = content.substring(0, firstNewline);
        const body = content.substring(firstNewline + 1).trim();

        // Check if trace has search match
        const hasMatch = searchQuery && content.toLowerCase().includes(searchQuery.toLowerCase());

        // Messages mode: show only header line, no folding
        if (traceLevel === 'messages') {
            return `
                <div class="trace-line">
                    <div class="text-primary" style="padding: 0.25rem; font-family: 'Consolas', 'Monaco', monospace; font-size: 0.85rem;">${highlightText(headerLine, searchQuery)}</div>
                </div>
            `;
        }

        // No body: just header
        if (!body) {
            return `
                <div class="trace-line">
                    <div class="text-primary" style="padding: 0.25rem; font-family: 'Consolas', 'Monaco', monospace; font-size: 0.85rem;">${highlightText(headerLine, searchQuery)}</div>
                </div>
            `;
        }

        // Verbose mode with folding
        const foldState = hasMatch ? 'expanded' : 'collapsed';
        const toggleIcon = hasMatch ? '▼' : '▶';
        const headerClass = hasMatch ? 'trace-header' : 'trace-header folded';
        const fullContent = headerLine + '\n' + body;

        return `
            <div class="trace-line" onmouseenter="showTooltip(event, ${index}, ${!hasMatch})" onmouseleave="hideTooltip(${index})">
                <div class="${headerClass}" id="header-${index}"
                     onmousedown="onHeaderMouseDown(${index})"
                     onmouseup="onHeaderMouseUp(${index})"
                     style="padding: 0.25rem; font-family: 'Consolas', 'Monaco', monospace; font-size: 0.85rem;">
                    <span class="trace-toggle" id="toggle-${index}" style="margin-right: 0.5rem;">${toggleIcon}</span>
                    <span class="trace-header-text text-primary">${highlightText(headerLine, searchQuery)}</span>
                </div>
                <div class="trace-body ${foldState} text-primary" id="body-${index}" style="font-family: 'Consolas', 'Monaco', monospace; font-size: 0.85rem; white-space: pre-wrap;">${highlightText(body, searchQuery)}</div>
                <div class="trace-tooltip" id="tooltip-${index}">${escapeHtml(fullContent)}</div>
            </div>
        `;
    }

    /**
     * Toggle a trace between folded and expanded.
     */
    function toggleTrace(index) {
        const body = document.getElementById('body-' + index);
        const toggle = document.getElementById('toggle-' + index);
        const header = document.getElementById('header-' + index);

        if (!header || !body || !toggle) {
            console.warn('[TraceRenderer] Could not find elements for trace', index);
            return;
        }

        const traceLine = header.parentElement;

        if (body.classList.contains('expanded')) {
            body.classList.remove('expanded');
            body.classList.add('collapsed');
            toggle.textContent = '▶';
            header.classList.add('folded');

            // Add tooltip for folded traces
            if (!traceLine.querySelector('.trace-tooltip')) {
                const headerText = header.querySelector('.trace-header-text').textContent;
                const bodyText = body.textContent;
                const fullContent = headerText + '\n' + bodyText;

                const tooltip = document.createElement('div');
                tooltip.className = 'trace-tooltip';
                tooltip.id = 'tooltip-' + index;
                tooltip.textContent = fullContent;
                traceLine.appendChild(tooltip);

                // Add event listeners
                traceLine.onmouseenter = (e) => showTooltip(e, index, true);
                traceLine.onmouseleave = () => hideTooltip(index);
            }
        } else {
            body.classList.remove('collapsed');
            body.classList.add('expanded');
            toggle.textContent = '▼';
            header.classList.remove('folded');
        }
    }

    /**
     * Toggle all traces in a container.
     */
    function toggleAllTraces(containerId, expand) {
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

    /**
     * Show tooltip on hover for folded traces.
     */
    function showTooltip(event, index, isFolded) {
        if (!isFolded) return;

        const body = document.getElementById('body-' + index);
        if (!body || !body.classList.contains('collapsed')) return;

        const tooltip = document.getElementById('tooltip-' + index);
        if (!tooltip) return;

        tooltip.style.display = 'block';
        tooltip.style.left = event.clientX + 'px';
        tooltip.style.top = (event.clientY + 20) + 'px';
    }

    /**
     * Hide tooltip.
     */
    function hideTooltip(index) {
        const tooltip = document.getElementById('tooltip-' + index);
        if (tooltip) {
            tooltip.style.display = 'none';
        }
    }

    /**
     * Highlight text with search query.
     */
    function highlightText(text, query) {
        if (!query) return escapeHtml(text);

        const escaped = escapeHtml(text);
        const regex = new RegExp(escapeRegex(query), 'gi');
        return escaped.replace(regex, match => `<span class="highlight">${match}</span>`);
    }

    /**
     * Escape HTML entities.
     */
    function escapeHtml(text) {
        const div = document.createElement('div');
        div.textContent = text;
        return div.innerHTML;
    }

    /**
     * Escape regex special characters.
     */
    function escapeRegex(string) {
        return string.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    }

    // ========== Search Functions ==========

    /**
     * Open the search box.
     */
    function openSearch() {
        const searchBox = document.getElementById('search-box');
        const searchInput = document.getElementById('search-input');
        if (searchBox && searchInput) {
            // Calculate position based on the actual trace container
            // Find the active console/trace container
            const consoleContainer = document.getElementById('console-container');
            const consoleArea = document.getElementById('console-area');

            if (consoleContainer && consoleArea) {
                // Get the bounding rect of console area to find where content starts
                const rect = consoleArea.getBoundingClientRect();
                // Position popup 10px below the top of console area
                searchBox.style.top = (rect.top + 10) + 'px';
            } else {
                // Fallback to default
                searchBox.style.top = '50px';
            }

            searchBox.classList.add('visible');
            searchInput.focus();
            searchInput.select();
        }
    }

    /**
     * Close the search box and clear highlights.
     * The render callback will be called to re-render without highlights.
     */
    function closeSearch() {
        const searchBox = document.getElementById('search-box');
        const searchInput = document.getElementById('search-input');
        if (searchBox && searchInput) {
            searchBox.classList.remove('visible');
            searchInput.value = '';
            clearHighlights();

            // Trigger re-render to remove highlights
            if (renderCallback) {
                renderCallback('');
            }
        }
    }

    /**
     * Perform search across traces.
     * @param {string} query - Search query
     * @param {Function} renderCallback - Function to re-render traces with highlights
     */
    function performSearch(query, renderCallback) {
        currentSearchQuery = query;
        searchMatches = [];
        currentMatchIndex = -1;

        // Re-render traces with highlighting
        if (renderCallback) {
            renderCallback(query);
        }

        // Collect all highlights for navigation
        document.querySelectorAll('.highlight').forEach(el => {
            searchMatches.push({ element: el });
        });

        if (searchMatches.length > 0) {
            currentMatchIndex = 0;
            highlightCurrentMatch();
        }

        updateSearchCount();
    }

    /**
     * Go to next search match.
     */
    function searchNext() {
        if (searchMatches.length === 0) return;
        currentMatchIndex = (currentMatchIndex + 1) % searchMatches.length;
        highlightCurrentMatch();
        updateSearchCount();
    }

    /**
     * Go to previous search match.
     */
    function searchPrev() {
        if (searchMatches.length === 0) return;
        currentMatchIndex = (currentMatchIndex - 1 + searchMatches.length) % searchMatches.length;
        highlightCurrentMatch();
        updateSearchCount();
    }

    /**
     * Clear all search highlights.
     */
    function clearHighlights() {
        currentSearchQuery = '';
        searchMatches = [];
        currentMatchIndex = -1;
        updateSearchCount();
    }

    /**
     * Highlight the current match and scroll to it.
     */
    function highlightCurrentMatch() {
        // Remove all current highlights
        document.querySelectorAll('.highlight.current').forEach(el => {
            el.classList.remove('current');
        });

        // Highlight current match
        if (searchMatches[currentMatchIndex]) {
            const match = searchMatches[currentMatchIndex].element;
            match.classList.add('current');

            // Scroll into view
            match.scrollIntoView({
                behavior: 'smooth',
                block: 'center'
            });
        }
    }

    /**
     * Update search count display.
     */
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

    /**
     * Get current search query.
     */
    function getCurrentSearchQuery() {
        return currentSearchQuery;
    }

    /**
     * Initialize search box event listeners.
     * Should be called once on page load.
     */
    function initSearchListeners(callback) {
        // Store the callback for use in closeSearch
        renderCallback = callback;

        const searchInput = document.getElementById('search-input');
        if (searchInput) {
            // Input event for live search
            searchInput.addEventListener('input', (e) => {
                performSearch(e.target.value, renderCallback);
            });

            // Enter key for next/prev
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

    /**
     * Render trace controls HTML (trace level combo + optional fold/clear buttons).
     *
     * @param {string} id - Unique prefix for element IDs (e.g., 'trace', 'dap-trace', 'mcp-trace')
     * @param {string} level - Current trace level ('off', 'messages', 'verbose')
     * @param {string} onchange - JS expression for combo onchange (e.g., "changeTraceLevel(this.value)")
     * @param {object} [buttons] - Optional fold/clear button config
     * @param {string} buttons.onFold - JS expression for fold button onclick
     * @param {string} buttons.onClear - JS expression for clear button onclick
     * @returns {string} HTML string
     */
    function renderTraceControls(id, level, onchange, buttons) {
        let html = `<label class="text-primary" style="font-size: 0.85rem;">
                Trace Level:
                <select id="${id}-level" onchange="${onchange}" class="select-field" style="margin-left: 0.5rem;">
                    <option value="off" ${level === 'off' ? 'selected' : ''}>Off</option>
                    <option value="messages" ${level === 'messages' ? 'selected' : ''}>Messages</option>
                    <option value="verbose" ${level === 'verbose' ? 'selected' : ''}>Verbose</option>
                </select>
            </label>`;
        if (buttons) {
            const buttonsHtml = `<button onclick="${buttons.onFold}" id="${id}-fold-button" ${level !== 'verbose' ? 'disabled' : ''}>Unfold All</button>`
                + `<button onclick="${buttons.onClear}" id="${id}-clear-button" ${level === 'off' ? 'disabled' : ''}>Clear</button>`;
            if (buttons.wrapperId) {
                html += `<span id="${buttons.wrapperId}" style="display: ${buttons.wrapperDisplay || 'contents'}">${buttonsHtml}</span>`;
            } else {
                html += buttonsHtml;
            }
        }
        return html;
    }

    /**
     * Update trace controls state (combo value + button disabled).
     *
     * @param {string} id - Same prefix used in renderTraceControls
     * @param {string} level - New trace level
     */
    function updateTraceControls(id, level) {
        const select = document.getElementById(`${id}-level`);
        if (select) select.value = level;
        const foldButton = document.getElementById(`${id}-fold-button`);
        if (foldButton) foldButton.disabled = level !== 'verbose';
        const clearButton = document.getElementById(`${id}-clear-button`);
        if (clearButton) clearButton.disabled = level === 'off';
    }

    /**
     * Check if a scrollable container is at (or near) the bottom.
     */
    function isScrolledToBottom(container, threshold) {
        if (!container) return true;
        if (typeof threshold !== 'number') threshold = 30;
        return container.scrollHeight - container.scrollTop - container.clientHeight <= threshold;
    }

    /**
     * Save the set of expanded trace body element IDs in a container.
     */
    function saveExpandedState(container) {
        const ids = new Set();
        if (!container) return ids;
        container.querySelectorAll('.trace-body.expanded').forEach(el => {
            if (el.id) ids.add(el.id);
        });
        return ids;
    }

    /**
     * Restore expanded state from a saved set of IDs.
     */
    function restoreExpandedState(container, expandedIds) {
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

    // Public API
    return {
        // Rendering
        renderTrace,
        toggleTrace,
        toggleAllTraces,
        showTooltip,
        hideTooltip,
        highlightText,
        escapeHtml,
        escapeRegex,

        // Scroll & state preservation
        isScrolledToBottom,
        saveExpandedState,
        restoreExpandedState,

        // Trace controls
        renderTraceControls,
        updateTraceControls,

        // Search
        openSearch,
        closeSearch,
        performSearch,
        searchNext,
        searchPrev,
        clearHighlights,
        getCurrentSearchQuery,
        initSearchListeners
    };
})();

// Expose globally for onclick handlers and other modules
window.toggleTrace = TraceRenderer.toggleTrace;
window.showTooltip = TraceRenderer.showTooltip;
window.hideTooltip = TraceRenderer.hideTooltip;
window.highlightText = TraceRenderer.highlightText;
window.escapeHtml = TraceRenderer.escapeHtml;
window.closeSearch = TraceRenderer.closeSearch;
window.searchNext = TraceRenderer.searchNext;
window.searchPrev = TraceRenderer.searchPrev;
window.clearHighlights = TraceRenderer.clearHighlights;
