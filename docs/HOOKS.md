# FlashbackTurbo — Détail des hooks Mixin

Chaque optimisation est isolée dans son propre Mixin, opt-in via [`TurboConfig`](../src/main/java/fr/zeffut/flashbackturbo/config/TurboConfig.java).

## H4 — Lever cap résolution 4K ✅ (0.1.0)

**Cible** : `AsyncFFmpegVideoWriter.<init>`, constante `3840 * 2160`.
**Mécanisme** : `@ModifyConstant` remplaçant la constante par `Integer.MAX_VALUE`.
**Impact qualité** : amélioration — supprime un downscale silencieux au-delà de 4K.
**Test manuel** : exporter à 5120×2880, vérifier que le fichier sortie a bien cette résolution.

## H6 — Tunes threading FFmpeg ✅ (0.1.0)

**Cible** : `AsyncFFmpegVideoWriter.<init>`, appel `recorder.start()`.
**Mécanisme** : `@Redirect` autour de `start()` pour appliquer `setVideoOption("threads", "auto")` et tunes spécifiques par encoder (nvenc.delay, qsv.async_depth, amf.query_timeout).
**Lossless** : oui — les options touchent uniquement scheduling, pas l'output.
**Gain attendu** : 0-15% selon encoder (souvent 0 sur libx264 déjà bien threadé).

## H2 + H3 + H7 — Refonte PNG writer ✅ (0.2.0)

**Cible** : `PNGSequenceVideoWriter` complet.
**Mécanisme** :
- `@Inject(TAIL)` ctor : instancie `ParallelPngEncoder` (N-1 threads).
- `@Redirect` `Thread.start()` ctor : bypass du thread vanilla.
- `@Inject(HEAD, cancellable)` `encode/finish/close` : route vers notre pool.

**Gains combinés** :
- H2 (parallel) : 3-8× selon nb cœurs.
- H3 (zlib L1 default) : 2-4× sur compression. Lossless décodé.
- H7 (color type 2 si !transparent) : élimine la boucle alpha cleanup + fichiers plus petits.

**Lossless** : oui — pixels décodés strictement identiques au source. Le seul delta vs vanilla est la taille fichier (sortie RGB plus petite que RGBA) et l'ordre des octets dans le PNG, indétectable visuellement.

## H5 — GPU RGB→YUV (design, impl déférée 0.4.0)

### Problème

Sur l'export MP4, `AsyncFFmpegVideoWriter` lance un `rescaleThread` mono-cœur qui exécute `sws_scale(RGBA → YUV420p)` avec les flags `SWS_LANCZOS | SWS_ACCURATE_RND | SWS_FULL_CHR_H_INT`. Sur un export 4K60, ce thread consomme ~10-25 % du temps total et plafonne à ~500 MB/s par cœur en CPU.

### Solution proposée

Déplacer la conversion couleur sur le GPU, dans un shader exécuté pendant le blit que `SaveableFramebufferQueue.blitFlip()` fait déjà. Le `flipBuffer` RGBA8 devient un trio de textures Y/U/V (ou une texture NV12 si Mojang `TextureFormat` le supporte). Le download envoie des planes YUV au CPU au lieu de RGBA, et le `rescaleThread` peut être complètement contourné.

### Composants Mixin requis

1. **`SaveableFramebuffer`** (~120 lignes Mixin)
   - Ajouter 3 `GpuBuffer` (un par plane Y, U, V) en plus du PBO actuel.
   - Modifier `startDownload` pour `copyTextureToBuffer` × 3 (vers chaque plane).
   - Modifier `finishDownload` pour retourner un nouveau record `YuvFrame(byte[] y, byte[] u, byte[] v)` au lieu de `NativeImage`.

2. **`SaveableFramebufferQueue`** (~80 lignes Mixin)
   - `blitFlip` : créer/utiliser un `RenderPipeline` custom avec shader GLSL BT.709 RGB→YUV420p.
   - Le shader écrit dans 3 attachments (Y, U, V) — UV à demi-résolution via passes séparées ou MRT avec depth/2 sampling.

3. **`AsyncFFmpegVideoWriter`** (~60 lignes Mixin)
   - Forcer `ExportJob.SRC_PIXEL_FORMAT = AV_PIX_FMT_YUV420P` quand H5 actif.
   - Faire `needsRescale = false` (skip swscale entièrement).
   - `recorder.recordImage(...)` reçoit 3 buffers planar Y/U/V au lieu d'un RGBA packed.

4. **Shader GLSL** (`assets/flashbackturbo/shaders/rgb_to_yuv.glsl`)
   - Coefficients BT.709 limited-range (par défaut H.264/H.265 broadcast).
   - Output : 3 render targets ou subroutine multipasse.

### Risques

- **Audio sync** : le rescaleThread sert aussi à transporter `audioBuffer` avec chaque frame. Le bypass doit conserver ce couplage.
- **Encoders non-YUV** (ProRes 4444, QTRLE, GIF, WebP, PNG-video) : ces formats veulent du RGBA. H5 doit détecter et tomber back sur la voie vanilla.
- **`settings.transparent()`** : transparence requiert RGBA. H5 incompatible quand activé.
- **Mojang GpuTexture API** en 1.21.9 : à vérifier qu'elle expose des formats single-channel R8 nécessaires pour les planes Y/U/V séparées. Sinon, packing nécessaire.

### Prérequis avant impl

1. Baseline benchmark vanilla sur replay 5min 4K60 avec un encoder HW — quantifier le coût réel du rescaleThread.
2. Test SSIM sur output : vérifier que la matrice BT.709 GPU produit < 0.5 unité d'écart vs swscale (le swscale utilise BT.709 mais avec offset/rounding spécifiques qu'on doit reproduire bit-exact).
3. Validation sur ≥ 2 GPU (Apple Silicon, NVIDIA) pour s'assurer que le shader ne diverge pas.

### Pourquoi déférée

L'implémentation requiert une session de test sur MC + Flashback en runtime avec des replays réels, sur plusieurs GPU. Ne peut pas être validée par tests unitaires seuls. La justifier économiquement nécessite d'abord le benchmark baseline qui n'a pas été fait.

## H11 — Promotion software→hardware encode ✅ (0.5.0)

### Problème

~13 % des exports Windows passent par `libopenh264` (seul encodeur software H.264 du FFmpeg
bytedeco LGPL — pas de libx264). Mesuré en télémétrie : médiane **54,7 s** sur des CPU **6-12
cœurs** (libopenh264 est quasi mono-thread → CPU sous-exploité). Or `h264_nvenc` (NVIDIA) et
`h264_qsv` (Intel) sont compilés dans le même FFmpeg, juste non sélectionnés par Flashback quand
l'utilisateur choisit « H.264 » générique.

### Solution

Dans le `@Redirect` existant sur `recorder.start()` (H6) : si l'encodeur est `libopenh264` et que
la config `promoteSoftwareToHardwareEncode` est active, on probe une seule fois (mémoïsé) les
encodeurs HW par un mini-recorder 64×64 jetable, et on redirige vers le premier utilisable
(`h264_nvenc` > `h264_qsv` ; `h264_mf` exclu car mesuré plus lent que libopenh264, `h264_amf` non
compilé). Fail-safe total : toute erreur de probe/promotion ou un `start()` HW qui échoue ⇒ retour
silencieux sur `libopenh264`, l'export ne casse jamais.

Filet complémentaire : pour les configs réellement sans GPU encode (qui restent en libopenh264), on
pousse `slices = min(cœurs, 8)` à OpenH264 pour exploiter les cœurs (gain modeste, gratuit).

### Composants

- `encoder/EncoderPromotion` — décision pure (testée).
- `encoder/HwEncoderProbe` — sélection pure + opener FFmpeg natif mémoïsé (testé côté pur).
- `encoder/ExportContextHolder` — pose `encoder_promoted_from/to` sur le contexte d'export.
- `mixin/encoder/AsyncFFmpegVideoWriterMixin` — câblage + fallback.
- `telemetry/GpuInfo` + `DeviceProfile` — super-properties `gpu_vendor`/`gpu_renderer`.
- Events : `fbt_hw_promotion_probe` (one-shot/session), `encoder_promoted_*` sur `fbt_export_started`.

### Gain attendu

Estimation d'après la télémétrie terrain (libopenh264 ~55 s vs nvenc ~8,5 s / qsv ~23 s sur clips
1080p comparables) : **~3-6×** selon le GPU, qualité préservée (SSIM ≥ 0.99). **Chiffre exact à
confirmer par bench en jeu sur une machine Windows/Linux avec GPU NVIDIA/Intel** (cf.
`scripts/ssim-compare.sh`) — non mesurable sur macOS (ni nvenc ni qsv ; le HW Mac = videotoolbox, déjà sélectionné par Flashback).
