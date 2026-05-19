# FlashbackTurbo — Specification

## 1. Problème

Flashback exporte les replays Minecraft en re-rendant la timeline, capturant le framebuffer par tick, puis envoyant les frames à FFmpeg (mp4) ou PNG sequence. Sur un replay de 10 min à 60 fps (36 000 frames), l'export dure typiquement **30-90 minutes**.

L'audit du codebase Flashback 2026 (commit récent, version 0.39.5) montre que **deux des trois axes prévus du SPEC initial sont déjà optimisés en vanilla** :

| Étape | Part approx. | Status en Flashback 2026 |
|-------|--------------|--------------------------|
| Render + tick + PerfectFrames | ~40 % | Non touchable (hors scope) |
| Framebuffer readback | ~30 % | ✅ **déjà PBO async** via `GpuBuffer` + `copyTextureToBuffer`, 6 frames en vol |
| Encode FFmpeg | ~20 % | ✅ HW encoders déjà disponibles via `VideoCodec.getEncoders()` |
| Pre-encode CPU work (swscale RGBA→YUV) | ~10 % | ❌ rescaleThread mono-cœur — gros levier restant |
| PNG sequence write | ~10 % (mode PNG seul) | ❌ single-thread + zlib L6 hardcodé Mojang |

## 2. Contrainte

> **Aucune perte de qualité visuelle.** Tolérance : SSIM ≥ 0.99 par rapport à la sortie vanilla, ce qui couvre le changement d'encoder hardware (différences subtiles vs libx264) et la conversion couleur GPU vs swscale CPU (identiques à la spec BT.709 près).

## 3. Hooks (cf docs/HOOKS.md)

Sept hooks Mixin implémentés (0.1.x / 0.2.x / 0.3.x), un déféré (0.4.x).

### H4 — Lever cap résolution 4K (0.1.0)

`AsyncFFmpegVideoWriter` downscale silencieusement les exports >4K. On supprime cette branche via `@ModifyConstant`. Amélioration qualité, pas perte.

### H6 — Tunes threading FFmpeg (0.1.0)

`@Redirect` autour de `recorder.start()` pour injecter `setVideoOption("threads", "auto")` et tunes par encoder (nvenc.delay, qsv.async_depth, amf.query_timeout). Lossless — ne touche que le scheduling interne FFmpeg.

### H8 — Animated Saving overlay (0.3.5)

Pendant `AsyncFFmpegVideoWriter.finish()`, le main thread bloque sur les wait loops drainant les queues encode/rescale. Sans `H8`, l'écran reste figé sur la dernière frame `finishFrame` de Flashback. Notre Mixin `@Redirect` les `LockSupport.parkNanos` pour intercaler un `gameRenderer.render(deltaTracker, false)` + `glfwSwapBuffers` à 4 fps. Affiche un `SavingExportScreen` avec animation des points et timer.

### H9 — Fragmented MP4 sur hardware encoders (0.3.5)

`EncoderTuning.applyThreadingTunes` ajoute `recorder.setOption("movflags", "+frag_keyframe+empty_moov")` quand l'encoder est `videotoolbox/nvenc/qsv/amf`. Élimine le moov atom géant écrit à la fin du fichier MP4. Sur un export 3 GB, finalize réduit de ~15s à ~1-2s (vérifié via ffprobe : premier 200KB du fichier produit ne contient que `mdat`, aucun `moov`/`mvhd`).

### H2 + H3 + H7 — Refonte PNG writer (0.2.0)

`PNGSequenceVideoWriter` est remplacé par `ParallelPngEncoder` (N-1 threads, custom `FastPngWriter` avec `Deflater` configurable). Le bypass se fait via `@Redirect` sur `Thread.start()` dans le ctor + `@Inject(HEAD, cancellable)` sur encode/finish/close. H7 supprime la boucle d'alpha cleanup en utilisant directement PNG color type 2 (RGB) quand transparency est off.

### H5 — GPU RGB→YUV (0.4.0, déféré)

Convertir RGBA → YUV420p dans un shader GLSL pendant le `blitFlip`, télécharger des planes YUV au lieu de RGBA, faire skip le `rescaleThread`. Design complet dans `docs/HOOKS.md`. Implémentation déférée — risque élevé sans validation multi-GPU.

## 4. Configuration

Fichier JSON `<game>/config/flashbackturbo.json`. Tous les hooks toggleables. Niveau zlib PNG slider 1-9. Voir [`TurboConfig`](../src/main/java/fr/zeffut/flashbackturbo/config/TurboConfig.java).

## 5. Tests et validation

Voir [docs/TESTING.md](TESTING.md) pour les 3 couches :
- Tests unitaires JUnit 5 (round-trip PNG, helpers purs)
- Sandbox MC isolée via `./gradlew runClient`
- Benchmark vanilla vs turbo (à venir, externe au mod)

## 6. Compatibilité

- MC 1.21.9 / 1.21.10 (Flashback 0.39.5)
- 1.21.11 (Flashback 0.39.5 pour 1.21.11) — à valider
- 26.1.x (Flashback 0.40.0) — à valider
- Fabric Loader ≥ 0.19.2, Java 21
- Aucune dépendance Forge / NeoForge

## 7. Légal

Licence Flashback : "Copyright 2024 Moulberry. Do not redistribute. All rights reserved."

FlashbackTurbo ne redistribue **aucun** code Flashback. Le jar Flashback est compile-only, gitignored, chaque dev le télécharge. Les Mixins patchent le bytecode au runtime contre la copie de Flashback que l'utilisateur a légitimement installée.

**Action obligatoire avant release publique** : confirmer avec Moulberry sur le Discord `flashback-tool` (`#addons`) que les addons Mixin externes sont permis. La licence ne mentionne pas explicitement les addons ; précédent : MultiView existe sans contestation.

## 8. Roadmap

- **0.1.0** — H4 + H6 — gain qualité (4K cap) + tunes threading ✅ codé
- **0.2.0** — H2 + H3 + H7 — refonte PNG writer (5-10× sur export PNG) ✅ codé
- **0.3.0** — Baseline benchmark vanilla, tooling de validation SSIM
- **0.4.0** — H5 GPU RGB→YUV — gros gain MP4 (+10-25%)
- **0.5.0** — Support 1.21.11 / 26.1.x
- **1.0.0** — Permission Moulberry confirmée, premier release Modrinth public

## 9. Hors scope définitif

- Toucher au pipeline de rendu / PerfectFrames (responsabilité Flashback).
- Forker, redistribuer ou inclure du code Flashback.
- Auto-tuning qualité vs vitesse sans consentement explicite utilisateur.
- Tout changement qui dégrade l'output visuel.
