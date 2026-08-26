import { state, updateSearchBoxVisibility, ensureLanguageConfigs, ensureLanguageConfigDetail } from './shared-state.js';
import { renderLoadingPlaceholder, renderServerLink, selectListItem } from './shared-ui.js';
import { registerActions } from './event-delegation.js';

let selectedLanguage = null;
let languagesData = [];
function getLanguageDisplayName(lang) {
    if (lang.aliases && lang.aliases.length > 0) {
        return lang.aliases[0];
    }
    return lang.id;
}

function countServers(lang) {
    if (!lang.servers) return 0;
    let count = 0;
    for (const type in lang.servers) {
        if (Array.isArray(lang.servers[type])) {
            count += lang.servers[type].length;
        }
    }
    return count;
}

function renderLanguageItem(lang) {
    const isActive = selectedLanguage === lang.id ? 'active' : '';
    const displayName = getLanguageDisplayName(lang);
    const serverCount = lang.serverCount ?? countServers(lang);
    const sourceIcon = lang.source === 'global' ? '🌐' : '📦';
    const sourceLabel = lang.source === 'global' ? 'global' : 'server';
    const extensionsPreview = lang.extensions && lang.extensions.length > 0
        ? lang.extensions.slice(0, 3).join(', ') + (lang.extensions.length > 3 ? ', ...' : '')
        : '';

    return `
        <div class="server-item ${isActive}" data-action="showLanguageDetails" data-language-id="${lang.id}">
            <div class="server-name d-flex align-center justify-between">
                <span>
                    <span class="server-source-icon" title="${sourceLabel}">${sourceIcon}</span>
                    ${displayName}
                </span>
                ${serverCount > 0 ? `<span class="badge badge-info">${serverCount} server${serverCount !== 1 ? 's' : ''}</span>` : ''}
            </div>
            <div class="server-id">${lang.id}${extensionsPreview ? ` · ${extensionsPreview}` : ''}</div>
        </div>
    `;
}

export async function loadAllLanguages(languageIdToSelect) {
    try {
        const container = document.getElementById('languages-list');
        if (!state.languageConfigs) {
            if (container) container.innerHTML = renderLoadingPlaceholder();
        }

        await ensureLanguageConfigs();
        languagesData = state.languageConfigs || [];
        languagesData.sort((a, b) => getLanguageDisplayName(a).localeCompare(getLanguageDisplayName(b)));

        if (!container) return;

        if (languagesData.length === 0) {
            container.innerHTML = '<div class="servers-placeholder">No languages registered</div>';
            return;
        }

        container.innerHTML = languagesData.map(lang => renderLanguageItem(lang)).join('');

        let langToShow;
        if (languageIdToSelect && languagesData.find(l => l.id === languageIdToSelect)) {
            langToShow = languageIdToSelect;
        } else if (selectedLanguage && languagesData.find(l => l.id === selectedLanguage)) {
            langToShow = selectedLanguage;
        } else {
            langToShow = languagesData[0].id;
        }
        showLanguageDetails(langToShow, true);
    } catch (error) {
        console.error('Failed to load languages:', error);
    }
}

async function showLanguageDetails(languageId, scroll) {
    const previousLanguage = selectedLanguage;
    selectedLanguage = languageId;

    updateSearchBoxVisibility(false);

    selectListItem(document.getElementById('languages-list'),
        '.server-item[data-language-id', previousLanguage, languageId, scroll);

    const contentArea = document.querySelector('.content-area');
    const consoleColumn = document.querySelector('.console-container');
    consoleColumn.style.display = 'flex';
    contentArea.style.gridTemplateColumns = '400px 1fr';
    consoleColumn.style.gridColumn = '2';

    const lang = languagesData.find(l => l.id === languageId);
    if (!lang) return;

    const displayName = getLanguageDisplayName(lang);
    const sourceIcon = lang.source === 'global' ? '🌐' : '📦';

    document.getElementById('console-area').innerHTML = `
        <div class="console-header">
            <div class="console-title">
                <span class="server-source-icon">${sourceIcon}</span>
                ${displayName}
            </div>
            <div class="console-controls"></div>
        </div>
        <div class="details-panel text-primary detail-content">
            ${buildLanguageSummaryHTML(lang)}
            <div id="language-detail-section">
                ${lang._detailLoaded ? buildLanguageDetailHTML(lang) : renderLoadingPlaceholder()}
            </div>
        </div>
    `;

    if (!lang._detailLoaded) {
        await ensureLanguageConfigDetail(languageId);
        if (selectedLanguage !== languageId) return;
        const detailSection = document.getElementById('language-detail-section');
        if (detailSection) {
            detailSection.innerHTML = buildLanguageDetailHTML(lang);
        }
    }
}

function buildLanguageSummaryHTML(lang) {
    const sourceLabel = lang.source === 'global'
        ? '<span class="text-success">Global</span> — defined in languages.json'
        : '<span class="text-warning">Server-declared</span> — referenced by server documentSelector only';

    let aliasesHTML = '';
    if (lang.aliases && lang.aliases.length > 0) {
        aliasesHTML = `
            <div class="detail-row">
                <span class="detail-label">Aliases:</span>
                <span class="detail-value">${lang.aliases.map(a => `<code>${a}</code>`).join(', ')}</span>
            </div>
        `;
    }

    let extensionsHTML = '';
    if (lang.extensions && lang.extensions.length > 0) {
        extensionsHTML = `
            <div class="detail-row">
                <span class="detail-label">Extensions:</span>
                <span class="detail-value">${lang.extensions.map(e => `<code>${e}</code>`).join(' ')}</span>
            </div>
        `;
    }

    return `
        <h3 class="text-success mt-0">Language Information</h3>

        <div class="detail-row">
            <span class="detail-label">Language ID:</span>
            <span class="detail-value"><code>${lang.id}</code></span>
        </div>

        <div class="detail-row">
            <span class="detail-label">Source:</span>
            <span class="detail-value">${sourceLabel}</span>
        </div>

        ${aliasesHTML}
        ${extensionsHTML}
    `;
}

function buildLanguageDetailHTML(lang) {
    let filenamesHTML = '';
    if (lang.filenames && lang.filenames.length > 0) {
        filenamesHTML = `
            <div class="detail-row">
                <span class="detail-label">Filenames:</span>
                <span class="detail-value">${lang.filenames.map(f => `<code>${f}</code>`).join(' ')}</span>
            </div>
        `;
    }

    let filenamePatternsHTML = '';
    if (lang.filenamePatterns && lang.filenamePatterns.length > 0) {
        filenamePatternsHTML = `
            <div class="detail-row">
                <span class="detail-label">Filename Patterns:</span>
                <span class="detail-value">${lang.filenamePatterns.map(p => `<code>${p}</code>`).join(' ')}</span>
            </div>
        `;
    }

    let firstLineHTML = '';
    if (lang.firstLine) {
        firstLineHTML = `
            <div class="detail-row">
                <span class="detail-label">First Line:</span>
                <span class="detail-value"><code>${lang.firstLine}</code></span>
            </div>
        `;
    }

    let serversHTML = '';
    const servers = lang.servers;
    if (servers) {
        const sections = [];
        if (servers.lsp && servers.lsp.length > 0) {
            sections.push(`
                <div class="mb-lg">
                    <strong class="text-label">Language Servers (LSP):</strong>
                    ${servers.lsp.map(s => renderServerLink('lsp', s.id, { name: s.name, extra: renderFilterBadges(s) })).join('')}
                </div>
            `);
        }
        if (servers.dap && servers.dap.length > 0) {
            sections.push(`
                <div class="mb-lg">
                    <strong class="text-label">Debug Adapters (DAP):</strong>
                    ${servers.dap.map(s => renderServerLink('dap', s.id, { name: s.name, extra: renderFilterBadges(s) })).join('')}
                </div>
            `);
        }
        if (servers.bsp && servers.bsp.length > 0) {
            sections.push(`
                <div class="mb-lg">
                    <strong class="text-label">Build Servers (BSP):</strong>
                    ${servers.bsp.map(s => renderServerLink('bsp', s.id, { name: s.name, extra: renderFilterBadges(s) })).join('')}
                </div>
            `);
        }
        if (sections.length > 0) {
            serversHTML = `
                <h3 class="text-success mt-2xl">Associated Servers</h3>
                ${sections.join('')}
            `;
        }
    }

    return `
        ${filenamesHTML}
        ${filenamePatternsHTML}
        ${firstLineHTML}

        ${serversHTML}

        ${!servers || countServersFromDTO(servers) === 0 ? `
        <div class="p-lg bg-panel rounded mt-2xl border-left-warning">
            <strong>No servers:</strong>
            <p class="mt-xs mb-0">No language servers, debug adapters, or build servers are currently configured for this language.</p>
        </div>
        ` : ''}
    `;
}

function renderFilterBadges(server) {
    let html = '';
    if (server.pattern) {
        html += ` <span class="selector-tag" style="font-size:0.75rem">pattern: ${server.pattern}</span>`;
    }
    if (server.scheme) {
        html += ` <span class="selector-tag" style="font-size:0.75rem">scheme: ${server.scheme}</span>`;
    }
    return html;
}

function countServersFromDTO(servers) {
    let count = 0;
    for (const type in servers) {
        if (Array.isArray(servers[type])) {
            count += servers[type].length;
        }
    }
    return count;
}

registerActions('click', {
    showLanguageDetails: (el) => showLanguageDetails(el.dataset.languageId),
});
