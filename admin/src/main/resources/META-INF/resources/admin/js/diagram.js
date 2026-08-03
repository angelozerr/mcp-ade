/**
 * Server dependency diagram using vis.js
 */

let serverDiagramNetwork = null;
let workspaceDiagramNetwork = null;

/**
 * Read a CSS custom property value from the current theme.
 */
function getThemeColor(varName) {
    return getComputedStyle(document.documentElement).getPropertyValue(varName).trim();
}

/**
 * Build the color palette from CSS variables for vis.js diagrams.
 */
function getDiagramColors() {
    return {
        nodeLsp: getThemeColor('--diagram-node-lsp'),
        nodeLspSelected: getThemeColor('--diagram-node-lsp-selected'),
        nodeDap: getThemeColor('--diagram-node-dap'),
        nodeDapSelected: getThemeColor('--diagram-node-dap-selected'),
        nodeExt: getThemeColor('--diagram-node-ext'),
        nodeExtSelected: getThemeColor('--diagram-node-ext-selected'),
        nodeBorder: getThemeColor('--diagram-node-border'),
        edgeBundle: getThemeColor('--diagram-edge-bundle'),
        edgeClasspath: getThemeColor('--diagram-edge-classpath'),
        edgeBindReq: getThemeColor('--diagram-edge-bind-req'),
        edgeBindNotif: getThemeColor('--diagram-edge-bind-notif'),
        edgeLabelBg: getThemeColor('--diagram-edge-label-bg'),
        fontColor: getThemeColor('--diagram-font-color'),
        edgeColor: getThemeColor('--diagram-edge-color'),
        textBright: getThemeColor('--text-bright'),
        textSecondary: getThemeColor('--text-secondary')
    };
}

/**
 * Get the node color based on server type and selection state.
 */
function getNodeColor(server, currentServerId, colors) {
    if (server.id === currentServerId) {
        if (server.isDap) return colors.nodeDapSelected;
        if (server.isExtension) return colors.nodeExtSelected;
        return colors.nodeLspSelected;
    }
    if (server.isExtension) return colors.nodeExt;
    if (server.isDap) return colors.nodeDap;
    return colors.nodeLsp;
}

/**
 * Build vis.js nodes from filtered servers.
 */
function buildNodes(filteredServers, currentServerId, colors) {
    return filteredServers.map(server => {
        const icon = server.isExtension ? '🧩' : (server.isDap ? '🐛' : '🚀');
        const label = `${icon} ${server.name || server.id}`;
        return {
            id: server.id,
            label: label,
            title: server.id === currentServerId
                ? (server.description || server.name || server.id)
                : `${server.description || server.name || server.id}\n\n💡 Double-click to open`,
            color: getNodeColor(server, currentServerId, colors),
            font: {
                color: colors.fontColor,
                size: 14
            },
            shape: 'box',
            margin: 10
        };
    });
}

/**
 * Build vis.js edges from filtered servers.
 */
function buildEdges(filteredServers, colors) {
    const edgeColors = {
        bundles: colors.edgeBundle,
        classpath: colors.edgeClasspath,
        bindRequest: colors.edgeBindReq,
        bindNotification: colors.edgeBindNotif
    };

    const edgeMap = new Map();

    filteredServers.forEach(server => {
        if (!server.contributions) return;

        Object.keys(server.contributions).forEach(targetServerId => {
            const contributionData = server.contributions[targetServerId];
            const edgeKey = `${server.id}->${targetServerId}`;

            if (!edgeMap.has(edgeKey)) {
                edgeMap.set(edgeKey, {
                    from: server.id,
                    to: targetServerId,
                    contributions: []
                });
            }

            Object.keys(contributionData).forEach(type => {
                const items = contributionData[type];
                if (!items || items.length === 0) return;

                edgeMap.get(edgeKey).contributions.push({
                    type: type,
                    count: items.length,
                    color: edgeColors[type] || colors.textSecondary
                });
            });
        });
    });

    const edges = [];
    edgeMap.forEach((edgeData) => {
        if (edgeData.contributions.length === 0) return;

        const label = edgeData.contributions
            .map(c => `${c.type} (${c.count})`)
            .join('\n');

        const color = edgeData.contributions[0].color;

        edges.push({
            from: edgeData.from,
            to: edgeData.to,
            label: label,
            color: {
                color: color,
                highlight: colors.textBright
            },
            arrows: {
                to: {
                    enabled: true,
                    scaleFactor: 0.3
                }
            },
            font: {
                color: colors.edgeColor,
                size: 11,
                strokeWidth: 0,
                multi: true,
                align: 'horizontal'
            },
            smooth: {
                type: 'curvedCW',
                roundness: 0.2
            }
        });
    });

    return edges;
}

/**
 * Build vis.js options.
 */
function buildDiagramOptions(colors) {
    return {
        layout: {
            hierarchical: false
        },
        physics: {
            enabled: true,
            solver: 'forceAtlas2Based',
            forceAtlas2Based: {
                gravitationalConstant: -50,
                centralGravity: 0.01,
                springLength: 200,
                springConstant: 0.08,
                damping: 0.85,
                avoidOverlap: 1
            },
            maxVelocity: 30,
            stabilization: {
                enabled: true,
                iterations: 500,
                updateInterval: 25
            }
        },
        interaction: {
            hover: true,
            navigationButtons: true,
            keyboard: {
                enabled: true,
                bindToWindow: false
            }
        },
        nodes: {
            borderWidth: 2,
            borderWidthSelected: 3,
            color: {
                border: colors.nodeBorder,
                background: colors.nodeLsp,
                highlight: {
                    border: colors.nodeLspSelected,
                    background: colors.nodeLspSelected
                }
            }
        },
        edges: {
            width: 1,
            selectionWidth: 2,
            font: {
                strokeWidth: 3,
                strokeColor: colors.edgeLabelBg,
                background: colors.edgeLabelBg,
                size: 11
            },
            labelHighlightBold: false
        }
    };
}

/**
 * Render server dependency diagram.
 * Shows only the current server + its direct dependencies.
 */
function renderServerDiagram(servers, currentServerId) {
    const container = document.getElementById('server-diagram-container');
    if (!container) {
        console.error('Diagram container not found');
        return;
    }

    const colors = getDiagramColors();

    // Filter: only current server + direct dependencies
    const relevantServerIds = new Set([currentServerId]);

    const currentServer = servers.find(s => s.id === currentServerId);
    if (currentServer && currentServer.contributions) {
        Object.keys(currentServer.contributions).forEach(targetId => {
            relevantServerIds.add(targetId);
        });
    }

    servers.forEach(server => {
        if (server.contributions && server.contributions[currentServerId]) {
            relevantServerIds.add(server.id);
        }
    });

    const filteredServers = servers.filter(s => relevantServerIds.has(s.id));

    const nodes = buildNodes(filteredServers, currentServerId, colors);
    const edges = buildEdges(filteredServers, colors);

    const data = {
        nodes: new vis.DataSet(nodes),
        edges: new vis.DataSet(edges)
    };

    const options = buildDiagramOptions(colors);

    if (serverDiagramNetwork) {
        serverDiagramNetwork.destroy();
    }

    serverDiagramNetwork = new vis.Network(container, data, options);

    serverDiagramNetwork.on('hoverNode', function(params) {
        if (params.node !== currentServerId) {
            container.style.cursor = 'pointer';
        }
    });
    serverDiagramNetwork.on('blurNode', function() {
        container.style.cursor = 'default';
    });

    serverDiagramNetwork.on('doubleClick', function(params) {
        if (params.nodes.length > 0) {
            const clickedServerId = params.nodes[0];

            if (clickedServerId === currentServerId) {
                console.log('Already viewing this server:', clickedServerId);
                return;
            }

            console.log('Double-clicked on server:', clickedServerId);

            const clickedServer = servers.find(s => s.id === clickedServerId);
            if (clickedServer?.isDap) {
                if (window.switchTab) {
                    window.switchTab('dap-servers', null, { serverId: clickedServerId });
                }
            } else {
                if (window.switchTab) {
                    window.switchTab('lsp-servers', null, { serverId: clickedServerId });
                }
            }
        }
    });

    setTimeout(() => {
        serverDiagramNetwork.fit({
            animation: {
                duration: 500,
                easingFunction: 'easeInOutQuad'
            }
        });
    }, 100);

    console.log('Server diagram rendered with', nodes.length, 'nodes and', edges.length, 'edges');

    initDiagramResizer('server-diagram-container');
}

/**
 * Render workspace server diagram.
 * Shows only servers that have at least one contribution.
 */
function renderWorkspaceDiagram(servers, currentServerId) {
    const container = document.getElementById('workspace-diagram-container');
    if (!container) {
        console.error('Workspace diagram container not found');
        return;
    }

    const colors = getDiagramColors();

    // Filter: only servers with contributions
    const connectedServerIds = new Set();

    servers.forEach(server => {
        if (server.contributions && Object.keys(server.contributions).length > 0) {
            connectedServerIds.add(server.id);
            Object.keys(server.contributions).forEach(targetId => {
                connectedServerIds.add(targetId);
            });
        }
    });

    const filteredServers = servers.filter(s => connectedServerIds.has(s.id));

    const nodes = buildNodes(filteredServers, currentServerId, colors);
    const edges = buildEdges(filteredServers, colors);

    const data = {
        nodes: new vis.DataSet(nodes),
        edges: new vis.DataSet(edges)
    };

    const options = buildDiagramOptions(colors);

    if (workspaceDiagramNetwork) {
        workspaceDiagramNetwork.destroy();
    }

    workspaceDiagramNetwork = new vis.Network(container, data, options);

    workspaceDiagramNetwork.on('hoverNode', function(params) {
        if (params.node !== currentServerId) {
            container.style.cursor = 'pointer';
        }
    });
    workspaceDiagramNetwork.on('blurNode', function() {
        container.style.cursor = 'default';
    });

    workspaceDiagramNetwork.on('doubleClick', function(params) {
        if (params.nodes.length > 0) {
            const clickedServerId = params.nodes[0];

            if (clickedServerId === currentServerId) {
                console.log('Already viewing this server:', clickedServerId);
                return;
            }

            console.log('Double-clicked on workspace server:', clickedServerId);

            const clickedServer = servers.find(s => s.id === clickedServerId);
            if (clickedServer?.isDap) {
                if (window.switchWorkspaceTab) {
                    window.switchWorkspaceTab('debuggers');
                }
                if (window.selectDapSessionByServerId) {
                    window.selectDapSessionByServerId(clickedServerId);
                }
            } else {
                if (window.switchWorkspaceTab) {
                    window.switchWorkspaceTab('servers');
                }
                if (window.selectServer) {
                    window.selectServer(clickedServer);
                } else {
                    switchConsoleTab('overview');
                    loadServerDetails(clickedServerId);
                }
            }
        }
    });

    setTimeout(() => {
        workspaceDiagramNetwork.fit({
            animation: {
                duration: 500,
                easingFunction: 'easeInOutQuad'
            }
        });
    }, 100);

    console.log('Workspace diagram rendered with', nodes.length, 'nodes and', edges.length, 'edges');

    initDiagramResizer('workspace-diagram-container');
}

/**
 * Initialize a draggable resizer between a diagram container and the content below it.
 * The resizer element must be a sibling between the diagram container and the content panel.
 */
function initDiagramResizer(diagramContainerId) {
    const diagramContainer = document.getElementById(diagramContainerId);
    if (!diagramContainer) return;

    const resizer = diagramContainer.nextElementSibling;
    if (!resizer || !resizer.classList.contains('diagram-resizer')) return;

    const contentPanel = resizer.nextElementSibling;
    if (!contentPanel) return;

    let startY, startHeight;

    function onMouseDown(e) {
        e.preventDefault();
        startY = e.clientY;
        startHeight = diagramContainer.offsetHeight;
        resizer.classList.add('active');
        document.addEventListener('mousemove', onMouseMove);
        document.addEventListener('mouseup', onMouseUp);
        document.body.style.cursor = 'ns-resize';
        document.body.style.userSelect = 'none';
    }

    function onMouseMove(e) {
        const delta = e.clientY - startY;
        const newHeight = Math.max(100, startHeight + delta);
        diagramContainer.style.height = newHeight + 'px';

        const network = diagramContainerId.includes('workspace')
            ? workspaceDiagramNetwork
            : serverDiagramNetwork;
        if (network) {
            network.redraw();
        }
    }

    function onMouseUp() {
        resizer.classList.remove('active');
        document.removeEventListener('mousemove', onMouseMove);
        document.removeEventListener('mouseup', onMouseUp);
        document.body.style.cursor = '';
        document.body.style.userSelect = '';
    }

    resizer.addEventListener('mousedown', onMouseDown);
}
