# DAP Console Output - Documentation

## Vue d'ensemble

Les tools DAP incluent maintenant automatiquement l'output console du programme debuggé (stdout/stderr/console.log) dans leurs réponses. Cela permet à l'agent IA de voir ce que le programme affiche sans avoir à le relancer manuellement.

## Architecture

### DapProgramOutput

Classe dédiée qui capture et bufferise l'output du programme :

```java
public class DapProgramOutput {
    // Buffer circulaire de 200 lignes max
    // Stocke category (stdout/stderr/console) + texte
}
```

**Fonctionnalités** :
- Buffer limité à 200 lignes (gestion mémoire)
- Préserve la catégorie DAP (stdout, stderr, console, etc.)
- Thread-safe (CopyOnWriteArrayList)
- Format avec catégories : `[stderr] error\n` ou texte brut

### Intégration dans DapSession

Chaque session DAP a son propre buffer :

```java
public class DapSession {
    private final DapProgramOutput programOutput;
}
```

**Capture automatique** :
- Événement DAP `output` → `DapSession.onOutput()` → `programOutput.addOutput(event)`
- Filtrage : les outputs "telemetry" sont ignorés

### Inclusion dans les réponses

Les tools d'exécution incluent automatiquement l'output :

**Tools concernés** :
- `continue_execution(sessionId)` - inclut automatiquement l'output
- `step_over(sessionId)` - inclut automatiquement l'output
- `step_in(sessionId)` - inclut automatiquement l'output
- `step_out(sessionId)` - inclut automatiquement l'output
- `get_console_output(sessionId)` - **nouveau tool dédié** pour récupérer uniquement l'output

**Format de réponse** :

```json
{
  "success": true,
  "consoleOutput": "=== Test ===\nScore: 100\n[stderr] Warning!\n",
  "outputLines": 3
}
```

**Si pas d'output** :
```json
{
  "success": true
}
```

## Comportement

### Accumulation

L'output s'accumule dans le buffer au fur et à mesure que le programme s'exécute :

1. Programme fait `console.log("Hello")`
2. Événement DAP `output` reçu
3. Ajouté au buffer
4. Prochain tool retourne tout le buffer (max 200 lignes)

### Catégories DAP

Les catégories suivantes sont capturées :

| Catégorie | Description | Format |
|-----------|-------------|--------|
| `stdout` | Sortie standard | Texte brut (pas de préfixe) |
| `stderr` | Erreurs standard | `[stderr] texte` |
| `console` | Console.log | `[console] texte` |
| `telemetry` | Metrics DAP | **Ignoré** |

### Limites

- **200 lignes max** : Buffer circulaire, les anciennes lignes sont supprimées
- **Par session** : Chaque session DAP a son propre buffer indépendant
- **Pas de persistance** : Le buffer est vidé quand la session est fermée

## Exemple d'utilisation pour l'agent IA

**Programme debuggé** :
```javascript
function test() {
    console.log("=== Début du test ===");
    console.log("Score:", 100);
    console.error("Attention: bug potentiel");
    let x = 42;
}
```

**Appel de l'agent** :
```javascript
continue_execution(sessionId)
```

**Réponse** :
```json
{
  "success": true,
  "allThreadsContinued": true,
  "consoleOutput": "=== Début du test ===\nScore: 100\n[stderr] Attention: bug potentiel\n",
  "outputLines": 3
}
```

**Avantage** : L'agent voit immédiatement ce qui se passe dans le programme sans appel supplémentaire.

### Tool dédié : `get_console_output`

Si l'agent veut **uniquement** consulter l'output console sans faire d'opération d'exécution :

```javascript
get_console_output(sessionId)
```

**Réponse avec output** :
```json
{
  "success": true,
  "output": "=== Début du test ===\nScore: 100\n[stderr] Attention: bug potentiel\n",
  "lines": 3
}
```

**Réponse sans output** :
```json
{
  "success": true,
  "output": "",
  "lines": 0,
  "message": "No console output yet"
}
```

**Cas d'usage** :
- L'agent veut vérifier si le programme a affiché quelque chose
- Consultation de l'output sans step/continue
- Vérification des logs avant de continuer

## Méthode helper interne

Une méthode privée évite la duplication de code :

```java
private Map<String, Object> createResultWithOutput(Map<String, Object> baseResult) {
    Map<String, Object> result = new HashMap<>(baseResult);
    
    if (programOutput.hasOutput()) {
        result.put("consoleOutput", programOutput.getAllWithCategories());
        result.put("outputLines", programOutput.getLineCount());
    }
    
    return result;
}
```

Utilisée par tous les tools d'exécution pour ajouter automatiquement l'output.

## Améliorations futures possibles

1. **Paramètre limit** : `get_console_output(sessionId, limit=50)` pour limiter le nombre de lignes retournées
2. **Curseur de lecture** : Marquer ce qui a déjà été lu pour ne retourner que le "nouveau" output
3. **Filtrage par catégorie** : `get_console_output(sessionId, category="stderr")` pour récupérer seulement les erreurs
4. **Timestamps** : Ajouter l'heure de chaque ligne d'output
5. **Clear output** : `clear_console_output(sessionId)` pour vider le buffer manuellement

## Implémentation

**Fichiers modifiés** :
- `DapProgramOutput.java` - Nouvelle classe pour le buffer
- `DapSession.java` - Intégration + capture dans `onOutput()` + helper `createResultWithOutput()`
- `DapDebugTools.java` - Simplification des tools step* (délégation au résultat de session)

**Tests** :
- Lancer un programme avec `console.log()` via DAP
- Appeler `continue_execution()` ou `step_over()`
- Vérifier que `consoleOutput` apparaît dans la réponse JSON
