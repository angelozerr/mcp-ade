# Améliorations des outils MCP pour le débogage DAP

## Résumé

Les outils MCP DAP ont été améliorés pour faciliter leur utilisation par un AI client, avec un focus sur la découvrabilité des configurations et la clarté des paramètres.

## ✅ Améliorations implémentées

### 1. Nouvel outil: `get_launch_template`

**Problème**: L'AI ne savait pas quels paramètres passer pour lancer un programme dans un langage donné.

**Solution**: Nouveau tool qui retourne un template complet avec tous les paramètres disponibles.

**Exemple d'utilisation:**

```javascript
// 1. Demander le template pour JavaScript
get_launch_template({language: "javascript"})

// Retour:
{
  "type": "node",
  "request": "launch",
  "name": "Launch Program",
  "program": "${workspaceFolder}/index.js",
  "skipFiles": ["<node_internals>/**"],
  "console": "integratedTerminal",
  "_optional": {
    "args": ["--optional-arg"],
    "env": {"NODE_ENV": "development"},
    "cwd": "${workspaceFolder}",
    "runtimeArgs": ["--nolazy"],
    "port": 9229
  },
  "_description": "Node.js/JavaScript debugging configuration"
}
```

**Langages supportés:**
- `javascript` / `typescript` / `node`
- `java`
- `python`
- `go`

Chaque template inclut:
- Les paramètres **requis** (type, request, program/mainClass, etc.)
- Les paramètres **optionnels** dans `_optional`
- Une **description** explicative

---

### 2. Nouvel outil: `validate_launch_config`

**Problème**: Impossible de valider une configuration avant de lancer le débogage, risquant des erreurs au runtime.

**Solution**: Outil de validation qui vérifie les champs requis et types.

**Exemple d'utilisation:**

```javascript
// Valider une config Java
validate_launch_config({
  sessionId: "java-123",
  launchConfiguration: {
    "type": "java",
    "request": "launch",
    "mainClass": "com.example.Main"
    // projectName manquant
  }
})

// Retour:
{
  "valid": true,
  "errors": [],
  "warnings": [
    "Recommended: specify 'projectName' for Java projects"
  ],
  "configuration": {...}
}
```

**Validation effectuée:**
- Champs requis selon le type de configuration
- Paramètres spécifiques au langage
- Recommandations (warnings) pour les bonnes pratiques

---

### 3. Nouvel outil: `launch_program`

**Problème**: `start_debugging` utilisait `scriptPath` qui n'avait pas le même sens selon les langages.

**Solution**: Nouvel outil acceptant une configuration DAP complète et structurée.

**Exemple d'utilisation:**

```javascript
// Pour JavaScript
launch_program({
  sessionId: "js-123",
  launchConfiguration: {
    "type": "node",
    "request": "launch",
    "program": "${workspaceFolder}/app.js",
    "args": ["--port", "3000"],
    "env": {"DEBUG": "true"}
  }
})

// Pour Java
launch_program({
  sessionId: "java-456",
  launchConfiguration: {
    "type": "java",
    "request": "launch",
    "mainClass": "com.example.Main",
    "projectName": "my-app",
    "vmArgs": "-Xmx512m"
  }
})
```

**Avantages:**
- Configuration **explicite** et **typée**
- Pas d'ambiguïté sur les paramètres
- Compatible avec tous les adaptateurs DAP

---

### 4. Amélioration: Documentation des chemins de fichiers

**Problème**: Ambiguïté sur le format des chemins (absolu vs relatif).

**Solution**: Documentation clarifiée dans toutes les descriptions d'outils.

**Outils mis à jour:**
- `set_breakpoint`: "File path should be absolute or relative to workspace root"
- `start_debugging`: "scriptPath should be absolute or relative to workspace root" + marqué DEPRECATED

---

## 📊 Avant / Après

### Avant

```javascript
// ❌ Pas clair comment lancer un programme Java
start_debugging({
  sessionId: "java-123",
  scriptPath: "???",  // Quoi mettre ici pour Java?
  additionalArgs: {
    // Quels paramètres sont disponibles?
  }
})
```

### Après

```javascript
// ✅ Workflow clair et guidé

// 1. Obtenir le template
const template = get_launch_template({language: "java"})
// Retourne tous les paramètres disponibles

// 2. Construire la config
const config = {
  type: "java",
  request: "launch",
  mainClass: "com.example.Main",
  projectName: "my-app"
}

// 3. Valider (optionnel mais recommandé)
const validation = validate_launch_config({
  sessionId: "java-123",
  launchConfiguration: config
})

if (!validation.valid) {
  console.log("Errors:", validation.errors)
  return
}

// 4. Lancer
launch_program({
  sessionId: "java-123",
  launchConfiguration: config
})
```

---

## 🎯 Workflow recommandé pour l'AI

### Scénario: "Debug a Java application"

```
User: "Debug Main.java in my project"

AI Agent:
1. create_debug_session({language: "java", ...})
   → sessionId: "java-123"

2. get_launch_template({language: "java"})
   → Découvre qu'il faut: type, mainClass, projectName

3. Construit la config:
   {
     "type": "java",
     "request": "launch",
     "mainClass": "com.example.Main",
     "projectName": "detected-from-workspace"
   }

4. validate_launch_config(...)
   → Vérifie que tout est OK

5. set_breakpoint({file: "Main.java", line: 25})
   → Place le breakpoint

6. launch_program({launchConfiguration: ...})
   → Lance le débogage

7. Quand arrêté: get_stack_trace(), get_local_variables(), etc.
```

---

## 🔧 Compatibilité

### Rétro-compatibilité

L'ancien outil `start_debugging` est **conservé** mais marqué **DEPRECATED**.

```java
@Tool(description = "DEPRECATED: Use launch_program() instead. ...")
public Map<String, Object> start_debugging(...)
```

Les AI clients existants continuent de fonctionner, mais sont encouragés à migrer vers `launch_program`.

---

## 📝 Liste complète des outils DAP

### Gestion de sessions (4 outils)
- `create_debug_session` - Créer une session
- `list_debug_sessions` - Lister les sessions actives
- `list_supported_languages` - Langages disponibles
- `close_debug_session` - Fermer une session

### Configuration (3 outils) ✨ NOUVEAU
- `get_launch_template` - Obtenir un template de config
- `validate_launch_config` - Valider une config
- `launch_program` - Lancer avec config complète

### Cycle de vie (4 outils)
- `start_debugging` - ⚠️ DEPRECATED, utiliser `launch_program`
- `attach_to_process` - Attacher à un processus
- `detach_from_process` - Détacher
- `close_debug_session` - Terminer

### Breakpoints (3 outils)
- `set_breakpoint` - Placer un breakpoint
- `remove_breakpoint` - Supprimer un breakpoint
- `list_all_breakpoints` - Lister tous les breakpoints

### Contrôle d'exécution (5 outils)
- `continue_execution` - Continuer
- `pause_execution` - Pause
- `step_over` - Pas à pas (suivant)
- `step_in` - Entrer dans fonction
- `step_out` - Sortir de fonction

### Inspection (7 outils)
- `get_stack_trace` - Pile d'appels
- `list_threads` - Lister les threads
- `get_scopes` - Obtenir les scopes
- `get_variables` - Variables d'un scope
- `get_local_variables` - Variables locales (raccourci)
- `evaluate_expression` - Évaluer une expression
- `get_debug_statistics` - Statistiques

**Total: 26 outils** (dont 3 nouveaux)

---

## ✅ Tests recommandés

### Test 1: Template JavaScript
```javascript
get_launch_template({language: "javascript"})
// Doit retourner un template Node.js complet
```

### Test 2: Validation Java
```javascript
validate_launch_config({
  sessionId: "test",
  launchConfiguration: {
    type: "java",
    request: "launch"
    // mainClass manquant
  }
})
// Doit retourner valid=false avec l'erreur "mainClass required"
```

### Test 3: Launch complet
```javascript
const config = get_launch_template({language: "python"})
config.program = "/path/to/script.py"

validate_launch_config({sessionId: "py", launchConfiguration: config})
// valid=true

launch_program({sessionId: "py", launchConfiguration: config})
// Lance le débogage Python
```

---

## 🚀 Prochaines étapes

### Templates additionnels
- [ ] Rust (rust-analyzer + CodeLLDB)
- [ ] C/C++ (gdb/lldb)
- [ ] PHP (Xdebug)
- [ ] Ruby

### Fonctionnalités avancées
- [ ] `get_attach_template` - Templates pour attach (vs launch)
- [ ] `suggest_breakpoints` - Suggérer des emplacements de breakpoints pertinents
- [ ] `explain_launch_error` - Analyser et expliquer les erreurs de lancement
- [ ] Auto-détection du type de projet pour pré-remplir le template

---

## 📖 Documentation

- **Guide complet**: `DAP_MCP_TOOLS_GUIDE.md`
- **Exemples de config**: `DAP_CONFIGURATION_EXAMPLES.md`
- **Architecture**: `DAP_IMPROVEMENTS_SUMMARY.md`
- **Ce document**: `DAP_TOOLS_IMPROVEMENTS.md`
