import { state } from './shared-state.js';

export function showModal(title, message, buttons) {
    const modal = document.getElementById('modal-overlay');
    const modalTitle = document.getElementById('modal-title');
    const modalMessage = document.getElementById('modal-message');
    const modalButtons = document.getElementById('modal-buttons');

    modalTitle.textContent = title;
    modalMessage.textContent = message;

    modalButtons.innerHTML = buttons.map(btn =>
        `<button class="modal-button ${btn.type || 'secondary'}" data-action="${btn.action}">${btn.label}</button>`
    ).join('');

    modalButtons.querySelectorAll('[data-action]').forEach(el => {
        el.addEventListener('click', () => {
            if (el.dataset.action === 'modal-cancel') {
                hideModal();
                if (state.modalResolve) state.modalResolve(false);
            } else if (el.dataset.action === 'modal-confirm') {
                hideModal();
                if (state.modalResolve) state.modalResolve(true);
            } else if (el.dataset.action === 'modal-ok') {
                hideModal();
            }
        });
    });

    modal.classList.add('visible');
}

export function hideModal() {
    document.getElementById('modal-overlay').classList.remove('visible');
}

export async function confirmAction(title, message, confirmLabel, isDanger = false) {
    return new Promise((resolve) => {
        state.modalResolve = resolve;
        showModal(title, message, [
            { label: 'Cancel', type: 'secondary', action: 'modal-cancel' },
            { label: confirmLabel, type: isDanger ? 'danger' : 'primary', action: 'modal-confirm' }
        ]);
    });
}

export function showAlert(title, message) {
    showModal(title, message, [
        { label: 'OK', type: 'primary', action: 'modal-ok' }
    ]);
}

export function showConfirmModal(title, message, onConfirm) {
    const titleEl = document.getElementById('confirm-modal-title');
    const messageEl = document.getElementById('confirm-modal-message');
    const modalEl = document.getElementById('confirm-modal');

    if (!titleEl || !messageEl || !modalEl) {
        console.error('Confirm modal elements not found!');
        return;
    }

    titleEl.textContent = title;
    messageEl.innerHTML = message;
    modalEl.classList.add('visible');

    const confirmBtn = document.getElementById('modal-confirm-btn');
    confirmBtn.onclick = () => {
        hideConfirmModal();
        onConfirm();
    };
}

export function hideConfirmModal() {
    document.getElementById('confirm-modal').classList.remove('visible');
}

export function initModalOverlay() {
    const modalOverlay = document.getElementById('modal-overlay');
    if (modalOverlay) {
        modalOverlay.addEventListener('click', (e) => {
            if (e.target.id === 'modal-overlay') {
                hideModal();
                if (state.modalResolve) {
                    state.modalResolve(false);
                }
            }
        });
    }
}
