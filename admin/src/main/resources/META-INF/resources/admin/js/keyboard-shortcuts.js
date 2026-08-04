let fullTextSelected = false;
let handlers = null;
let keydownListener = null;
let mousedownListener = null;

function handleKeyDown(e) {
    if (!handlers) return;

    if (e.key === 'Escape') {
        if (handlers.onCloseSearch) {
            handlers.onCloseSearch();
        }
        return;
    }

    const activeElement = document.activeElement;
    const isInputFocused = activeElement.tagName === 'INPUT' || activeElement.tagName === 'TEXTAREA';

    const isInEditor = activeElement.classList?.contains('monaco-editor') ||
                      activeElement.closest('.monaco-editor') ||
                      activeElement.classList?.contains('CodeMirror') ||
                      activeElement.closest('.CodeMirror');

    if (isInputFocused || isInEditor) return;

    const consoleInfo = handlers.getActiveConsole();
    if (!consoleInfo) return;

    if (e.ctrlKey && e.key === 'a') {
        e.preventDefault();
        selectAllConsoleContent(consoleInfo);
    }

    if (e.ctrlKey && e.key === 'c' && fullTextSelected) {
        e.preventDefault();
        copyFullConsoleContent(consoleInfo);
    }

    if (e.ctrlKey && e.key === 'f') {
        e.preventDefault();
        if (handlers.onSearch) {
            handlers.onSearch(consoleInfo);
        }
    }
}

function handleMouseDown() {
    fullTextSelected = false;
}

function selectAllConsoleContent(consoleInfo) {
    const container = document.getElementById(consoleInfo.containerId);
    if (!container) return;

    const range = document.createRange();
    range.selectNodeContents(container);
    const selection = window.getSelection();
    selection.removeAllRanges();
    selection.addRange(range);

    fullTextSelected = true;
    container.focus();
}

function copyFullConsoleContent(consoleInfo) {
    const traces = consoleInfo.data || [];

    let fullText = '';
    traces.forEach(trace => {
        const content = trace.content || trace;
        fullText += content + '\n\n';
    });

    navigator.clipboard.writeText(fullText).then(() => {
        fullTextSelected = false;
        console.log(`[${consoleInfo.type.toUpperCase()}] Copied ${traces.length} traces to clipboard`);
    }).catch(err => {
        console.error(`[${consoleInfo.type.toUpperCase()}] Failed to copy:`, err);
        fullTextSelected = false;
    });
}

export const KeyboardShortcuts = {
    register(h) {
        if (handlers) {
            console.warn('[KeyboardShortcuts] Already registered - unregistering first');
            this.unregister();
        }

        handlers = h;
        keydownListener = handleKeyDown;
        mousedownListener = handleMouseDown;

        document.addEventListener('keydown', keydownListener);
        document.addEventListener('mousedown', mousedownListener);
    },

    unregister() {
        if (keydownListener) {
            document.removeEventListener('keydown', keydownListener);
        }
        if (mousedownListener) {
            document.removeEventListener('mousedown', mousedownListener);
        }
        handlers = null;
        keydownListener = null;
        mousedownListener = null;
        fullTextSelected = false;
    }
};
