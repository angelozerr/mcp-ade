/**
 * Admin UI - Settings rendering utilities
 *
 * Reusable functions for rendering settings with source info (inherited/overridden),
 * reset buttons, and toggle controls. Used by workspace settings, server settings, etc.
 */

import { showToast } from './toast.js';

const FILTER_THRESHOLD = 5;

/**
 * Render a settings panel with toolbar (title + count + optional filter) and items list.
 *
 * @param {object} opts
 * @param {string} opts.title - Section title (e.g. "Actions", "Settings")
 * @param {string[]} opts.itemsHtml - Array of rendered item HTML strings
 * @param {string} [opts.filterAction] - data-action for the filter input (omit to disable filter)
 * @param {string} [opts.listId] - id for the items container (needed for filtering)
 * @param {string} [opts.countId] - id for the count span (needed for filtering)
 */
export function renderSettingsPanel(opts) {
    const { title, itemsHtml, filterAction, listId, countId } = opts;
    const count = itemsHtml.length;

    const filterHtml = filterAction
        ? `<div class="settings-panel-filter"><input type="text" class="input-field mcp-tools-search" placeholder="Filter ${title.toLowerCase()}..." data-action="${filterAction}" />
           <span class="mcp-tools-count" ${countId ? `id="${countId}"` : ''}>${count} ${title.toLowerCase()}</span></div>`
        : '';

    return `
        <div class="mcp-tool-group settings-panel-group">
            <div class="mcp-tool-group-header" data-action="toggleMcpToolGroup">
                <span class="mcp-tool-group-toggle">▼</span>
                <span class="mcp-tool-group-name">${title}</span>
                <span class="mcp-tool-group-count">${count}</span>
            </div>
            <div class="mcp-tool-group-body" ${listId ? `id="${listId}"` : ''}>
                ${filterHtml}
                ${itemsHtml.join('')}
            </div>
        </div>
    `;
}

/**
 * Render the source hint for a setting (inherited from application / overridden at workspace level).
 */
export function renderSettingSourceHint(source) {
    const isOverridden = source === 'WORKSPACE';
    return isOverridden
        ? '<span class="setting-source-hint setting-source-overridden">overridden at workspace level</span>'
        : '<span class="setting-source-hint">inherited from application</span>';
}

/**
 * Render a reset button if the setting is overridden at workspace level.
 */
export function renderSettingResetButton(source, resetAction, dataAttrs) {
    if (source !== 'WORKSPACE' || !resetAction) return '';
    const attrs = Object.entries(dataAttrs).map(([k, v]) => `data-${k}="${v}"`).join(' ');
    return `<button class="editor-btn setting-reset-btn" data-action="${resetAction}" ${attrs} title="Reset to application value">reset</button>`;
}

/**
 * Render the source row (hint + optional reset button) for a setting.
 */
export function renderSettingSourceRow(source, resetAction, dataAttrs) {
    const hint = renderSettingSourceHint(source);
    const resetBtn = renderSettingResetButton(source, resetAction, dataAttrs);
    return `<div class="setting-source-row">${hint} ${resetBtn}</div>`;
}

/**
 * Render a toggle setting item with source info.
 */
export function renderToggleSetting(opts) {
    const { label, description, value, source, toggleAction, resetAction, dataAttrs, statusHtml } = opts;
    const attrs = Object.entries(dataAttrs).map(([k, v]) => `data-${k}="${v}"`).join(' ');
    const sourceRow = source ? renderSettingSourceRow(source, resetAction, dataAttrs) : '';

    return `
        <div class="setting-item">
            <div class="setting-item-info">
                <div class="setting-item-label">${label}</div>
                <div class="setting-item-description">${description}</div>
                ${sourceRow}
            </div>
            <div class="setting-item-control">
                ${statusHtml || ''}
                <label class="toggle-switch" data-stop-propagation>
                    <input type="checkbox" ${value ? 'checked' : ''} data-action="${toggleAction}" ${attrs}>
                    <span class="toggle-slider"></span>
                </label>
            </div>
        </div>
    `;
}

/**
 * Render a server setting item based on its type (enum, boolean, string) with source info.
 */
export function renderServerSetting(setting, changeAction, resetAction, dataAttrs) {
    const attrs = Object.entries(dataAttrs).map(([k, v]) => `data-${k}="${v}"`).join(' ');
    const sourceRow = setting.source ? renderSettingSourceRow(setting.source, resetAction, { ...dataAttrs, key: setting.key }) : '';
    const requiredBadge = setting.required ? '<span class="setting-required-badge">required</span>' : '';

    let controlHTML = '';
    const currentValue = setting.currentValue || setting.defaultValue || '';

    if (setting.enumValues && setting.enumValues.length > 0) {
        const options = setting.enumValues.map((v, i) => {
            const label = (setting.enumDescriptions && setting.enumDescriptions[i]) ? setting.enumDescriptions[i] : v;
            const selected = v === currentValue ? 'selected' : '';
            return `<option value="${v}" ${selected}>${label}</option>`;
        }).join('');
        controlHTML = `<select class="select-field settings-input" data-action="${changeAction}" data-setting-key="${setting.key}" ${attrs}>
                           ${options}
                       </select>`;
    } else if (setting.type === 'boolean') {
        controlHTML = renderToggleControl(currentValue === 'true' || currentValue === true, changeAction, setting.key, attrs);
    } else {
        controlHTML = `<input type="text" class="input-field settings-input" value="${currentValue}"
                              data-action="${changeAction}" data-setting-key="${setting.key}" ${attrs}>`;
    }

    return `
        <div class="setting-item${setting.required ? ' setting-item-required' : ''}">
            <div class="setting-item-info">
                <div class="setting-item-label">${setting.title || setting.key} ${requiredBadge}</div>
                ${setting.description ? `<div class="setting-item-description">${setting.description}</div>` : ''}
                ${sourceRow}
            </div>
            <div class="setting-item-control">
                ${controlHTML}
            </div>
        </div>
    `;
}

function renderToggleControl(checked, action, settingKey, attrs) {
    return `<label class="toggle-switch" data-stop-propagation>
                <input type="checkbox" ${checked ? 'checked' : ''} data-action="${action}" data-setting-key="${settingKey}" ${attrs}>
                <span class="toggle-slider"></span>
            </label>`;
}

/**
 * Render an action item (button-triggered, like Build or Refresh).
 */
export function renderActionItem(opts) {
    const { label, description, buttonLabel, buttonAction, dataAttrs, buttonClass } = opts;
    const attrs = Object.entries(dataAttrs || {}).map(([k, v]) => `data-${k}="${v}"`).join(' ');

    return `
        <div class="setting-item">
            <div class="setting-item-info">
                <div class="setting-item-label">${label}</div>
                <div class="setting-item-description">${description}</div>
            </div>
            <div class="setting-item-control">
                <button class="editor-btn ${buttonClass || ''}" data-action="${buttonAction}" ${attrs} style="white-space:nowrap">${buttonLabel}</button>
            </div>
        </div>
    `;
}

/**
 * Filter setting items in a list container by text content.
 */
export function filterSettingItems(listId, countId, query) {
    const list = document.getElementById(listId);
    const countEl = document.getElementById(countId);
    if (!list) return;

    const items = list.querySelectorAll('.setting-item');
    const lowerQuery = (query || '').toLowerCase();
    let visible = 0;

    items.forEach(item => {
        const text = item.textContent.toLowerCase();
        const matches = !lowerQuery || text.includes(lowerQuery);
        item.style.display = matches ? '' : 'none';
        if (matches) visible++;
    });

    if (countEl) {
        const word = (n) => n === 1 ? 'setting' : 'settings';
        countEl.textContent = lowerQuery
            ? `${visible} / ${items.length} ${word(items.length)}`
            : `${items.length} ${word(items.length)}`;
    }
}

/**
 * Reset a workspace setting via the API and return the resolved value.
 */
export async function resetWorkspaceSetting(uri, key) {
    try {
        const response = await fetch(`/api/admin/workspaces/${encodeURIComponent(uri)}/settings/${encodeURIComponent(key)}`, {
            method: 'DELETE'
        });
        if (response.ok) {
            const result = await response.json();
            console.log(`Setting '${key}' reset:`, result);
            showToast('Setting reset');
            return result;
        }
    } catch (error) {
        console.error(`Failed to reset setting '${key}':`, error);
    }
    return null;
}

/**
 * Set a workspace setting via the API.
 */
export async function setWorkspaceSetting(uri, key, value) {
    try {
        const response = await fetch(`/api/admin/workspaces/${encodeURIComponent(uri)}/settings/${encodeURIComponent(key)}`, {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ value })
        });
        if (response.ok) {
            const result = await response.json();
            console.log(`Setting '${key}' set:`, result);
            showToast('Settings saved');
            return result;
        }
    } catch (error) {
        console.error(`Failed to set setting '${key}':`, error);
    }
    return null;
}
