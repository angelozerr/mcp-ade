# Build Support Modes : Native, Fast et BSP

MCP Language Tools supporte 3 modes de build support pour JDT.LS, configurables
indépendamment pour Maven et Gradle via les settings :

```
lsp.jdtls.settings.maven.buildSupport = native | fast
lsp.jdtls.settings.gradle.buildSupport = native | fast | bsp
```

## Vue d'ensemble

| | Native | Fast | BSP |
|---|---|---|---|
| Build tool | Maven / Gradle | Maven / Gradle | Gradle uniquement |
| Import | M2E / Buildship (dans JDT.LS) | CLI externe (`mvn dependency:build-classpath`) | Build Server Protocol |
| Scope | Tout le workspace | Un module à la demande | Un module à la demande |
| Cache disque | Non | Oui (`~/.mcp-languagetools/classpath-cache/`) | Oui |
| Temps 1er lancement (Quarkus) | 1-2 heures | ~150s | ~60s |
| Temps 2ème lancement | ~5 min | ~20s | ~15s |
| Adapté pour | IDE interactif humain | Agent IA / opération ciblée | Agent IA / opération ciblée |

## Mode Native

Le mode par défaut. JDT.LS utilise ses importeurs intégrés :
- **Maven** : M2E (`MavenProjectImporter`, order=400)
- **Gradle** : Buildship (`GradleProjectImporter`, order=300)

### Fonctionnement

```
Démarrage JDT.LS
─────────────────
1. initialize → M2E/Buildship scanne le workspace
2. Import de TOUS les modules (pom.xml / build.gradle trouvés)
3. Résolution Maven/Gradle complète pour chaque module
4. Build de tous les projets
5. Indexation JDT de toutes les classes
```

### Pourquoi c'est lent

Sur un projet comme Quarkus (~1400 modules), M2E crée un projet Eclipse pour
chaque `pom.xml` et exécute `readMavenProject()` sur chacun. C'est un import
**exhaustif** : tout-ou-rien. Il n'y a pas de mode "un seul module à la demande".

## Mode Fast

Le mode fast inverse l'approche : **aucun import au démarrage, tout à la demande**.

### Principe

L'extraction du classpath est faite **en dehors de JDT.LS**, par le serveur MCP :
- **Maven** : `mvn dependency:build-classpath` (processus externe)
- **Gradle** : le même principe via les APIs Gradle

Le résultat est écrit sous forme de **descripteurs JSON** dans le répertoire
de données JDT.LS (`<dataDir>/mcp-classpath/<projectName>.json`). Deux
extensions JDT.LS consomment ces descripteurs :

- **McpProjectImporter** (`IProjectImporter`, order=10) — s'exécute pendant
  `initialize`, avant M2E (400) et Buildship (300). Quand des descripteurs
  existent, bloque les importeurs natifs via `isResolved()=true`. Crée les
  projets Eclipse avec Java nature et configure les builders.
- **McpBuildSupport** (`IBuildSupport`, order=50) — lit le descripteur JSON
  et appelle `setRawClasspath()` pour configurer source roots, JRE, project
  references et library JARs.

### Flow : 1er lancement (cold start)

```
initialize (60s)                         Après ServiceReady
──────────────────                       ────────────────────
1. McpProjectImporter s'active           1. Premier outil déclenche ensureModuleSetup()
2. Pas de descriptors → skip             2. Extraction classpath via Maven CLI (~90s)
3. M2E/Gradle désactivés via settings    3. Écriture descripteurs JSON (reactor + main)
4. JDT.LS démarre sans projets           4. Appel java.project.import (~50ms)
                                         5. McpProjectImporter crée les projets
                                         6. Sauvegarde cache disque
                                         7. Module prêt → outils disponibles
```

### Flow : 2ème lancement (warm cache)

```
Avant initialize                         Après ServiceReady
──────────────────                       ────────────────────
1. Cache valide → écriture descripteurs  1. ensureModuleSetup() détecte cache + descriptor
2. Création répertoire mcp-classpath/    2. Skip java.project.import (rien n'a changé)
                                         3. Module prêt en ~150ms
initialize (10s)
──────────────────
1. McpProjectImporter trouve descriptors
2. Création projets + classpath
3. Indexation démarre immédiatement
```

### Descripteur JSON

Chaque fichier `<dataDir>/mcp-classpath/<projectName>.json` contient :

```json
{
  "projectName": "quarkus-awt",
  "projectPath": "C:/Users/.../quarkus/extensions/awt/runtime",
  "sourceRoots": ["src/main/java", "src/test/java"],
  "classpathJars": ["~/.m2/repository/.../quarkus-core-999-SNAPSHOT.jar", ...],
  "projectReferences": ["arc", "quarkus-core"],
  "disableBuilders": false
}
```

- **projectReferences** : noms des projets reactor (dépendances intra-workspace)
- **disableBuilders** : `true` pour les reactor modules (projets source-only,
  pas de diagnostics JDT ni de compilation — évite le bruit sur les modules non ciblés)

### Two-pass import

McpProjectImporter utilise une approche en deux passes pour gérer les
project references :

1. **Pass 1** : créer/ouvrir tous les projets avec Java nature
2. **Pass 2** : configurer builders et classpath (`setRawClasspath`)

Cet ordre garantit que les project references (dépendances reactor) sont
résolubles lors de la configuration du classpath, quel que soit l'ordre de
traitement des descripteurs.

### Cache classpath

Le cache disque (`~/.mcp-languagetools/classpath-cache/`) stocke :
- Le `ClasspathInfo` complet (source roots, JARs, reactor deps)
- Les timestamps des fichiers build (pom.xml, build.gradle)

Invalidation automatique si :
- Un pom.xml/build.gradle a changé (timestamp différent)
- Un JAR référencé n'existe plus (ex: `~/.m2/repository` nettoyé)

### Optimisation `force=false`

`McpBuildSupport.update(project, force=false, monitor)` compare le classpath
actuel avec le nouveau. Si identique, `setRawClasspath()` est ignoré — évite
la ré-indexation lors d'appels répétés à `java.project.import` (ex: preloading
de modules siblings).

## Mode BSP (Build Server Protocol)

Le mode BSP est une variante du mode fast, spécifique à Gradle. Au lieu
d'extraire le classpath via un processus CLI externe, il utilise le
**Build Server Protocol** pour communiquer avec un serveur Gradle BSP.

### Différences avec Fast

| | Fast (Maven CLI) | BSP (Gradle) |
|---|---|---|
| Extraction | Processus `mvn` externe | Connexion BSP persistante |
| Communication | Stdout (fichier temporaire) | JSON-RPC (LSP-like) |
| Latence | Nouveau processus à chaque fois | Serveur persistant, requêtes rapides |
| Build tool | Maven et Gradle | Gradle uniquement |

### Fonctionnement

```
1. MCP server démarre le BSP server Gradle (si pas déjà running)
2. Requête BSP buildTarget/dependencySources pour le module ciblé
3. Réponse → ClasspathInfo
4. Écriture du même format de descripteur JSON que le mode fast
5. McpProjectImporter/McpBuildSupport traitent les descripteurs identiquement
```

Le BSP server reste actif entre les requêtes, ce qui rend les extractions
suivantes beaucoup plus rapides qu'un nouveau processus Maven CLI.

### Architecture commune

Les 3 modes convergent vers le même point d'entrée côté JDT.LS :

```
                    ┌─────────────────┐
                    │  McpBuildSupport │ ← IBuildSupport (order=50)
                    │  setRawClasspath │
                    └────────┬────────┘
                             │ lit
                    ┌────────┴────────┐
                    │  Descripteur    │ ← <dataDir>/mcp-classpath/*.json
                    │  JSON           │
                    └────────┬────────┘
                             │ écrit par
              ┌──────────────┼──────────────┐
              │              │              │
     ┌────────┴───────┐ ┌───┴────┐ ┌───────┴──────┐
     │ Maven CLI      │ │ Gradle │ │ Gradle BSP   │
     │ (fast mode)    │ │ (fast) │ │ (bsp mode)   │
     │ dependency:    │ │        │ │ buildTarget/  │
     │ build-classpath│ │        │ │ dependencies  │
     └────────────────┘ └────────┘ └──────────────┘
```

McpProjectImporter et McpBuildSupport sont **build-tool agnostiques** — ils
ne lisent que du JSON. La logique spécifique au build tool vit dans les
extensions MCP (`extensions/java`), à travers les interfaces `BuildSupport`
(synchrone, pour Maven/Gradle CLI) et `BspBuildSupport` (asynchrone, pour BSP).

## Pourquoi c'est possible pour un agent IA

Un agent IA travaille différemment d'un développeur dans un IDE :

- **Développeur** : ouvre un workspace, navigue librement → besoin que **tout**
  soit indexé et prêt
- **Agent IA** : reçoit un chemin de fichier, exécute une opération → besoin
  d'**un seul module** prêt

Le mode fast/BSP exploite cette différence : au lieu de préparer tout le
workspace "au cas où", il prépare uniquement ce qui est demandé, quand
c'est demandé. Avec le preloading des siblings, les modules voisins sont
configurés en arrière-plan après le premier appel.

## Résumé des performances

Mesures sur Quarkus (`extensions/awt/runtime`, module simple, 43 JARs) :

| Étape | Native (M2E) | Fast 1er | Fast 2ème |
|---|---|---|---|
| `initialize` | 60s | 61s | **10s** |
| Import/Résolution | 300-600s | 91s (Maven CLI) | **150ms** (cache) |
| Setup projet | inclus | **48ms** (`java.project.import`) | skip |
| `validateLaunchConfig` | 0s (tout prêt) | 96ms (indexation en cours) | **6s** |
| **Total** | **1-2 heures** | **~152s** | **~21s** |

Le gain entre 1er et 2ème lancement (7x) vient principalement du cache
classpath (0s Maven) et du setup projet pendant `initialize` (McpProjectImporter
lit les descripteurs écrits depuis le cache avant le démarrage de JDT.LS).
