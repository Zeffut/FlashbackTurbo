# Télémétrie PostHog (design)

Date: 2026-06-03
Status: design approved, pending implementation

## Problème

FlashbackTurbo est un addon Fabric distribué publiquement (Modrinth). On n'a aujourd'hui
aucune visibilité sur l'usage réel : quels formats d'export sont utilisés, quelles
résolutions/framerates, à quelle fréquence chaque hook (H1/H8/H9/H10) se déclenche, quels
encoders, et — surtout — quels exports échouent et pourquoi. Sans données terrain, le
roadmap d'amélioration repose sur des suppositions.

## Objectif

Instrumenter le mod avec un tracking PostHog le plus complet possible : lifecycle, pipeline
d'export (start/finish/fail/cancel), métriques de performance, et déclenchement de chaque
hook — pour piloter les priorités d'amélioration sur des données réelles.

## Non-goals

- UI de consentement / écran opt-in (décision : toujours actif + anonyme, voir Confidentialité).
- Session recording, autocapture, feature flags, ou tout autre produit PostHog hors `capture`.
- Tracking de données identifiantes (jamais de pseudo, IP, chemin, nom de monde, IP serveur).
- Projet PostHog dédié (décision : on reste sur « Default project », isolation par préfixe `fbt_`).

## Décisions arrêtées (brainstorming)

| Décision | Choix |
|----------|-------|
| Modèle de consentement | Toujours actif, strictement anonyme + kill-switch config |
| Intégration | SDK officiel `posthog-java` **shadé + relocalisé** dans le jar |
| Host | EU Cloud — `https://eu.i.posthog.com` |
| Projet PostHog | « Default project » (id 192659), org « Zeffut's Saas » |
| Clé d'ingestion | `phc_zdMj4p5wo8EvfVApjb2EbfUHJ76zgYGM5wAGz5YJC359` (clé publique projet, OK en clair) |
| Isolation | Tous les events préfixés `fbt_` (le projet héberge aussi « Esiee-Paris-Salles ») |

## Approche

Embarquer le SDK `com.posthog.java:posthog` dans le jar via le **Gradle Shadow plugin**, avec
**relocation** du package `com.posthog` → `fr.zeffut.flashbackturbo.shadow.posthog` pour éviter
tout conflit de classloader avec un autre mod qui shaderait la même lib. Le SDK gère le
batching, les retries et l'envoi asynchrone en arrière-plan ; on ne fait jamais d'I/O réseau
sur le main thread.

Une façade statique `Telemetry` centralise toute la logique. **Règle d'or : la télémétrie ne
doit JAMAIS faire échouer un export.** Chaque méthode publique est wrappée en try/catch qui
avale + log en `debug`, jamais ne propage.

### Build (build.gradle)

- Ajouter le plugin `id 'com.gradleup.shadow' version '8.3.x'` (compatible Loom 1.16 / Gradle).
- Dépendance : `implementation "com.posthog.java:posthog:1.+"` (figer la version exacte au moment
  de l'implémentation après vérification des deps transitives).
- Tâche `shadowJar` : relocate `com.posthog` → `fr.zeffut.flashbackturbo.shadow.posthog` ;
  relocaliser aussi les deps transitives du SDK (JSON lib, HTTP client) sous le même namespace.
- Faire passer le `remapJar` de Loom sur la sortie du `shadowJar` (configurer
  `remapJar.dependsOn shadowJar` et l'input), pour que le jar final remappé contienne bien la lib
  shadée. Détail d'intégration Loom×Shadow à valider en implémentation (point de risque connu).
- Vérifier que la lib shadée n'est PAS embarquée via `include` (JIJ) — on veut du vrai shading
  relocalisé, pas un jar-in-jar.

### Composants (unités isolées)

#### 1. `fr.zeffut.flashbackturbo.telemetry.Telemetry` (façade statique)

API publique consommée par le reste du mod :

- `static void init()` — appelée depuis `FlashbackTurboClient.onInitializeClient()`. No-op total si
  `cfg.enableTelemetry == false`. Sinon : construit le client PostHog
  (`new PostHog.Builder(API_KEY).host("https://eu.i.posthog.com").build()`), résout le
  `distinct_id` via `AnonymousId`, calcule les super properties via `DeviceProfile`, enregistre un
  `Runtime.getRuntime().addShutdownHook(...)` qui appelle `shutdown()`, puis émet `fbt_mod_loaded`.
- `static void capture(String event, Map<String,Object> props)` — fusionne les super properties +
  `props`, appelle `posthog.capture(distinctId, event, merged)`. Tout en try/catch fail-safe.
- `static void shutdown()` — `posthog.shutdown()` (flush la queue). Idempotent, fail-safe.
- Champs : client PostHog (nullable si désactivé/échec init), `distinctId`, `superProps`.

Constantes : `API_KEY`, `HOST`, et le préfixe `fbt_` appliqué dans `capture` (les call-sites
passent le nom court, ou le nom complet — décision : call-sites passent le nom complet `fbt_*`
pour la grepabilité).

#### 2. `fr.zeffut.flashbackturbo.telemetry.DeviceProfile`

Collecte one-shot (au `init`) des propriétés système attachées à chaque event (super props) :

- `os` (`os.name`), `os_version`, `arch` (`os.arch`)
- `cpu_cores` (`Runtime.availableProcessors()`)
- `max_heap_mb` (`Runtime.maxMemory()` / 1MB)
- `java_version` (`java.version`)
- `mc_version`, `fabric_loader_version` — via `FabricLoader` metadata
- `flashback_version` — via `FabricLoader.getModContainer("flashback")` si présent, sinon `unknown`
- `fbt_version` — `mod_version` du mod container FBT
- `mods_count` — `FabricLoader.getAllMods().size()`
- Snapshot des 7 flags de `TurboConfig` (`liftResolutionCap`, `tuneFFmpegThreading`,
  `parallelPngWriter`, `pngCompressionLevel`, `showExportProgressOverlay`,
  `useFragmentedMp4OnHwEncoders`, `fixExportSetupRace`)

#### 3. `fr.zeffut.flashbackturbo.telemetry.AnonymousId`

- UUID aléatoire persisté dans `config/flashbackturbo_telemetry.json` (fichier séparé de la config
  principale, pour survivre à un reset de `flashbackturbo.json`).
- Si le fichier existe et contient un UUID valide → le lire. Sinon → `UUID.randomUUID()`, écrire,
  retourner. Sert de `distinct_id`. Aucune donnée identifiante n'y est jamais écrite.

#### 4. `fr.zeffut.flashbackturbo.telemetry.Sanitizer`

Fonctions de nettoyage appliquées avant envoi :

- `sanitizeMessage(String)` — strip chemins absolus, séquences ressemblant à des pseudos/IPs.
- `topFrames(Throwable, int n)` — renvoie les n premières frames de stacktrace **classes+méthodes
  seulement**, sans chemins de fichiers locaux.
- Utilisé par tous les events `fbt_*_failed`.

#### 5. Config — `TurboConfig`

Ajouter un champ :

```java
/** Kill-switch télémétrie PostHog anonyme. true = activé (défaut). */
public boolean enableTelemetry = true;
```

Mettre à jour la ligne de log d'init de `FlashbackTurboClient` pour inclure `enableTelemetry`.

### Taxonomie d'events (`fbt_*`)

**Lifecycle**

| Event | Quand | Propriétés notables |
|-------|-------|--------------------|
| `fbt_mod_loaded` | 1× au `init()` | super props uniquement |

**Pipeline d'export** (instrumenté dans `ExportJobMixin` / writers)

| Event | Quand | Propriétés |
|-------|-------|-----------|
| `fbt_export_started` | début d'un export | `format`, `encoder`, `width`, `height`, `framerate`, `frame_count` ou `duration_ticks`, `ssaa`, + snapshot des flags |
| `fbt_export_finished` | export terminé OK | `duration_ms`, `frames_written`, `avg_encode_fps`, `output_size_bytes` (si lisible), `success=true` |
| `fbt_export_failed` | exception pendant l'export | `exception_class`, `message` (sanitisé), `phase` (`setup`/`encode`/`finish`), `top_frames` |
| `fbt_export_cancelled` | annulation utilisateur | `phase`, `frames_done` |

**Hooks spécifiques** (mesurer l'impact réel de chaque H)

| Event | Hook | Propriétés |
|-------|------|-----------|
| `fbt_setup_race_recovered` | H10 | `ticks_pumped`, `wait_ms`, `timeout_hit` |
| `fbt_resolution_cap_lifted` | H1 | `width`, `height` (déclenché si > 4K) |
| `fbt_fragmented_mp4_used` | H9 | `encoder`, `estimated_finalize_ms_saved` |
| `fbt_saving_overlay_shown` | H8 | `shown_ms` |
| `fbt_parallel_png_used` | PNG | `threads`, `frames`, `throughput_fps` |

Note : `fbt_export_progress` volontairement **exclu** (bruyant, peu actionnable). Les métriques de
perf agrégées vivent dans `fbt_export_finished`.

### Points d'instrumentation (call-sites dans les mixins existants)

- `ExportJobMixin` (`setup`/run) → `fbt_export_started`, `fbt_export_finished`,
  `fbt_export_failed`, `fbt_export_cancelled`, `fbt_setup_race_recovered`.
- `AsyncFFmpegVideoWriterMixin` (`<init>`) → infos encoder/résolution pour `started` ;
  `fbt_resolution_cap_lifted`, `fbt_fragmented_mp4_used`.
- `AsyncFFmpegFinishMixin` (`finish` HEAD/TAIL) → `fbt_saving_overlay_shown`, durée finalize.
- `PngSequenceVideoWriterMixin` → `fbt_parallel_png_used`.

Le partage d'état entre call-sites (ex. timestamp de début d'export pour calculer `duration_ms`)
passe par des champs statiques thread-safe dans `Telemetry` ou une petite classe
`ExportTracker` interne — choix arrêté en implémentation.

## Confidentialité

- 100 % anonyme : `distinct_id` = UUID aléatoire local, jamais de PII.
- `Sanitizer` nettoie chemins / pseudos / IPs / noms de monde dans tout payload sortant.
- `anonymize_ips` est déjà activé côté projet PostHog (vérifié).
- Kill-switch : `enableTelemetry=false` → `init()` no-op, zéro réseau, zéro fichier d'id créé.
- La clé `phc_` est une **clé publique d'ingestion** (write-only) : OK de la committer en clair.

## Robustesse (invariant critique)

La télémétrie est **strictement non-bloquante et fail-safe** :

- Toute exception interne (réseau, init SDK, sérialisation) est avalée + loggée en `debug`,
  jamais propagée.
- Aucun appel réseau synchrone sur le render/main thread (le SDK PostHog envoie en async).
- Un export doit réussir intégralement même si PostHog est down, lent, ou bloqué.
- Le client SDK nullable : si l'init échoue, `capture()` devient un no-op silencieux.

## Tests

- `AnonymousIdTest` — création + persistance + relecture de l'UUID ; UUID stable entre deux appels.
- `SanitizerTest` — un chemin absolu / pseudo / IP est bien strippé ; stacktrace réduite aux frames.
- `TelemetryTest` — `capture` ne lève jamais même si le client est null ; no-op si désactivé.
- Vérification build : `shadowJar` produit un jar avec `com.posthog` relocalisé sous
  `fr.zeffut.flashbackturbo.shadow.posthog` (inspection `jar tf`), et le mod charge en runtime
  dans l'env de test isolé (voir mémoire `feedback_testing`) sans polluer le jar de release.

## Risques connus

1. **Loom × Shadow** : faire cohabiter `remapJar` (Loom) et `shadowJar` est délicat. Point de
   vigilance n°1 ; prévoir un fallback (relocation manuelle ou JIJ) si l'intégration coince.
2. **Deps transitives de posthog-java** : à auditer (`./gradlew dependencies`) et toutes
   relocaliser, sinon conflit potentiel.
3. **Mélange de données** avec « Esiee-Paris-Salles » sur le même projet PostHog : mitigé par le
   préfixe `fbt_` mais reste sous-optimal ; envisager un projet dédié plus tard.
