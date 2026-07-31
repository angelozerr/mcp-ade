# Fast Mode vs M2E : Pourquoi le mode fast est beaucoup plus rapide

## Le problème avec M2E (mode full)

Quand JDT.LS démarre en mode classique sur un projet Maven multi-module (ex: Quarkus ~1400 modules), M2E effectue les étapes suivantes :

1. **Scan du workspace** : parcourt tout l'arbre de fichiers pour trouver les `pom.xml`
2. **Import de TOUS les modules** : crée un projet Eclipse pour chaque `pom.xml` (~1400 projets)
3. **Configuration Maven de chaque projet** : exécute `MavenProjectFacade.readMavenProject()` pour chaque module, ce qui lance une résolution Maven complète (parsing POM, résolution des dépendances, téléchargement si nécessaire)
4. **Build de tous les projets** : compile tous les modules, résout les classpath, indexe
5. **Indexation JDT** : indexe toutes les classes de tous les JARs de tous les modules

**Temps total : 1-2 heures** sur un projet comme Quarkus (~1400 modules), avec consommation mémoire élevée (risque OOM).

### Pourquoi M2E ne peut pas être configuré pour être aussi rapide

M2E est conçu comme un import **exhaustif** : il doit comprendre l'intégralité du workspace pour fonctionner. Ses options de configuration ne changent pas fondamentalement cette approche :

| Option M2E | Ce qu'elle fait | Ce qu'elle ne fait pas |
|---|---|---|
| `java.import.maven.enabled=false` | Empêche l'import de **nouveaux** projets | N'empêche pas le traitement des projets **déjà** dans le workspace |
| `java.import.exclusions=["**"]` | Exclut les dossiers du scan | Bricolage : empêche aussi les projets légitimes d'être trouvés |
| `java.autobuild.enabled=false` | Désactive la compilation auto | M2E fait quand même la configuration Maven de chaque projet |
| `skipProjectConfiguration=true` | Saute la configuration à l'init | Bon, mais ne résout pas le problème des projets résiduels |

Le problème fondamental : **M2E ne supporte pas le mode "un seul module à la demande"**. Il est tout-ou-rien :
- Soit il importe tout (lent mais complet)
- Soit il n'importe rien (rapide mais inutile)

Même avec `skipProjectConfiguration=true`, les projets existants dans le `-data` directory d'un run précédent sont toujours traités par M2E au redémarrage ("Updating X configuration"), ce qui ajoute ~40s d'overhead sur un run précédemment utilisé.

## L'approche Fast Mode

Le mode fast prend l'approche inverse : **aucun import au démarrage, tout à la demande**.

### Architecture

```
Démarrage (fast mode)                    Premier appel outil (ex: diagnostics sur arc-processor)
─────────────────────                    ──────────────────────────────────────────────────────
1. Nettoyage projets résiduels (<1s)     1. Détection du module (pom.xml) (<1ms)
2. skipProjectConfiguration=true         2. Extraction classpath Maven (~30s 1er run, 0s cache)
3. M2E/Gradle import disabled            3. Setup des reactor modules comme projets source (~2s)
4. JDT.LS démarre en ~5s                 4. Setup du module cible (setupProject) (~1s)
5. ServiceReady immédiat                 5. Build du module cible (buildProject) (~10s)
                                         6. Module prêt → diagnostics disponibles
```

### Comparaison des temps

| Étape | M2E (full) | Fast Mode (1er run) | Fast Mode (cache) |
|---|---|---|---|
| Démarrage JDT.LS | 5s | 5s | 5s |
| Import/Scan | 300-600s (tous modules) | 0s (aucun scan) | 0s |
| Configuration Maven | 600-3600s (tous modules) | 30s (1 module ciblé) | 0s (depuis cache) |
| Setup projets | inclus ci-dessus | 3s (module + reactor deps) | 3s |
| Build | 600-3600s (tous modules) | 10s (1 module ciblé) | 10s |
| **Total** | **1-2 heures** | **~48s** | **~18s** |

### Pourquoi c'est possible pour un agent IA

Un agent IA (Claude, etc.) travaille fondamentalement différemment d'un développeur humain dans un IDE :

- **Un développeur** ouvre un workspace et navigue librement entre les fichiers → il a besoin que **tout** soit indexé et prêt
- **Un agent IA** reçoit un chemin de fichier précis et exécute une opération dessus → il a besoin d'**un seul module** prêt

Le mode fast exploite cette différence : au lieu de préparer tout le workspace "au cas où", il prépare uniquement ce qui est demandé, quand c'est demandé.

## Les optimisations clés

### 1. Nettoyage des projets résiduels

Le `-data` directory d'Eclipse persiste entre les redémarrages. Les projets créés lors d'un run précédent sont toujours présents. Même avec `skipProjectConfiguration=true`, M2E détecte ces projets existants et les "met à jour" (~40s d'overhead).

**Solution** : nettoyer `.metadata/.plugins/org.eclipse.core.resources/.projects/` et `.snap` avant de démarrer JDT.LS en fast mode. Résultat : 0 projets au démarrage = 0s d'overhead M2E.

Les index JDT (dans `.metadata/.plugins/org.eclipse.jdt.core/`) sont préservés — pas besoin de ré-indexer les JARs.

### 2. `skipProjectConfiguration=true`

Capacité native de JDT.LS (vérifié dans `ProjectsManager.java` ligne 116) qui empêche le scan et l'import de nouveaux projets au démarrage.

### 3. Cache classpath sur disque

En mode `fast+cache`, le classpath extrait de Maven est sauvegardé sur disque. Au prochain démarrage :
- Pas d'appel Maven (0s au lieu de 30s)
- Le projet doit quand même être créé dans le workspace (setupProject + buildProject ~13s)

### 4. Reactor modules comme projets source

Les dépendances intra-workspace (reactor modules Maven) sont créées comme projets source JDT (sans builders, sans résolution Maven). Cela permet la navigation cross-module et la résolution de types sans coût.

## Résumé

| | M2E (full) | Fast Mode |
|---|---|---|
| Philosophie | Tout préparer à l'avance | Préparer à la demande |
| Adapté pour | IDE interactif humain | Agent IA / opération ciblée |
| Projets créés au démarrage | Tous (~1400) | 0 |
| Projets créés à l'usage | 0 | 1 + ses reactor deps (~7) |
| Risque OOM | Élevé (gros projets) | Faible |
| Temps au 1er outil | 0s (tout est prêt) | ~48s (setup à la demande) |
| Temps total démarrage → prêt | 1-2 heures | 5s (ServiceReady) |
