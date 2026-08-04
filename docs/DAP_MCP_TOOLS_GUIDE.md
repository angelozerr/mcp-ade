# Guide d'utilisation des outils MCP pour le débogage (DAP)

## Vue d'ensemble

Le système DAP (Debug Adapter Protocol) est exposé via **20+ outils MCP** permettant à un AI client de déboguer des programmes dans différents langages (JavaScript, Python, Java, Go, etc.).

## Outils disponibles

### 1. Gestion des sessions

#### `create_debug_session`
Crée une nouvelle session de débogage pour un langage spécifique.

**Paramètres:**
- `language` (string): Le langage de programmation (ex: "javascript", "python", "java")
- `sessionName` (string): Nom descriptif de la session
- `workspaceUri` (string): URI du workspace (ex: "file:///path/to/project")

**Retour:**
```json
{
  "sessionId": "abc-123",
  "language": "javascript",
  "state": "CREATED"
}
```

#### `list_debug_sessions`
Liste toutes les sessions de débogage actives avec leur état.

**Retour:**
```json
[
  {
    "sessionId": "abc-123",
    "language": "javascript",
    "sessionName": "Debug main.js",
    "state": "PAUSED"
  }
]
```

#### `list_supported_languages`
Liste les langages supportés par les adaptateurs de débogage disponibles.

**Retour:**
```json
["javascript", "python", "java", "go", "rust"]
```

#### `close_debug_session`
Ferme et termine une session de débogage.

**Paramètres:**
- `sessionId` (string): ID de la session

---

### 2. Points d'arrêt (Breakpoints)

#### `set_breakpoint`
Place un point d'arrêt à une ligne spécifique.

**Paramètres:**
- `sessionId` (string): ID de la session
- `file` (string): Chemin du fichier
- `line` (int): Numéro de ligne
- `condition` (string, optionnel): Condition (ex: "x > 10")

**Retour:**
```json
{
  "success": true,
  "breakpointId": "bp-1",
  "file": "/path/to/file.js",
  "line": 42,
  "verified": true
}
```

#### `remove_breakpoint`
Supprime un point d'arrêt.

**Paramètres:**
- `sessionId` (string): ID de la session
- `breakpointId` (string): ID du breakpoint

#### `list_all_breakpoints`
Liste tous les points d'arrêt de la session.

**Retour:**
```json
{
  "success": true,
  "count": 2,
  "breakpoints": [
    {
      "breakpointId": "bp-1",
      "file": "/path/to/file.js",
      "line": 42,
      "verified": true,
      "condition": ""
    }
  ]
}
```

---

### 3. Cycle de vie du débogage

#### `start_debugging`
Lance un programme en mode débogage.

**Paramètres:**
- `sessionId` (string): ID de la session
- `scriptPath` (string): Chemin du script à déboguer
- `additionalArgs` (map, optionnel): Arguments supplémentaires (dépend du langage)

**Exemple pour Node.js:**
```json
{
  "sessionId": "abc-123",
  "scriptPath": "/path/to/main.js",
  "additionalArgs": {
    "args": ["--port", "3000"],
    "env": {"DEBUG": "true"}
  }
}
```

**Exemple pour Java:**
```json
{
  "sessionId": "java-123",
  "scriptPath": "/path/to/project",
  "additionalArgs": {
    "mainClass": "com.example.Main",
    "projectName": "my-project"
  }
}
```

#### `attach_to_process`
Attache le débogueur à un processus en cours d'exécution.

**Paramètres:**
- `sessionId` (string): ID de la session
- `processId` (int): PID du processus

---

### 4. Contrôle d'exécution

#### `continue_execution`
Continue l'exécution après un point d'arrêt.

**Paramètres:**
- `sessionId` (string): ID de la session

#### `pause_execution`
Met en pause le programme en cours d'exécution.

#### `step_over`
Exécute la ligne courante sans entrer dans les fonctions.

#### `step_in`
Entre dans la fonction appelée sur la ligne courante.

#### `step_out`
Sort de la fonction courante et retourne à l'appelant.

---

### 5. Inspection

#### `get_stack_trace`
Obtient la pile d'appels (stack trace).

**Retour:**
```json
{
  "success": true,
  "frames": [
    {
      "id": 1,
      "name": "myFunction",
      "file": "/path/to/file.js",
      "line": 42,
      "column": 10
    }
  ]
}
```

#### `list_threads`
Liste tous les threads du programme débogué.

**Retour:**
```json
{
  "success": true,
  "threads": [
    {"id": 1, "name": "main"},
    {"id": 2, "name": "worker-1"}
  ]
}
```

#### `get_scopes`
Obtient les scopes de variables pour une frame donnée.

**Paramètres:**
- `sessionId` (string): ID de la session
- `frameId` (int): ID de la frame (depuis `get_stack_trace`)

**Retour:**
```json
{
  "success": true,
  "scopes": [
    {
      "name": "Locals",
      "variablesReference": 1,
      "expensive": false
    },
    {
      "name": "Globals",
      "variablesReference": 2,
      "expensive": true
    }
  ]
}
```

#### `get_variables`
Obtient les variables d'un scope.

**Paramètres:**
- `sessionId` (string): ID de la session
- `variablesReference` (int): Référence depuis `get_scopes`

**Retour:**
```json
{
  "success": true,
  "variables": [
    {
      "name": "x",
      "value": "42",
      "type": "number",
      "variablesReference": 0,
      "expandable": false
    },
    {
      "name": "obj",
      "value": "{...}",
      "type": "object",
      "variablesReference": 3,
      "expandable": true
    }
  ]
}
```

#### `get_local_variables`
Raccourci pour obtenir les variables locales de la frame courante.

**Paramètres:**
- `sessionId` (string): ID de la session

#### `evaluate_expression`
Évalue une expression dans le contexte de débogage actuel.

**Paramètres:**
- `sessionId` (string): ID de la session
- `expression` (string): Expression à évaluer (ex: "x + y", "myArray.length")
- `frameId` (int, optionnel): ID de la frame (utilise la frame courante si omis)

**Retour:**
```json
{
  "success": true,
  "result": "52",
  "type": "number",
  "variablesReference": 0
}
```

---

### 6. Statistiques

#### `get_debug_statistics`
Obtient des statistiques sur les sessions de débogage actives.

**Retour:**
```json
{
  "totalSessions": 3,
  "byState": {
    "RUNNING": 1,
    "PAUSED": 2
  },
  "byLanguage": {
    "javascript": 2,
    "python": 1
  }
}
```

---

## Workflow typique

### 1. Déboguer un programme JavaScript

```javascript
// 1. Créer une session
create_debug_session({
  language: "javascript",
  sessionName: "Debug app.js",
  workspaceUri: "file:///home/user/my-app"
})
// → sessionId: "js-123"

// 2. Placer des breakpoints
set_breakpoint({
  sessionId: "js-123",
  file: "/home/user/my-app/app.js",
  line: 15
})

set_breakpoint({
  sessionId: "js-123",
  file: "/home/user/my-app/utils.js",
  line: 42,
  condition: "count > 100"  // Breakpoint conditionnel
})

// 3. Lancer le débogage
start_debugging({
  sessionId: "js-123",
  scriptPath: "/home/user/my-app/app.js",
  additionalArgs: {
    "args": ["--port", "3000"]
  }
})

// 4. Quand le programme s'arrête à un breakpoint:

// Inspecter la stack
get_stack_trace({sessionId: "js-123"})

// Voir les variables locales
get_local_variables({sessionId: "js-123"})

// Évaluer une expression
evaluate_expression({
  sessionId: "js-123",
  expression: "user.name + ' ' + user.age"
})

// 5. Contrôle d'exécution
step_over({sessionId: "js-123"})     // Ligne suivante
step_in({sessionId: "js-123"})       // Entrer dans la fonction
continue_execution({sessionId: "js-123"})  // Continuer

// 6. Fermer la session
close_debug_session({sessionId: "js-123"})
```

### 2. Déboguer un programme Java

```java
// 1. Créer une session Java
create_debug_session({
  language: "java",
  sessionName: "Debug HelloWorld",
  workspaceUri: "file:///home/user/java-project"
})
// → sessionId: "java-456"

// 2. Placer un breakpoint
set_breakpoint({
  sessionId: "java-456",
  file: "/home/user/java-project/src/main/java/com/example/Main.java",
  line: 25
})

// 3. Lancer avec configuration Java spécifique
start_debugging({
  sessionId: "java-456",
  scriptPath: "/home/user/java-project",
  additionalArgs: {
    "mainClass": "com.example.Main",
    "projectName": "my-java-app",
    "vmArgs": "-Xmx512m"
  }
})
```

---

## Avantages pour l'AI

1. **Débogage interactif**: L'AI peut déboguer pas à pas un programme pour comprendre son comportement
2. **Inspection profonde**: Accès complet aux variables, stack traces, et expressions
3. **Multi-langages**: Support de JavaScript, Python, Java, Go, etc.
4. **Points d'arrêt conditionnels**: Pause uniquement quand une condition est vraie
5. **Évaluation d'expressions**: Tester des hypothèses en temps réel

---

## Configuration requise

Les adaptateurs de débogage doivent être configurés dans `~/.mcp-languagetools/config.json`:

```json
{
  "dapServers": {
    "java-debug": {
      "id": "java-debug",
      "name": "Java Debug Server",
      "enabled": true
    },
    "vscode-js-debug": {
      "id": "vscode-js-debug",
      "name": "VSCode JS Debug",
      "enabled": true,
      "transport": "SOCKET",
      "launch": {
        "default": "node ${serverHome}/dapDebugServer.js ${port}"
      }
    }
  }
}
```

---

## Limitations connues

1. **runInTerminal**: Implémentation basique (placeholder)
2. **Breakpoints non vérifiés**: Certains adaptateurs peuvent ne pas confirmer immédiatement les breakpoints
3. **Sessions multiples**: Attention aux conflits de ports si plusieurs sessions utilisent le même adaptateur

---

## Prochaines étapes

- [ ] Ajouter des templates pré-configurés pour les langages populaires
- [ ] Améliorer `runInTerminal` pour une vraie intégration terminal
- [ ] Ajouter support pour les watchpoints et logpoints
- [ ] Documentation des configurations spécifiques par langage
