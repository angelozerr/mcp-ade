let toastContainer = null;
let hideTimer = null;

function ensureContainer() {
    if (!toastContainer) {
        toastContainer = document.getElementById('toast-container');
    }
    return toastContainer;
}

export function showToast(message = 'Settings saved') {
    const container = ensureContainer();
    if (!container) return;

    if (hideTimer) {
        clearTimeout(hideTimer);
        hideTimer = null;
    }

    container.textContent = message;
    container.classList.remove('toast-hide');
    container.classList.add('toast-show');

    hideTimer = setTimeout(() => {
        container.classList.remove('toast-show');
        container.classList.add('toast-hide');
        hideTimer = null;
    }, 2000);
}
