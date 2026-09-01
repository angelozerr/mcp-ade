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
    lspConfigs: null,
    dapConfigs: null,
    bspConfigs: null,
    runtimeConfigs: null,
    extensionConfigs: null,
    languageConfigs: null,
    currentDapSessionId: null,
    currentDapServerId: null,
    dapTracesBySession: {},
    dapTracesByServer: {},
    dapSessions: null,
    installOutputServerId: null,
    installingServers: new Set(),
    installTraces: {},
    installStatus: {},
    installProgress: {},
    currentDiagramServers: null,
    currentDiagramServerId: null,
    currentWorkspaceDiagramServers: null,
    currentWorkspaceDiagramServerId: null,
    modalResolve: null,
    userExplicitlySelectedServer: false,
};

export function isOnWorkspacesTab() {
    return state.currentTab === 'workspaces';
}

export function isOnMcpTab() {
    return state.currentTab === 'mcp-traces';
}

export function isOnRuntimesTab() {
    return state.currentTab === 'runtimes';
}

export function isOnDebuggersTab() {
    return state.currentTab === 'workspaces' && state.currentWorkspaceTab === 'debuggers';
}

export function isOnBuildTab() {
    return state.currentTab === 'workspaces' && state.currentWorkspaceTab === 'build';
}

export function isOnSettingsTab() {
    return state.currentTab === 'workspaces' && state.currentWorkspaceTab === 'settings';
}

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
    if (state.bspConfigs?.[serverId]) return '/api/admin/bsp/configs';
    if (state.dapConfigs?.[serverId]) return '/api/admin/dap/configs';
    return '/api/admin/lsp/configs';
}

export function mergeServerData(runtime) {
    const serverId = runtime.serverId || runtime.id;
    const config = state.lspConfigs?.[serverId] || {};
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
        hasInstaller: config.hasInstaller,
        installationStatus: config.installationStatus,
        installProgress: runtime.installProgress,
        traceLevel: runtime.traceLevel
    };
}

export function mergeBspServerData(runtime) {
    const serverId = runtime.serverId || runtime.id;
    const config = state.bspConfigs?.[serverId] || {};
    return {
        id: serverId,
        name: config.name || serverId,
        description: config.description,
        enabled: config.enabled,
        isBsp: true,
        hasInstaller: config.hasInstaller,
        installationStatus: config.installationStatus,
        status: runtime.status,
        statusMessage: runtime.statusMessage,
        isReady: runtime.isReady,
        pid: runtime.pid,
        installProgress: runtime.installProgress,
        traceLevel: runtime.traceLevel
    };
}

let lspConfigsPromise = null;
let dapConfigsPromise = null;
let bspConfigsPromise = null;
let runtimeConfigsPromise = null;
let extensionConfigsPromise = null;
let languageConfigsPromise = null;

export async function loadLspConfigs() {
    try {
        const response = await fetch('/api/admin/lsp/configs');
        const configs = await response.json();
        state.lspConfigs = {};
        configs.forEach(config => {
            if (state.installStatus[config.id]) {
                config.installationStatus = state.installStatus[config.id];
            }
            state.lspConfigs[config.id] = config;
        });
        console.log('Loaded', configs.length, 'LSP configs');
    } catch (error) {
        console.error('Failed to load LSP configs:', error);
    }
}

export async function ensureLspConfigs() {
    if (state.lspConfigs) return;
    if (!lspConfigsPromise) {
        lspConfigsPromise = loadLspConfigs().finally(() => { lspConfigsPromise = null; });
    }
    return lspConfigsPromise;
}

export async function loadDapConfigs() {
    try {
        const response = await fetch('/api/admin/dap/configs');
        const configs = await response.json();
        state.dapConfigs = {};
        configs.forEach(config => {
            if (state.installStatus[config.id]) {
                config.installationStatus = state.installStatus[config.id];
            }
            state.dapConfigs[config.id] = config;
        });
        console.log('Loaded', configs.length, 'DAP configs');
    } catch (error) {
        console.error('Failed to load DAP configs:', error);
    }
}

export async function ensureDapConfigs() {
    if (state.dapConfigs) return;
    if (!dapConfigsPromise) {
        dapConfigsPromise = loadDapConfigs().finally(() => { dapConfigsPromise = null; });
    }
    return dapConfigsPromise;
}

export async function loadBspConfigs() {
    try {
        const response = await fetch('/api/admin/bsp/configs');
        const configs = await response.json();
        state.bspConfigs = {};
        configs.forEach(config => {
            if (state.installStatus[config.id]) {
                config.installationStatus = state.installStatus[config.id];
            }
            state.bspConfigs[config.id] = config;
        });
        console.log('Loaded', configs.length, 'BSP configs');
    } catch (error) {
        console.error('Failed to load BSP configs:', error);
    }
}

export async function ensureBspConfigs() {
    if (state.bspConfigs) return;
    if (!bspConfigsPromise) {
        bspConfigsPromise = loadBspConfigs().finally(() => { bspConfigsPromise = null; });
    }
    return bspConfigsPromise;
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
    }
}

export async function ensureRuntimeConfigs() {
    if (state.runtimeConfigs) return;
    if (!runtimeConfigsPromise) {
        runtimeConfigsPromise = loadRuntimeConfigs().finally(() => { runtimeConfigsPromise = null; });
    }
    return runtimeConfigsPromise;
}

export async function loadExtensionConfigs() {
    try {
        const response = await fetch('/api/admin/extensions');
        if (!response.ok) throw new Error('Failed to load extensions');
        state.extensionConfigs = (await response.json()).sort((a, b) => (a.id || '').localeCompare(b.id || ''));
        console.log('Loaded', state.extensionConfigs.length, 'extension configs');
    } catch (error) {
        console.error('Failed to load extension configs:', error);
    }
}

export async function ensureExtensionConfigs() {
    if (state.extensionConfigs) return;
    if (!extensionConfigsPromise) {
        extensionConfigsPromise = loadExtensionConfigs().finally(() => { extensionConfigsPromise = null; });
    }
    return extensionConfigsPromise;
}

export async function loadLanguageConfigs() {
    try {
        const response = await fetch('/api/admin/languages');
        state.languageConfigs = await response.json();
        console.log('Loaded', state.languageConfigs.length, 'language configs');
    } catch (error) {
        console.error('Failed to load language configs:', error);
    }
}

export async function ensureLanguageConfigs() {
    if (state.languageConfigs) return;
    if (!languageConfigsPromise) {
        languageConfigsPromise = loadLanguageConfigs().finally(() => { languageConfigsPromise = null; });
    }
    return languageConfigsPromise;
}

export async function ensureLspConfigDetail(serverId) {
    const config = state.lspConfigs?.[serverId];
    if (!config || config._detailLoaded) return config;
    try {
        const response = await fetch(`/api/admin/lsp/configs/${serverId}`);
        const detail = await response.json();
        Object.assign(config, detail);
        config._detailLoaded = true;
    } catch (error) {
        console.error('Failed to load LSP config detail:', error);
    }
    return config;
}

export async function ensureDapConfigDetail(serverId) {
    const config = state.dapConfigs?.[serverId];
    if (!config || config._detailLoaded) return config;
    try {
        const response = await fetch(`/api/admin/dap/configs/${serverId}`);
        const detail = await response.json();
        Object.assign(config, detail);
        config._detailLoaded = true;
    } catch (error) {
        console.error('Failed to load DAP config detail:', error);
    }
    return config;
}

export async function ensureBspConfigDetail(serverId) {
    const config = state.bspConfigs?.[serverId];
    if (!config || config._detailLoaded) return config;
    try {
        const response = await fetch(`/api/admin/bsp/configs/${serverId}`);
        const detail = await response.json();
        Object.assign(config, detail);
        config._detailLoaded = true;
    } catch (error) {
        console.error('Failed to load BSP config detail:', error);
    }
    return config;
}

export async function ensureRuntimeConfigDetail(runtimeId) {
    const config = state.runtimeConfigs?.[runtimeId];
    if (!config || config._detailLoaded) return config;
    try {
        const response = await fetch(`/api/admin/runtimes/${runtimeId}`);
        const detail = await response.json();
        Object.assign(config, detail);
        config._detailLoaded = true;
    } catch (error) {
        console.error('Failed to load runtime config detail:', error);
    }
    return config;
}

export async function ensureExtensionConfigDetail(extensionId) {
    const config = state.extensionConfigs?.find(e => e.id === extensionId);
    if (!config || config._detailLoaded) return config;
    try {
        const response = await fetch(`/api/admin/extensions/${encodeURIComponent(extensionId)}`);
        const detail = await response.json();
        Object.assign(config, detail);
        config._detailLoaded = true;
    } catch (error) {
        console.error('Failed to load extension config detail:', error);
    }
    return config;
}

export async function ensureLanguageConfigDetail(languageId) {
    const config = state.languageConfigs?.find(l => l.id === languageId);
    if (!config || config._detailLoaded) return config;
    try {
        const response = await fetch(`/api/admin/languages/${encodeURIComponent(languageId)}`);
        const detail = await response.json();
        Object.assign(config, detail);
        config._detailLoaded = true;
    } catch (error) {
        console.error('Failed to load language config detail:', error);
    }
    return config;
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
