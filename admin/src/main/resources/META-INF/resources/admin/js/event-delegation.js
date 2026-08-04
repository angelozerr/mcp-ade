const handlers = {
    click: {},
    change: {},
    input: {},
    mousedown: {},
    mouseup: {},
};

export function registerActions(eventType, actions) {
    Object.assign(handlers[eventType], actions);
}

export function initEventDelegation() {
    for (const eventType of ['click', 'change', 'input', 'mousedown', 'mouseup']) {
        document.addEventListener(eventType, (e) => {
            const el = e.target.closest('[data-action]');
            if (!el) return;
            const fn = handlers[eventType][el.dataset.action];
            if (!fn) return;
            if (el.hasAttribute('data-stop-propagation')) e.stopPropagation();
            fn(el, e);
        });
    }

    // mouseenter/mouseleave don't bubble — use mouseover/mouseout with contains check
    document.addEventListener('mouseover', (e) => {
        const el = e.target.closest('[data-hover-enter]');
        if (!el || el._hoverActive) return;
        el._hoverActive = true;
        const fn = handlers.click[el.dataset.hoverEnter];
        if (fn) fn(el, e);
    });

    document.addEventListener('mouseout', (e) => {
        const el = e.target.closest('[data-hover-enter]');
        if (!el) return;
        if (el.contains(e.relatedTarget)) return;
        el._hoverActive = false;
        const fn = handlers.click[el.dataset.hoverLeave];
        if (fn) fn(el, e);
    });
}
