# MCP-LSP REST API URLs

## Workspaces

| Méthode | URL | Description | Ressource |
|---------|-----|-------------|-----------|
| GET | `/api/admin/workspaces` | Liste tous les workspaces | WorkspaceAdminResource |
| GET | `/api/admin/workspaces/{uri}` | Détails d'un workspace | WorkspaceAdminResource |
| GET | `/api/admin/workspaces/{uri}/lsp-servers` | Serveurs LSP d'un workspace | WorkspaceAdminResource |
| GET | `/api/admin/workspaces/{uri}/dap-sessions` | Sessions DAP d'un workspace | WorkspaceAdminResource |
| DELETE | `/api/admin/workspaces/{uri}` | Ferme un workspace | WorkspaceAdminResource |

## LSP - Configuration

| Méthode | URL | Description | Ressource |
|---------|-----|-------------|-----------|
| GET | `/api/admin/lsp/configs` | Liste toutes les configs LSP | LspAdminResource |
| GET | `/api/admin/lsp/configs/{serverId}` | Détails d'une config LSP | LspAdminResource |
| GET | `/api/admin/lsp/configs/{serverId}/installer` | Info installateur d'un serveur LSP | LspAdminResource |
| POST | `/api/admin/lsp/configs/{serverId}/installer` | Installe un serveur LSP | LspAdminResource |
| GET | `/api/admin/lsp/configs/{serverId}/trace` | Niveau de trace LSP | LspAdminResource |
| PUT | `/api/admin/lsp/configs/{serverId}/trace` | Change le niveau de trace LSP | LspAdminResource |

## LSP - Serveurs (instances)

| Méthode | URL | Description | Ressource |
|---------|-----|-------------|-----------|
| POST | `/api/admin/lsp/servers/{workspaceUri}/{serverId}/stop` | Arrête un serveur LSP | LspAdminResource |
| POST | `/api/admin/lsp/servers/{workspaceUri}/{serverId}/restart` | Redémarre un serveur LSP | LspAdminResource |
| POST | `/api/admin/lsp/servers/{workspaceUri}/{serverId}/start-managed` | Démarre un serveur LSP managé | LspAdminResource |
| POST | `/api/admin/lsp/servers/{workspaceUri}/{serverId}/disconnect` | Déconnecte un serveur LSP | LspAdminResource |
| POST | `/api/admin/lsp/servers/{workspaceUri}/{serverId}/connect-ide` | Connecte IDE au serveur LSP | LspAdminResource |

## LSP - Traces

| Méthode | URL | Description | Ressource |
|---------|-----|-------------|-----------|
| GET | `/api/admin/lsp/traces` | Traces LSP récentes (limit param) | LspTraceResource |
| GET | `/api/admin/lsp/traces/server/{serverId}` | Traces d'un serveur LSP | LspTraceResource |
| GET | `/api/admin/lsp/traces/workspace/{workspaceUri}/server/{serverId}` | Traces d'un serveur LSP dans un workspace | LspTraceResource |
| DELETE | `/api/admin/lsp/traces` | Efface toutes les traces LSP | LspTraceResource |

## DAP - Configuration

| Méthode | URL | Description | Ressource |
|---------|-----|-------------|-----------|
| GET | `/api/admin/dap/configs` | Liste toutes les configs DAP | DapAdminResource |
| GET | `/api/admin/dap/configs/{serverId}` | Détails d'une config DAP | DapAdminResource |
| GET | `/api/admin/dap/configs/{serverId}/installer` | Info installateur d'un serveur DAP | DapAdminResource |
| POST | `/api/admin/dap/configs/{serverId}/installer` | Installe un serveur DAP | DapAdminResource |
| GET | `/api/admin/dap/configs/{serverId}/templates` | Templates de config DAP | DapAdminResource |

## DAP - Sessions

| Méthode | URL | Description | Ressource |
|---------|-----|-------------|-----------|
| GET | `/api/admin/dap/sessions` | Liste toutes les sessions DAP | DapSessionResource |
| POST | `/api/admin/dap/sessions` | Crée une nouvelle session DAP | DapSessionResource |
| POST | `/api/admin/dap/sessions/{sessionId}/launch` | Lance une session DAP | DapSessionResource |
| POST | `/api/admin/dap/sessions/{sessionId}/stop` | Arrête une session DAP | DapSessionResource |
| DELETE | `/api/admin/dap/sessions/{sessionId}` | Supprime une session DAP | DapSessionResource |
| GET | `/api/admin/dap/sessions/templates/{serverId}` | Templates pour un serveur DAP | DapSessionResource |

## DAP - Traces

| Méthode | URL | Description | Ressource |
|---------|-----|-------------|-----------|
| GET | `/api/admin/dap/traces` | Traces DAP récentes (limit param) | DapTraceResource |
| GET | `/api/admin/dap/traces/session/{sessionId}` | Traces d'une session DAP | DapTraceResource |
| DELETE | `/api/admin/dap/traces` | Efface toutes les traces DAP | DapTraceResource |

## MCP - Configuration

| Méthode | URL | Description | Ressource |
|---------|-----|-------------|-----------|
| GET | `/api/admin/mcp/config` | Configuration MCP (incluant trace level) | McpAdminResource |
| PUT | `/api/admin/mcp/config` | Change la configuration MCP | McpAdminResource |

## MCP - Clients

| Méthode | URL | Description | Ressource |
|---------|-----|-------------|-----------|
| GET | `/api/admin/mcp/clients` | Liste tous les clients MCP | McpClientsResource |

## MCP - Traces

| Méthode | URL | Description | Ressource |
|---------|-----|-------------|-----------|
| GET | `/api/admin/mcp/traces` | Traces MCP récentes (limit param) | McpTracesResource |
| DELETE | `/api/admin/mcp/traces` | Efface toutes les traces MCP | McpTracesResource |

---

## ✅ Structure normalisée (IMPLÉMENTÉE)

Toutes les URLs suivent maintenant le pattern `/api/admin/{protocol}/...` :

**LSP** - Language Server Protocol
- Configs : `/api/admin/lsp/configs/*`
- Serveurs (instances) : `/api/admin/lsp/servers/*`
- Traces : `/api/admin/lsp/traces/*`

**DAP** - Debug Adapter Protocol
- Configs : `/api/admin/dap/configs/*`
- Sessions : `/api/admin/dap/sessions/*`
- Traces : `/api/admin/dap/traces/*`

**MCP** - Model Context Protocol
- Config : `/api/admin/mcp/config`
- Clients : `/api/admin/mcp/clients`
- Traces : `/api/admin/mcp/traces/*`

**Workspaces**
- `/api/admin/workspaces/*`
