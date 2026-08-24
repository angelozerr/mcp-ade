export const state = {
    selectedWorkspace: null,
    workspaces: [],
    selectedServer: null,
    currentTab: 'workspaces',
    currentServerId: null,
    currentConsoleTab: 'traces',
    currentWorkspaceTab: 'servers',
    tracesByServer: {},
    traceLevels: {},
    lspConfigs: {},
    dapConfigs: {},
    bspConfigs: {},
    runtimeConfigs: {},
    currentDapSessionId: null,
    currentDapServerId: null,
    dapTracesBySession: {},
    dapTracesByServer: {},
    dapSessions: [],
    installOutputServerId: null,
    currentDiagramServers: null,
    currentDiagramServerId: null,
    currentWorkspaceDiagramServers: null,
    currentWorkspaceDiagramServerId: null,
    modalResolve: null,
    userExplicitlySelectedServer: false,
};

const THEME_DARK = 'dark';
const THEME_LIGHT = 'light';
const THEME_STORAGE_KEY = 'admin-theme';
const THEME_ATTR = 'data-theme';

export function getCurrentTheme() {
    return document.documentElement.getAttribute(THEME_ATTR) || THEME_DARK;
}

export function setTheme(theme) {
    document.documentElement.setAttribute(THEME_ATTR, theme);
    localStorage.setItem(THEME_STORAGE_KEY, theme);
    updateThemeIcon(theme);
}

export function updateThemeIcon(theme) {
    const btn = document.getElementById('theme-toggle-btn');
    if (btn) {
        btn.innerHTML = theme === THEME_DARK ? '\u{1F319}' : '\u{2600}\u{FE0F}';
        btn.title = theme === THEME_DARK ? 'Switch to light theme' : 'Switch to dark theme';
    }
}

export function traceKey(workspaceUri, serverId) {
    return (workspaceUri || '') + '|' + serverId;
}

export function formatStatusClass(status) {
    return 'status-' + status.toLowerCase();
}

export function formatStatusLabel(status, externalInstance) {
    const labels = {
        'NOT_STARTED': 'Not Started',
        'INSTALLING': 'Installing',
        'INSTALL_FAILED': 'Install Failed',
        'STARTING': 'Starting',
        'START_FAILED': 'Start Failed',
        'INDEXING': 'Indexing',
        'RUNNING': 'Running',
        'STOPPING': 'Stopping',
        'STOPPED': 'Stopped',
        'ERROR': 'Error',
        'SWITCHING': 'Switching',
        'CONNECTING_TO_IDE': 'Connecting to IDE',
        'CONNECTED_TO_IDE': 'Connected to IDE',
        'DISCONNECTING': 'Disconnecting'
    };

    if (status === 'CONNECTED_TO_IDE' && externalInstance && externalInstance.clientName) {
        const version = externalInstance.clientVersion ? ` ${externalInstance.clientVersion}` : '';
        return `Connected to ${externalInstance.clientName}${version}`;
    }

    return labels[status] || status;
}

export function getServerName(serverId) {
    const config = state.lspConfigs?.[serverId] || state.dapConfigs?.[serverId] || state.bspConfigs?.[serverId];
    return config?.name || serverId;
}

export function getRuntimeName(runtimeId) {
    return state.runtimeConfigs?.[runtimeId]?.name || runtimeId;
}

export function getServerApiBase(serverId) {
    if (state.bspConfigs[serverId]) return '/api/admin/bsp/configs';
    if (state.dapConfigs[serverId]) return '/api/admin/dap/configs';
    return '/api/admin/lsp/configs';
}

export function mergeServerData(runtime) {
    const serverId = runtime.serverId || runtime.id;
    const config = state.lspConfigs[serverId] || {};
    return {
        id: serverId,
        name: config.name || serverId,
        description: config.description,
        documentSelector: config.documentSelector,
        command: runtime.command || config.command,
        args: config.args,
        env: config.env,
        workingDirectory: config.workingDirectory,
        initializationOptions: config.initializationOptions,
        contributions: config.contributions,
        isExtension: config.isExtension,
        enabled: config.enabled,
        settings: config.settings,
        parentServerId: runtime.parentServerId,
        status: runtime.status,
        statusMessage: runtime.statusMessage,
        isReady: runtime.isReady,
        pid: runtime.pid,
        externalInstance: runtime.externalInstance,
        installProgress: runtime.installProgress,
        traceLevel: runtime.traceLevel
    };
}

export function mergeBspServerData(runtime) {
    const serverId = runtime.serverId || runtime.id;
    const config = state.bspConfigs[serverId] || {};
    return {
        id: serverId,
        name: config.name || serverId,
        description: config.description,
        enabled: config.enabled,
        isBsp: true,
        status: runtime.status,
        statusMessage: runtime.statusMessage,
        isReady: runtime.isReady,
        pid: runtime.pid,
        installProgress: runtime.installProgress,
        traceLevel: runtime.traceLevel
    };
}

export async function loadLspConfigs() {
    try {
        const response = await fetch('/api/admin/lsp/configs');
        const configs = await response.json();
        state.lspConfigs = {};
        configs.forEach(config => { state.lspConfigs[config.id] = config; });
        console.log('Loaded', configs.length, 'LSP configs');
    } catch (error) {
        console.error('Failed to load LSP configs:', error);
    }
}

export async function loadDapConfigs() {
    try {
        const response = await fetch('/api/admin/dap/configs');
        const configs = await response.json();
        state.dapConfigs = {};
        configs.forEach(config => { state.dapConfigs[config.id] = config; });
        console.log('Loaded', configs.length, 'DAP configs');
    } catch (error) {
        console.error('Failed to load DAP configs:', error);
        state.dapConfigs = {};
    }
}

export async function loadBspConfigs() {
    try {
        const response = await fetch('/api/admin/bsp/configs');
        const configs = await response.json();
        state.bspConfigs = {};
        configs.forEach(config => { state.bspConfigs[config.id] = config; });
        console.log('Loaded', configs.length, 'BSP configs');
    } catch (error) {
        console.error('Failed to load BSP configs:', error);
        state.bspConfigs = {};
    }
}

export async function loadRuntimeConfigs() {
    try {
        const response = await fetch('/api/admin/runtimes');
        const runtimes = await response.json();
        state.runtimeConfigs = {};
        runtimes.forEach(rt => { state.runtimeConfigs[rt.id] = rt; });
        console.log('Loaded', runtimes.length, 'runtime configs');
    } catch (error) {
        console.error('Failed to load runtime configs:', error);
        state.runtimeConfigs = {};
    }
}

export function updateSearchBoxVisibility(showSearchBox) {
    const searchBox = document.getElementById('search-box');
    if (searchBox) {
        searchBox.classList.remove('visible');
        if (showSearchBox) {
            searchBox.classList.add('search-box-available');
        } else {
            searchBox.classList.remove('search-box-available');
        }
    }
}

export function buildGlobalContributedByMap(servers) {
    const map = {};
    servers.forEach(server => {
        if (server.contributes && server.contributes.contributeServerConfigurations) {
            server.contributes.contributeServerConfigurations.forEach(targetId => {
                if (!map[targetId]) map[targetId] = [];
                map[targetId].push(server.id);
            });
        }
    });
    return map;
}

export function buildWorkspaceContributedByMap(servers) {
    const map = {};
    for (const server of servers) {
        if (server.contributions) {
            for (const targetId of Object.keys(server.contributions)) {
                if (!map[targetId]) map[targetId] = [];
                map[targetId].push(server.id);
            }
        }
    }
    return map;
}

export function formatWorkspaceContributeInfo(server, contributedByMap) {
    const contributesTo = server.contributions ? Object.keys(server.contributions) : [];
    const contributedBy = contributedByMap[server.id] || [];

    let text = '';
    let tooltip = '';

    if (contributesTo.length > 0) {
        const full = contributesTo.join(', ');
        const styled = contributesTo.map(id => `<span class="text-secondary">${id}</span>`).join(', ');
        const displayStyled = full.length > 20
            ? contributesTo.slice(0, 1).map(id => `<span class="text-secondary">${id}</span>`).join('') + ', <span class="text-secondary">...</span>'
            : styled;
        text = ` <span class="text-muted font-2xl font-bold">→</span> ${displayStyled}`;
        if (full.length > 20) {
            tooltip = `Contributes to: ${full}`;
        }
    } else if (contributedBy.length > 0) {
        const full = contributedBy.join(', ');
        const styled = contributedBy.map(id => `<span class="text-secondary">${id}</span>`).join(', ');
        const displayStyled = full.length > 20
            ? contributedBy.slice(0, 1).map(id => `<span class="text-secondary">${id}</span>`).join('') + ', <span class="text-secondary">...</span>'
            : styled;
        text = ` <span class="text-muted font-2xl font-bold">←</span> ${displayStyled}`;
        if (full.length > 20) {
            tooltip = `Contributed by: ${full}`;
        }
    }

    return { text, tooltip };
}
