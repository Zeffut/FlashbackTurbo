# H11 — Promotion software → hardware encode (« fast software encode »)

Date : 2026-06-10
Statut : design validé (mode autonome), prêt pour writing-plans

## Problème (constaté en télémétrie, vrais users)

~13 % des exports Windows passent par `libopenh264` (encodeur software). Médiane **54,7 s**,
sur des machines à **6 cœurs (4–12)** — donc pas des configs faibles, mais un encodeur
quasi mono-thread qui laisse le CPU inexploité.

Comparaison (export terminé, médiane) :

| Encodeur | Type | Exports | Cœurs méd. | Durée méd. |
|---|---|---|---|---|
| h264_nvenc | NVIDIA HW | 217 | — | 8,5 s |
| h264_qsv | Intel HW | 35 | 8 | 25,4 s |
| **libopenh264** | **software** | **38** | **6** | **54,7 s** |
| h264_mf | Media Foundation | 2 | 4 | 95,3 s |

## Contrainte décisive : pas de libx264

Le FFmpeg embarqué par Flashback est `org.bytedeco:ffmpeg 6.1.1-1.5.10` en **build LGPL**.
Encodeurs H.264 réellement compilés (vérifié par `strings` sur `avcodec-60.dll` Windows) :
`libopenh264`, `h264_nvenc`, `h264_qsv`, `h264_mf`. **Aucun `libx264` / `h264_amf`.**

→ Impossible de basculer software vers un meilleur encodeur software. Le seul gain réel =
**promouvoir vers un encodeur matériel** (`nvenc`/`qsv`) déjà compilé mais non sélectionné par
Flashback (l'utilisateur a choisi « H.264 » générique → Flashback retombe sur openh264).

## Objectif

Quand Flashback s'apprête à encoder en `libopenh264`, rediriger vers le meilleur encodeur HW
disponible au runtime, sans perte de qualité visuelle (SSIM ≥ 0,99) et sans jamais casser
l'export. Gain attendu : **55 s → ~9–25 s** selon le GPU.

## Architecture

Trois unités, toutes opt-in et fail-safe.

### 1. `HwEncoderProbe` (nouvelle classe `encoder/HwEncoderProbe.java`)

Responsabilité unique : déterminer, une fois par session, quel encodeur HW H.264 est
**réellement utilisable** (compilé ET GPU/driver présents).

- API : `static Optional<String> bestH264Hardware()` — mémoïsé (résultat caché en `volatile`).
- Méthode : pour chaque candidat dans l'ordre de priorité `["h264_nvenc", "h264_qsv"]`,
  instancier un `FFmpegFrameRecorder` jetable (64×64, 1 frame, format `mp4`, sortie vers un
  fichier temporaire `Files.createTempFile`, supprimé après — plus portable que `NUL`/`/dev/null`
  dont le muxing MP4 est capricieux), appeler `start()`, encoder une frame noire, puis
  `stop()/release()`.
  Le premier qui démarre sans exception est retenu.
- `h264_mf` est **exclu** des candidats (mesuré 95 s > openh264). AMD-only (ni nvenc ni qsv) →
  aucun résultat, on reste software.
- Tout échec (exception, classe absente) → candidat ignoré. Aucune exception ne sort de la classe.
- Le probe ne tourne qu'une fois et seulement si la promotion est activée et qu'un export
  software est sur le point de démarrer (lazy).

### 2. Promotion dans `AsyncFFmpegVideoWriterMixin` (hook existant H6)

Le `@Redirect` sur `recorder.start()` (`flashbackturbo$tuneRecorderBeforeStart`) est étendu :

```
encoder = recorder.getVideoCodecName()
si config.promoteSoftwareToHardwareEncode ET encoder == "libopenh264" :
    HwEncoderProbe.bestH264Hardware().ifPresent(hw -> {
        recorder.setVideoCodecName(hw)   // ex. "h264_nvenc"
        promotedFrom = "libopenh264"; promotedTo = hw
    })
EncoderTuning.applyThreadingTunes(recorder)   // s'applique ensuite à l'encodeur final
recorder.start()
```

- `EncoderTuning` voit alors l'encodeur HW promu et applique ses tunes (`delay=0` nvenc,
  `async_depth` qsv) + H9 fragmented MP4 (cohérent, c'est un HW encoder).
- **Fallback** : si `recorder.start()` lève après promotion (cas limite : probe OK mais start
  échoue sur ces réglages précis), on `release()`, on **remet `setVideoCodecName("libopenh264")`**,
  on ré-applique les tunes software, et on `start()` à nouveau. L'export part toujours.

### 3. `EncoderTuning` — threading openh264 (filet configs sans GPU)

Aujourd'hui le `switch` laisse `libopenh264` dans le `default` (aucun tune au-delà de
`threads=auto`). Ajouter un case explicite :

```
case "libopenh264" -> {
    // OpenH264 ne multithread pas via 'threads' seul ; 'slices' découpe la frame en
    // tranches encodables en parallèle. slices = min(cœurs, 8). Léger impact qualité
    // (frontières de tranches) → à valider SSIM ; sinon plafonner ou rendre opt-in fin.
    if (recorder.getVideoOption("slices") == null)
        tryVideoOption(recorder, "slices", Integer.toString(Math.min(8, CPU_CORES)));
}
```

Gain modeste, gratuit, pour les machines réellement sans encodeur HW.

### 4. Instrumentation télémétrie

- **`DeviceProfile.collect()`** : ajouter `gpu_vendor` et `gpu_renderer` lus depuis le contexte
  GL de Minecraft (`GL11.glGetString(GL_VENDOR)` / `GL_RENDERER`) via `RenderSystem`.
  Best-effort : si pas de contexte GL accessible au moment de la collecte, omettre.
  → permet de mesurer quel % des users `libopenh264` ont un GPU promouvable (NVIDIA/Intel).
- **`fbt_export_started`** : nouvelles propriétés `encoder_promoted_from`, `encoder_promoted_to`
  (absentes si pas de promotion) renseignées via `ExportContext` depuis le mixin.
- **Nouvel event** `fbt_hw_promotion_probe` (one-shot par session) : `{ probed: [...],
  selected: "h264_nvenc"|null, probe_ms }` pour suivre la couverture et le coût du probe.

### 5. Config

Nouveau flag dans `TurboConfig` :

```java
/** H11 : si Flashback encode en libopenh264 (software) mais qu'un encodeur HW
 *  (nvenc/qsv) est utilisable, redirige vers lui. ~4–6× plus rapide, qualité ≥. */
public boolean promoteSoftwareToHardwareEncode = true;
```

Et le flag correspondant `cfg_promoteSoftwareToHardwareEncode` dans `DeviceProfile`.

## Flux de données

`ExportJob` → construit `AsyncFFmpegVideoWriter` → `recorder` configuré en `libopenh264`
→ `@Redirect start()` : probe HW (caché) → `setVideoCodecName(hw)` si dispo → `EncoderTuning`
→ `start()` (avec fallback software) → encodage sur GPU → `fbt_export_started` porte la promotion.

## Gestion d'erreur

- Probe : chaque candidat isolé en try/catch ; échec = candidat sauté.
- Promotion : si start() HW échoue → reset codec software + restart (testé).
- GL vendor : best-effort, omis si indisponible.
- Principe inviolable (cf. `Telemetry` fail-safe) : aucune ligne de H11 ne doit pouvoir
  empêcher un export de démarrer ou de finir.

## Tests

- **Unitaire** : `HwEncoderProbe` avec un `FFmpegFrameRecorder` simulé (candidat qui start OK /
  qui throw) → vérifie ordre de priorité, mémoïsation, fallback vide.
- **Unitaire** : logique de promotion (encoder==libopenh264 + flag → setVideoCodecName appelé ;
  flag off ou encoder HW natif → pas touché).
- **Validation qualité** : export de référence software vs promu HW → SSIM ≥ 0,99 (script de
  comparaison existant du projet) ; sinon ajuster bitrate/réglages.
- **In-game** (env de test isolé, cf. [[feedback_testing]]) : forcer un export H.264 sur machine
  avec GPU, vérifier dans les logs `[H11]` la promotion + dans PostHog `encoder_promoted_to`.

## Hors scope (YAGNI)

- **H5** (conversion RGB→YUV côté GPU) : chantier séparé, plus lourd, bénéficie à tous les
  encodeurs. Pas dans ce spec.
- Promotion HEVC/AV1 et `h264_mf` (AMD) : on cible H.264 HW (nvenc/qsv), le cas dominant.

## Compatibilité versions

Hook sur le même point que H6 → présent sur 1.21.x (yarn) et à porter dans
`scripts/build-26.1.sh` (Mojang). `RenderSystem`/GL pour le vendor : API stable, à remapper si
besoin dans le script 26.1.

Voir [[project-field-telemetry-findings]], [[project-posthog-telemetry]].
