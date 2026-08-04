import { state, buildWorkspaceContributedByMap } from './shared-state.js';
import { escapeHtml } from './trace-renderer.js';

export function formatContributionsSection(server, allServers = null) {
    const contributesTo = server.contributions ? Object.keys(server.contributions) : [];

    if (!allServers) {
        const workspace = state.workspaces.find(w => w.rootUri === state.selectedWorkspace);
        const dapServersWithFlag = (Object.values(state.dapConfigs || {}) || []).map(s => ({...s, isDap: true}));
        allServers = workspace ? [...(workspace.lspServers || []), ...dapServersWithFlag] : [];
    }
    const contributedByMap = buildWorkspaceContributedByMap(allServers);
    const contributedBy = contributedByMap[server.id] || [];

    if (contributesTo.length === 0 && contributedBy.length === 0) {
        return '';
    }

    let html = '<div class="details-section"><h4>Contributions</h4>';

    if (contributesTo.length > 0) {
        html += '<div class="contribution-subsection">';
        html += '<h5 class="text-success mb-sm">→ Contributes To</h5>';

        for (const targetServerId of contributesTo) {
            const contributionData = server.contributions[targetServerId];
            html += `<div class="contribution-target mb-lg">`;
            html += `<div class="text-label-alt mb-xs font-bold">${targetServerId}</div>`;

            for (const [type, items] of Object.entries(contributionData)) {
                if (items && items.length > 0) {
                    html += `<div class="mb-sm ml-lg">`;
                    html += `<span class="text-secondary">${type}:</span>`;
                    html += `<ul class="text-secondary p-0 font-base mt-xs mb-0 ml-xl">`;
                    items.forEach(item => {
                        const displayValue = typeof item === 'string' ? item : JSON.stringify(item);
                        const isError = displayValue.startsWith('ERROR:');
                        const cleanValue = isError ? displayValue.substring(6) : displayValue;
                        const errorClass = isError ? 'text-error-light' : '';
                        const style = isError ? 'font-weight: bold; cursor: help;' : '';
                        const title = isError ? 'File not found or pattern did not match any files' : '';
                        html += `<li class="${errorClass}" style="margin-bottom: 0.2rem; word-break: break-all; ${style}" ${title ? `title="${title}"` : ''}>${escapeHtml(cleanValue)}</li>`;
                    });
                    html += `</ul></div>`;
                }
            }
            html += `</div>`;
        }
        html += '</div>';
    }

    if (contributedBy.length > 0) {
        html += '<div class="contribution-subsection mt-lg">';
        html += '<h5 class="text-string mb-sm">← Contributed By</h5>';

        const contributionsByType = {};

        contributedBy.forEach(contributorServerId => {
            const contributorServer = allServers.find(s => s.id === contributorServerId);
            if (!contributorServer || !contributorServer.contributions) return;

            const contributionData = contributorServer.contributions[server.id];
            if (!contributionData) return;

            for (const [type, items] of Object.entries(contributionData)) {
                if (items && items.length > 0) {
                    if (!contributionsByType[type]) contributionsByType[type] = [];
                    items.forEach(item => {
                        contributionsByType[type].push({ server: contributorServerId, value: item });
                    });
                }
            }
        });

        for (const [type, contributions] of Object.entries(contributionsByType)) {
            html += `<div class="mb-lg">`;
            html += `<div class="text-secondary mb-sm font-bold">${type} <span class="text-dimmed">(Total: ${contributions.length})</span></div>`;
            html += `<div class="ml-lg">`;

            contributions.forEach(contrib => {
                const displayValue = typeof contrib.value === 'string' ? contrib.value : JSON.stringify(contrib.value);
                const isError = displayValue.startsWith('ERROR:');
                const cleanValue = isError ? displayValue.substring(6) : displayValue;
                const valueClass = isError ? 'text-error-light' : '';
                const valueStyle = isError ? 'word-break: break-all; font-weight: bold; cursor: help;' : 'word-break: break-all;';
                const title = isError ? 'File not found or pattern did not match any files' : '';
                html += `<div class="text-secondary font-base mb-xs">`;
                html += `<span class="text-label-alt d-inline-block contribution-label">${contrib.server}</span>`;
                html += `<span class="text-label">•</span> `;
                html += `<span class="${valueClass}" style="${valueStyle}" ${title ? `title="${title}"` : ''}>${escapeHtml(cleanValue)}</span>`;
                html += `</div>`;
            });

            html += `</div></div>`;
        }

        html += '</div>';
    }

    html += '</div>';
    return html;
}
