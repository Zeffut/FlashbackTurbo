# H11 — Promotion software→hardware encode — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Quand Flashback s'apprête à encoder en `libopenh264` (software, ~55 s sur des CPU 6-12 cœurs), rediriger automatiquement vers un encodeur matériel disponible (`h264_nvenc` puis `h264_qsv`) pour ~4-6× plus vite, sans perte de qualité (SSIM ≥ 0.99) et sans jamais casser l'export.

**Architecture:** Logique de décision et de sélection **pures** (testables sans FFmpeg natif) dans deux petites classes `EncoderPromotion` et `HwEncoderProbe` ; le probe matériel réel (création d'un `FFmpegFrameRecorder` jetable) est isolé derrière un `Predicate<String>` injectable. Le câblage se fait dans le `@Redirect` existant sur `recorder.start()` (H6) de `AsyncFFmpegVideoWriterMixin`, avec fallback software si le start matériel échoue. Télémétrie : `encoder_promoted_from/to`, event `fbt_hw_promotion_probe`, et super-properties `gpu_vendor`/`gpu_renderer`.

**Tech Stack:** Java 21, Fabric, Mixin + MixinExtras, org.bytedeco javacv `FFmpegFrameRecorder`, JUnit 5, Gradle, PostHog (shadé).

---

## File Structure

- Create `src/main/java/fr/zeffut/flashbackturbo/encoder/EncoderPromotion.java` — décision pure : faut-il promouvoir, et vers quel encodeur.
- Create `src/main/java/fr/zeffut/flashbackturbo/encoder/HwEncoderProbe.java` — sélection du meilleur encodeur HW utilisable (sélection pure + opener natif + mémoïsation).
- Create `src/main/java/fr/zeffut/flashbackturbo/telemetry/GpuInfo.java` — holder best-effort du vendor/renderer GL.
- Create `src/test/java/fr/zeffut/flashbackturbo/encoder/EncoderPromotionTest.java`
- Create `src/test/java/fr/zeffut/flashbackturbo/encoder/HwEncoderProbeTest.java`
- Create `src/test/java/fr/zeffut/flashbackturbo/encoder/EncoderTuningTest.java`
- Create `src/test/java/fr/zeffut/flashbackturbo/telemetry/GpuInfoTest.java`
- Modify `src/main/java/fr/zeffut/flashbackturbo/config/TurboConfig.java` — flag `promoteSoftwareToHardwareEncode`.
- Modify `src/main/java/fr/zeffut/flashbackturbo/telemetry/DeviceProfile.java` — `gpu_vendor`/`gpu_renderer` + `cfg_promoteSoftwareToHardwareEncode`.
- Modify `src/main/java/fr/zeffut/flashbackturbo/encoder/EncoderTuning.java` — case `libopenh264` (slices) + helper pur.
- Modify `src/main/java/fr/zeffut/flashbackturbo/mixin/encoder/AsyncFFmpegVideoWriterMixin.java` — câblage promotion + fallback + télémétrie.
- Modify `src/main/java/fr/zeffut/flashbackturbo/FlashbackTurboClient.java` — capture GL best-effort sur le render thread.
- Create `scripts/ssim-compare.sh` — comparaison SSIM ffmpeg de deux exports.
- Modify `README.md`, `docs/HOOKS.md` — documenter H11.
- Modify `gradle.properties` — bump version.

---

## Task 1: Décision de promotion (logique pure)

**Files:**
- Create: `src/main/java/fr/zeffut/flashbackturbo/encoder/EncoderPromotion.java`
- Test: `src/test/java/fr/zeffut/flashbackturbo/encoder/EncoderPromotionTest.java`

- [ ] **Step 1: Écrire le test qui échoue**

```java
package fr.zeffut.flashbackturbo.encoder;

import org.junit.jupiter.api.Test;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;

class EncoderPromotionTest {

    @Test
    void promotesLibopenh264WhenEnabledAndHwAvailable() {
        Optional<String> r = EncoderPromotion.choose("libopenh264", true, Optional.of("h264_nvenc"));
        assertEquals(Optional.of("h264_nvenc"), r);
    }

    @Test
    void doesNotPromoteWhenDisabled() {
        assertEquals(Optional.empty(),
            EncoderPromotion.choose("libopenh264", false, Optional.of("h264_nvenc")));
    }

    @Test
    void doesNotPromoteWhenNoHardware() {
        assertEquals(Optional.empty(),
            EncoderPromotion.choose("libopenh264", true, Optional.empty()));
    }

    @Test
    void doesNotPromoteNonSoftwareEncoder() {
        // déjà en HW natif (l'utilisateur a choisi nvenc) → on ne touche à rien
        assertEquals(Optional.empty(),
            EncoderPromotion.choose("h264_nvenc", true, Optional.of("h264_qsv")));
    }

    @Test
    void handlesNullCurrentEncoder() {
        assertEquals(Optional.empty(),
            EncoderPromotion.choose(null, true, Optional.of("h264_nvenc")));
    }
}
```

- [ ] **Step 2: Lancer le test pour vérifier qu'il échoue**

Run: `./gradlew test --tests 'fr.zeffut.flashbackturbo.encoder.EncoderPromotionTest'`
Expected: FAIL — `EncoderPromotion` n'existe pas (compilation error).

- [ ] **Step 3: Implémenter le minimum**

```java
package fr.zeffut.flashbackturbo.encoder;

import java.util.Optional;

/**
 * Décision pure de promotion d'encodeur. Aucune dépendance FFmpeg/Minecraft → testable seule.
 *
 * <p>On ne promeut QUE l'encodeur software H.264 ({@code libopenh264}) — le seul software H.264
 * présent dans le FFmpeg bytedeco LGPL (pas de libx264). Si l'utilisateur a déjà choisi un encodeur
 * matériel, on ne touche à rien.
 */
public final class EncoderPromotion {

    /** Encodeur software qu'on cherche à remplacer par du matériel. */
    public static final String SOFTWARE_H264 = "libopenh264";

    private EncoderPromotion() {}

    /**
     * @param current      encodeur actuellement configuré sur le recorder (peut être null)
     * @param enabled      flag de config {@code promoteSoftwareToHardwareEncode}
     * @param hwAvailable  meilleur encodeur HW utilisable détecté (vide si aucun)
     * @return l'encodeur HW vers lequel promouvoir, ou vide si on ne promeut pas
     */
    public static Optional<String> choose(String current, boolean enabled, Optional<String> hwAvailable) {
        if (!enabled) return Optional.empty();
        if (!SOFTWARE_H264.equals(current)) return Optional.empty();
        return hwAvailable;
    }
}
```

- [ ] **Step 4: Lancer le test pour vérifier qu'il passe**

Run: `./gradlew test --tests 'fr.zeffut.flashbackturbo.encoder.EncoderPromotionTest'`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/fr/zeffut/flashbackturbo/encoder/EncoderPromotion.java src/test/java/fr/zeffut/flashbackturbo/encoder/EncoderPromotionTest.java
git commit -m "feat(H11): décision pure de promotion d'encodeur (EncoderPromotion)"
```

---

## Task 2: Sélection du meilleur encodeur HW (logique pure)

**Files:**
- Create: `src/main/java/fr/zeffut/flashbackturbo/encoder/HwEncoderProbe.java`
- Test: `src/test/java/fr/zeffut/flashbackturbo/encoder/HwEncoderProbeTest.java`

- [ ] **Step 1: Écrire le test qui échoue**

```java
package fr.zeffut.flashbackturbo.encoder;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import static org.junit.jupiter.api.Assertions.*;

class HwEncoderProbeTest {

    @Test
    void picksFirstCandidateThatOpens() {
        Predicate<String> opener = name -> name.equals("h264_qsv"); // nvenc échoue, qsv ouvre
        Optional<String> r = HwEncoderProbe.select(List.of("h264_nvenc", "h264_qsv"), opener);
        assertEquals(Optional.of("h264_qsv"), r);
    }

    @Test
    void prefersEarlierCandidateWhenBothOpen() {
        Predicate<String> opener = name -> true;
        Optional<String> r = HwEncoderProbe.select(List.of("h264_nvenc", "h264_qsv"), opener);
        assertEquals(Optional.of("h264_nvenc"), r);
    }

    @Test
    void emptyWhenNoneOpen() {
        Predicate<String> opener = name -> false;
        assertEquals(Optional.empty(),
            HwEncoderProbe.select(List.of("h264_nvenc", "h264_qsv"), opener));
    }

    @Test
    void openerThrowingIsTreatedAsUnavailable() {
        Predicate<String> opener = name -> { throw new RuntimeException("driver boom"); };
        assertEquals(Optional.empty(),
            HwEncoderProbe.select(List.of("h264_nvenc"), opener));
    }

    @Test
    void defaultCandidatesAreNvencThenQsvOnly() {
        // h264_mf est exclu (mesuré plus lent que libopenh264), amf non compilé
        assertEquals(List.of("h264_nvenc", "h264_qsv"), HwEncoderProbe.DEFAULT_CANDIDATES);
    }
}
```

- [ ] **Step 2: Lancer le test pour vérifier qu'il échoue**

Run: `./gradlew test --tests 'fr.zeffut.flashbackturbo.encoder.HwEncoderProbeTest'`
Expected: FAIL — `HwEncoderProbe` n'existe pas.

- [ ] **Step 3: Implémenter le minimum (sélection pure + constantes ; opener natif ajouté en Task 3)**

```java
package fr.zeffut.flashbackturbo.encoder;

import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Détermine le meilleur encodeur H.264 matériel réellement UTILISABLE (compilé dans le FFmpeg
 * bytedeco ET GPU/driver présents au runtime).
 *
 * <p>La sélection {@link #select} est pure et testable. L'ouverture réelle d'un encodeur (création
 * d'un {@code FFmpegFrameRecorder} jetable) est isolée dans {@link #realOpener()} (Task 3) et n'est
 * jamais exécutée en test unitaire.
 */
public final class HwEncoderProbe {

    /** Candidats par ordre de préférence. h264_mf exclu (95 s mesuré > openh264) ; amf non compilé. */
    public static final List<String> DEFAULT_CANDIDATES = List.of("h264_nvenc", "h264_qsv");

    private HwEncoderProbe() {}

    /**
     * Premier candidat que {@code opener} parvient à ouvrir. Un opener qui lève est traité comme
     * « indisponible » (candidat sauté). Best-effort : ne lève jamais.
     */
    public static Optional<String> select(List<String> candidates, Predicate<String> opener) {
        for (String c : candidates) {
            try {
                if (opener.test(c)) return Optional.of(c);
            } catch (Throwable ignored) {
                // candidat indisponible (classe absente, driver KO) → suivant
            }
        }
        return Optional.empty();
    }
}
```

- [ ] **Step 4: Lancer le test pour vérifier qu'il passe**

Run: `./gradlew test --tests 'fr.zeffut.flashbackturbo.encoder.HwEncoderProbeTest'`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/fr/zeffut/flashbackturbo/encoder/HwEncoderProbe.java src/test/java/fr/zeffut/flashbackturbo/encoder/HwEncoderProbeTest.java
git commit -m "feat(H11): sélection pure du meilleur encodeur HW (HwEncoderProbe.select)"
```

---

## Task 3: Opener natif + mémoïsation (probe réel)

**Files:**
- Modify: `src/main/java/fr/zeffut/flashbackturbo/encoder/HwEncoderProbe.java`

> Pas de test unitaire : l'opener crée un vrai `FFmpegFrameRecorder` (natif). Vérifié au build (compile) puis en jeu (Task 10). Toute la méthode est fail-safe.

- [ ] **Step 1: Ajouter l'opener natif et la sélection mémoïsée**

Ajouter les imports et membres suivants à `HwEncoderProbe` (après le champ `DEFAULT_CANDIDATES`) :

```java
    private static volatile Optional<String> cached; // null = pas encore probé
    private static volatile long lastProbeMs = -1L;

    /** Résultat du probe (pour la télémétrie). */
    public record ProbeResult(java.util.List<String> probed, String selected, long probeMs) {}

    private static volatile ProbeResult lastResult;

    /** Meilleur encodeur HW utilisable, probé une seule fois puis mémoïsé. Best-effort. */
    public static synchronized Optional<String> bestH264Hardware() {
        if (cached != null) return cached;
        long start = System.nanoTime();
        Optional<String> sel = select(DEFAULT_CANDIDATES, realOpener());
        long ms = (System.nanoTime() - start) / 1_000_000L;
        cached = sel;
        lastResult = new ProbeResult(DEFAULT_CANDIDATES, sel.orElse(null), ms);
        return sel;
    }

    /** Dernier résultat de probe (null si jamais probé). Pour la télémétrie. */
    public static ProbeResult lastResult() { return lastResult; }

    /**
     * Opener réel : tente de démarrer un {@code FFmpegFrameRecorder} 64×64 mp4 vers un fichier
     * temporaire avec l'encodeur demandé. Réussit ⇒ encodeur utilisable. Tout échec ⇒ false.
     */
    private static Predicate<String> realOpener() {
        return name -> {
            java.io.File tmp = null;
            org.bytedeco.javacv.FFmpegFrameRecorder rec = null;
            try {
                tmp = java.io.File.createTempFile("fbt-probe-", ".mp4");
                rec = new org.bytedeco.javacv.FFmpegFrameRecorder(tmp, 64, 64);
                rec.setFormat("mp4");
                rec.setVideoCodecName(name);
                rec.setFrameRate(30);
                rec.start(); // lève si l'encodeur n'est pas ouvrable (driver/GPU absent)
                try (org.bytedeco.javacv.Java2DFrameConverter conv = new org.bytedeco.javacv.Java2DFrameConverter()) {
                    java.awt.image.BufferedImage img =
                        new java.awt.image.BufferedImage(64, 64, java.awt.image.BufferedImage.TYPE_3BYTE_BGR);
                    rec.record(conv.convert(img));
                }
                return true;
            } catch (Throwable t) {
                return false;
            } finally {
                if (rec != null) { try { rec.stop(); } catch (Throwable ignored) {} try { rec.release(); } catch (Throwable ignored) {} }
                if (tmp != null) { try { tmp.delete(); } catch (Throwable ignored) {} }
            }
        };
    }
```

- [ ] **Step 2: Compiler pour vérifier que les types javacv résolvent**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL (les classes `FFmpegFrameRecorder`, `Java2DFrameConverter` viennent du jar Flashback en `modCompileOnly`).

- [ ] **Step 3: Re-lancer la suite probe (toujours verte, on n'a touché qu'au natif)**

Run: `./gradlew test --tests 'fr.zeffut.flashbackturbo.encoder.HwEncoderProbeTest'`
Expected: PASS (5 tests).

- [ ] **Step 4: Commit**

```bash
git add src/main/java/fr/zeffut/flashbackturbo/encoder/HwEncoderProbe.java
git commit -m "feat(H11): opener FFmpeg natif + probe HW mémoïsé"
```

---

## Task 4: Flag de configuration

**Files:**
- Modify: `src/main/java/fr/zeffut/flashbackturbo/config/TurboConfig.java`
- Modify: `src/main/java/fr/zeffut/flashbackturbo/telemetry/DeviceProfile.java:43` (bloc des `cfg_*`)

> Pas de test unitaire (le singleton dépend de FabricLoader) ; couvert par compile + lecture dans la logique testée.

- [ ] **Step 1: Ajouter le flag dans TurboConfig**

Dans `TurboConfig.java`, juste après le champ `fixExportSetupRace` (ligne ~70), ajouter :

```java
    /**
     * H11 : si Flashback encode en {@code libopenh264} (software, mono-thread, lent) mais qu'un
     * encodeur matériel ({@code h264_nvenc} puis {@code h264_qsv}) est réellement utilisable,
     * redirige l'export vers lui. ~4-6× plus rapide, qualité égale ou supérieure à débit égal.
     * Fail-safe : si la promotion échoue au démarrage, on retombe sur {@code libopenh264}.
     */
    public boolean promoteSoftwareToHardwareEncode = true;
```

- [ ] **Step 2: Exposer le flag dans la télémétrie**

Dans `DeviceProfile.collect()`, après la ligne `p.put("cfg_fixExportSetupRace", cfg.fixExportSetupRace);`, ajouter :

```java
            p.put("cfg_promoteSoftwareToHardwareEncode", cfg.promoteSoftwareToHardwareEncode);
```

- [ ] **Step 3: Compiler**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/fr/zeffut/flashbackturbo/config/TurboConfig.java src/main/java/fr/zeffut/flashbackturbo/telemetry/DeviceProfile.java
git commit -m "feat(H11): flag config promoteSoftwareToHardwareEncode + télémétrie"
```

---

## Task 5: Holder GPU vendor/renderer (best-effort)

**Files:**
- Create: `src/main/java/fr/zeffut/flashbackturbo/telemetry/GpuInfo.java`
- Test: `src/test/java/fr/zeffut/flashbackturbo/telemetry/GpuInfoTest.java`

- [ ] **Step 1: Écrire le test qui échoue**

```java
package fr.zeffut.flashbackturbo.telemetry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class GpuInfoTest {

    @BeforeEach
    void reset() { GpuInfo.resetForTest(); }

    @Test
    void emptyByDefault() {
        assertTrue(GpuInfo.snapshot().isEmpty());
    }

    @Test
    void snapshotContainsVendorAndRendererOnceSet() {
        GpuInfo.setForTest("NVIDIA Corporation", "NVIDIA GeForce RTX 3070/PCIe/SSE2");
        Map<String, Object> p = GpuInfo.snapshot();
        assertEquals("NVIDIA Corporation", p.get("gpu_vendor"));
        assertEquals("NVIDIA GeForce RTX 3070/PCIe/SSE2", p.get("gpu_renderer"));
    }

    @Test
    void blankValuesAreOmitted() {
        GpuInfo.setForTest("", "   ");
        assertTrue(GpuInfo.snapshot().isEmpty());
    }
}
```

- [ ] **Step 2: Lancer le test pour vérifier qu'il échoue**

Run: `./gradlew test --tests 'fr.zeffut.flashbackturbo.telemetry.GpuInfoTest'`
Expected: FAIL — `GpuInfo` n'existe pas.

- [ ] **Step 3: Implémenter le minimum**

```java
package fr.zeffut.flashbackturbo.telemetry;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Holder best-effort du vendor/renderer GL de Minecraft. Rempli une seule fois depuis le render
 * thread (où le contexte GL est courant) via {@link #captureFromGl}. Si jamais rempli, {@link
 * #snapshot()} renvoie une map vide → les propriétés sont simplement omises de la télémétrie.
 */
public final class GpuInfo {

    private static volatile String vendor;
    private static volatile String renderer;

    private GpuInfo() {}

    /** À appeler sur le render thread (contexte GL courant). Best-effort, ne lève jamais. */
    public static void captureFromGl() {
        if (vendor != null) return; // déjà capturé
        try {
            String v = org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_VENDOR);
            String r = org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_RENDERER);
            vendor = v == null ? "" : v;
            renderer = r == null ? "" : r;
        } catch (Throwable t) {
            vendor = ""; renderer = "";
        }
    }

    /** Propriétés à fusionner dans les super-properties. Vide si non capturé ou valeurs blanches. */
    public static Map<String, Object> snapshot() {
        Map<String, Object> p = new LinkedHashMap<>();
        String v = vendor, r = renderer;
        if (v != null && !v.isBlank()) p.put("gpu_vendor", v);
        if (r != null && !r.isBlank()) p.put("gpu_renderer", r);
        return p;
    }

    // --- test hooks ---
    static void resetForTest() { vendor = null; renderer = null; }
    static void setForTest(String v, String r) { vendor = v; renderer = r; }
}
```

- [ ] **Step 4: Lancer le test pour vérifier qu'il passe**

Run: `./gradlew test --tests 'fr.zeffut.flashbackturbo.telemetry.GpuInfoTest'`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/fr/zeffut/flashbackturbo/telemetry/GpuInfo.java src/test/java/fr/zeffut/flashbackturbo/telemetry/GpuInfoTest.java
git commit -m "feat(H11): GpuInfo holder best-effort (vendor/renderer GL)"
```

---

## Task 6: Inclure le GPU dans DeviceProfile + capture sur render thread

**Files:**
- Modify: `src/main/java/fr/zeffut/flashbackturbo/telemetry/DeviceProfile.java`
- Modify: `src/main/java/fr/zeffut/flashbackturbo/FlashbackTurboClient.java`

> Pas de test unitaire pour la capture GL (native). Le merge des propriétés est trivial ; vérifié par compile + en jeu.

- [ ] **Step 1: Fusionner les propriétés GPU dans DeviceProfile.collect()**

Dans `DeviceProfile.collect()`, juste avant le `return p;` final, ajouter :

```java
            p.putAll(GpuInfo.snapshot()); // gpu_vendor/gpu_renderer si déjà capturés (best-effort)
```

- [ ] **Step 2: Déclencher la capture GL sur le render thread**

Dans `FlashbackTurboClient.onInitializeClient()` (ou la méthode d'init client équivalente), enregistrer un hook qui capture une seule fois au premier rendu. Ajouter l'import `net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;` puis, dans l'init :

```java
        // H11 : capturer le vendor/renderer GL une seule fois, sur le render thread (contexte GL courant).
        HudRenderCallback.EVENT.register((ctx, tickCounter) -> {
            fr.zeffut.flashbackturbo.telemetry.GpuInfo.captureFromGl(); // no-op après la 1re capture
        });
```

> Si `HudRenderCallback` n'est pas disponible dans la version de Fabric API utilisée, utiliser `net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK` à la place et envelopper l'appel dans `com.mojang.blaze3d.systems.RenderSystem.isOnRenderThread()` — sinon laisser `captureFromGl()` qui est déjà gardé en try/catch.

- [ ] **Step 3: Compiler**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/fr/zeffut/flashbackturbo/telemetry/DeviceProfile.java src/main/java/fr/zeffut/flashbackturbo/FlashbackTurboClient.java
git commit -m "feat(H11): capture GL vendor/renderer + inclusion DeviceProfile"
```

---

## Task 7: Tune threading libopenh264 (helper pur + case EncoderTuning)

**Files:**
- Modify: `src/main/java/fr/zeffut/flashbackturbo/encoder/EncoderTuning.java`
- Test: `src/test/java/fr/zeffut/flashbackturbo/encoder/EncoderTuningTest.java`

- [ ] **Step 1: Écrire le test qui échoue (helper pur de calcul des slices)**

```java
package fr.zeffut.flashbackturbo.encoder;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EncoderTuningTest {

    @Test
    void slicesEqualsCoresWhenBelowCap() {
        assertEquals(6, EncoderTuning.openh264Slices(6));
    }

    @Test
    void slicesCappedAtEight() {
        assertEquals(8, EncoderTuning.openh264Slices(16));
    }

    @Test
    void slicesAtLeastOne() {
        assertEquals(1, EncoderTuning.openh264Slices(0));
        assertEquals(1, EncoderTuning.openh264Slices(-4));
    }
}
```

- [ ] **Step 2: Lancer le test pour vérifier qu'il échoue**

Run: `./gradlew test --tests 'fr.zeffut.flashbackturbo.encoder.EncoderTuningTest'`
Expected: FAIL — `openh264Slices` n'existe pas.

- [ ] **Step 3: Implémenter le helper pur + le case dans `applyThreadingTunes`**

Ajouter dans `EncoderTuning` (méthode publique statique, près du haut de la classe) :

```java
    /** Nombre de slices OpenH264 = cœurs plafonné à 8, au moins 1. Pur, testable. */
    public static int openh264Slices(int cores) {
        return Math.max(1, Math.min(8, cores));
    }
```

Puis dans le `switch (encoder)` de `applyThreadingTunes`, remplacer le commentaire `default` existant en ajoutant un case explicite AVANT le `default` :

```java
            case "libopenh264" -> {
                // OpenH264 est quasi mono-thread via 'threads' seul ; 'slices' découpe la frame en
                // tranches encodables en parallèle → exploite les cœurs sur les configs sans GPU encode.
                if (recorder.getVideoOption("slices") == null) {
                    tryVideoOption(recorder, "slices", Integer.toString(openh264Slices(CPU_CORES)));
                }
            }
```

- [ ] **Step 4: Lancer le test + la suite encoder**

Run: `./gradlew test --tests 'fr.zeffut.flashbackturbo.encoder.*'`
Expected: PASS (EncoderPromotion 5 + HwEncoderProbe 5 + EncoderTuning 3).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/fr/zeffut/flashbackturbo/encoder/EncoderTuning.java src/test/java/fr/zeffut/flashbackturbo/encoder/EncoderTuningTest.java
git commit -m "feat(H11): threading slices pour libopenh264 (filet configs sans GPU)"
```

---

## Task 8: Câbler la promotion dans le mixin (start() redirect + fallback + télémétrie)

**Files:**
- Modify: `src/main/java/fr/zeffut/flashbackturbo/mixin/encoder/AsyncFFmpegVideoWriterMixin.java:58-71`

> Pas de test unitaire (mixin + recorder natif). Vérifié par compile + bench/test en jeu (Task 10). Toute la promotion est fail-safe.

- [ ] **Step 1: Remplacer la méthode `flashbackturbo$tuneRecorderBeforeStart`**

D'abord, ajouter un champ de garde one-shot en tête de la classe `AsyncFFmpegVideoWriterMixin` (juste après l'accolade ouvrante de la classe) :

```java
    @org.spongepowered.asm.mixin.Unique
    private static boolean flashbackturbo$hwProbeReported = false;
```

Puis remplacer entièrement le corps de la méthode `@Redirect` existante par :

```java
    @Redirect(
        method = "<init>",
        at = @At(
            value = "INVOKE",
            target = "Lorg/bytedeco/javacv/FFmpegFrameRecorder;start()V"
        ),
        require = 0
    )
    private void flashbackturbo$tuneRecorderBeforeStart(FFmpegFrameRecorder recorder) throws FFmpegFrameRecorder.Exception {
        String promotedFrom = null, promotedTo = null;

        // H11 : promotion software → hardware si applicable.
        try {
            if (fr.zeffut.flashbackturbo.config.TurboConfig.current().promoteSoftwareToHardwareEncode) {
                String current = recorder.getVideoCodecName();
                java.util.Optional<String> hw = fr.zeffut.flashbackturbo.encoder.EncoderPromotion.choose(
                    current, true, fr.zeffut.flashbackturbo.encoder.HwEncoderProbe.bestH264Hardware());
                // Émettre l'event de probe UNE SEULE FOIS par session (le probe est mémoïsé).
                var pr = fr.zeffut.flashbackturbo.encoder.HwEncoderProbe.lastResult();
                if (pr != null && !flashbackturbo$hwProbeReported) {
                    flashbackturbo$hwProbeReported = true;
                    java.util.Map<String, Object> pp = new HashMap<>();
                    pp.put("probed", pr.probed());
                    pp.put("selected", pr.selected());
                    pp.put("probe_ms", pr.probeMs());
                    fr.zeffut.flashbackturbo.telemetry.Telemetry.capture("fbt_hw_promotion_probe", pp);
                }
                if (hw.isPresent()) {
                    recorder.setVideoCodecName(hw.get());
                    promotedFrom = current;
                    promotedTo = hw.get();
                    FlashbackTurboClient.LOGGER.info("[H11] promotion encodeur {} → {}", current, hw.get());
                }
            }
        } catch (Throwable t) {
            FlashbackTurboClient.LOGGER.warn("[H11] promotion ignorée (fail-safe)", t);
            promotedFrom = null; promotedTo = null;
        }

        // Enrichir le contexte d'export pour fbt_export_started.
        if (promotedTo != null) {
            try {
                ExportContextHolder.recordPromotion(promotedFrom, promotedTo);
            } catch (Throwable ignored) {}
        }

        if (TurboConfig.current().tuneFFmpegThreading) {
            EncoderTuning.applyThreadingTunes(recorder);
        }

        // Démarrage avec fallback : si le HW promu refuse de démarrer, on revient au software.
        try {
            recorder.start();
        } catch (Throwable t) {
            if (promotedTo != null) {
                FlashbackTurboClient.LOGGER.warn("[H11] start {} échoué, retour à {} : {}",
                    promotedTo, promotedFrom, t.toString());
                try { recorder.release(); } catch (Throwable ignored) {}
                recorder.setVideoCodecName(promotedFrom);
                if (TurboConfig.current().tuneFFmpegThreading) {
                    EncoderTuning.applyThreadingTunes(recorder);
                }
                try { ExportContextHolder.recordPromotion(null, null); } catch (Throwable ignored) {}
                recorder.start(); // si ça relève ici, on laisse remonter (comportement vanilla)
            } else {
                throw t;
            }
        }
    }
```

> `HashMap` est déjà importable : ajouter `import java.util.HashMap;` en tête du fichier si absent.

- [ ] **Step 2: Ajouter le helper d'enrichissement du contexte d'export**

La promotion doit poser `encoder_promoted_from/to` sur l'`ExportContext` actif. Ajouter une petite classe utilitaire pour éviter de coupler le mixin à l'API interne. Créer `src/main/java/fr/zeffut/flashbackturbo/encoder/ExportContextHolder.java` :

```java
package fr.zeffut.flashbackturbo.encoder;

import fr.zeffut.flashbackturbo.telemetry.ExportContext;
import fr.zeffut.flashbackturbo.telemetry.Telemetry;

/** Petit pont fail-safe pour annoter le contexte d'export actif avec la promotion d'encodeur. */
public final class ExportContextHolder {
    private ExportContextHolder() {}

    /** Pose (ou efface si null) les propriétés de promotion sur le contexte d'export courant. */
    public static void recordPromotion(String from, String to) {
        ExportContext ctx = Telemetry.export();
        if (ctx == null) return;
        ctx.put("encoder_promoted_from", from);
        ctx.put("encoder_promoted_to", to);
    }
}
```

Et corriger l'appel dans le mixin : remplacer les deux `ExportContextHolder.recordPromotion(...)` par `fr.zeffut.flashbackturbo.encoder.ExportContextHolder.recordPromotion(...)` (ou ajouter l'import `import fr.zeffut.flashbackturbo.encoder.ExportContextHolder;`).

- [ ] **Step 3: Compiler**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Lancer toute la suite de tests**

Run: `./gradlew test`
Expected: PASS (tous les tests existants + nouveaux).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/fr/zeffut/flashbackturbo/mixin/encoder/AsyncFFmpegVideoWriterMixin.java src/main/java/fr/zeffut/flashbackturbo/encoder/ExportContextHolder.java
git commit -m "feat(H11): câblage promotion HW dans le redirect start() + fallback software + télémétrie"
```

---

## Task 9: Build complet 1.21.x + variante 26.1

**Files:** (aucun nouveau ; validation de compilation sur toute la matrice)

- [ ] **Step 1: Build principal 1.21.x**

Run: `./gradlew clean build`
Expected: BUILD SUCCESSFUL ; jar produit dans `build/libs/flashbackturbo-*.jar`. Vérifier la relocation PostHog inchangée :
Run: `jar tf build/libs/flashbackturbo-*.jar | grep -c '^fr/zeffut/flashbackturbo/shadow/posthog/'`
Expected: > 0.

- [ ] **Step 2: Build variante 26.1**

Run: `./scripts/build-26.1.sh`
Expected: `[26.1] ✅ build OK (shadé)`. Si le script casse sur les nouveaux fichiers (mappings yarn→mojang), corriger `scripts/build-26.1.sh` : les classes `EncoderPromotion`, `HwEncoderProbe`, `GpuInfo`, `ExportContextHolder` n'utilisent pas d'API Minecraft mappée SAUF `GpuInfo` (LWJGL `GL11`, identique en mojang → aucun remap) et `FlashbackTurboClient` (déjà géré par les sed existants). Vérifier qu'aucun nom mappé nouveau n'est requis.

- [ ] **Step 3: Commit (si le script 26.1 a dû être ajusté)**

```bash
git add scripts/build-26.1.sh
git commit -m "build(H11): adapte build-26.1 pour les nouvelles classes encoder"
```

---

## Task 10: BENCH + validation qualité (en jeu, env de test isolé)

**Files:**
- Create: `scripts/ssim-compare.sh`

> Cf. [[feedback_testing]] : env de test SÉPARÉ, ne jamais polluer le jar mod. Le bench se fait en jeu car l'encodage réel passe par Flashback.

- [ ] **Step 1: Créer le script de comparaison SSIM**

```bash
#!/usr/bin/env bash
# Compare deux vidéos image par image et renvoie le SSIM moyen.
# Usage : ./scripts/ssim-compare.sh reference.mp4 candidate.mp4
# Exit 0 si SSIM moyen ≥ 0.99, sinon 1.
set -euo pipefail
REF="$1"; CAND="$2"
OUT=$(ffmpeg -i "$REF" -i "$CAND" -lavfi "ssim=stats_file=-" -f null - 2>&1 | grep -oE 'All:[0-9.]+' | tail -1 | cut -d: -f2)
echo "SSIM moyen = $OUT"
awk -v s="$OUT" 'BEGIN{ exit (s+0 >= 0.99 ? 0 : 1) }'
```

Puis : `chmod +x scripts/ssim-compare.sh`.

- [ ] **Step 2: Préparer l'env de test et lancer le jeu**

Préparer l'environnement de test isolé (runtime Flashback + deps, cf. session précédente : `libs/flashback-runtime-deps/` + `modLocalRuntime`, NON committé). Lancer le client dev :
Run: `./gradlew runClient` (ou la cible de test isolée du projet)
Expected: client atteint le menu ; un replay de test est disponible.

- [ ] **Step 3: Bench A — forcer libopenh264 (référence)**

Dans le jeu : config `flashbackturbo.json` → `"promoteSoftwareToHardwareEncode": false`. Exporter le replay de test en **H.264** (qui retombe sur `libopenh264`). Noter dans les logs la durée d'export et récupérer la vidéo de sortie (`reference_sw.mp4`). Confirmer dans PostHog l'event `fbt_export_finished` avec `encoder=libopenh264` (pas de `encoder_promoted_to`).

- [ ] **Step 4: Bench B — promotion active**

Config → `"promoteSoftwareToHardwareEncode": true`. Ré-exporter le **même** replay, mêmes réglages. Récupérer `candidate_hw.mp4`. Vérifier dans les logs `[H11] promotion encodeur libopenh264 → h264_nvenc` (ou qsv) et dans PostHog `fbt_export_started` avec `encoder_promoted_to` + `fbt_hw_promotion_probe`.

- [ ] **Step 5: Chiffrer le gain et valider la qualité**

```bash
./scripts/ssim-compare.sh reference_sw.mp4 candidate_hw.mp4
```
Expected: `SSIM moyen ≥ 0.99` (exit 0). Calculer le gain temps = durée_A / durée_B (attendu ~3-6×). **Consigner** les deux durées + le SSIM + l'encodeur promu dans le commit message et dans `docs/HOOKS.md`.

- [ ] **Step 6: Documenter H11 + bench dans HOOKS.md et README**

Ajouter une entrée H11 dans `docs/HOOKS.md` (mécanisme + résultat de bench chiffré) et une ligne dans le tableau des hooks du `README.md` (`| **H11** | AsyncFFmpegVideoWriter.start() | Promotion auto libopenh264 → nvenc/qsv quand un GPU encode est dispo (~Nx plus vite, SSIM ≥ 0.99) |`). Remplacer `~Nx` par le gain mesuré.

- [ ] **Step 7: Commit**

```bash
git add scripts/ssim-compare.sh docs/HOOKS.md README.md
git commit -m "test(H11): bench en jeu — libopenh264 vs <encodeur> = <X>x, SSIM <valeur> ; doc H11"
```

> ⚠️ Si le bench ne peut pas être lancé en autonomie (pas de GPU dispo dans l'env de test, ou pas de replay), NE PAS publier : marquer cette tâche bloquée et demander à l'utilisateur de faire le bench manuel, en fournissant la procédure ci-dessus.

---

## Task 11: Bump version + publication (toutes versions + launchers)

> Pré-requis : Task 10 verte (gain chiffré + SSIM ≥ 0.99). Cf. [[feedback_bench_and_publish]], [[reference_publishing]], [[feedback_releases]] (ne JAMAIS écraser une version publiée).

- [ ] **Step 1: Bump de version**

Dans `gradle.properties`, incrémenter `mod_version` (ex. `0.4.0` → `0.5.0`). Mettre à jour le badge/statut du `README.md` et le bloc de compat (table des versions FBT).

- [ ] **Step 2: Rebuild des deux variantes propres**

Run: `./gradlew clean build` puis `./scripts/build-26.1.sh`
Expected: deux jars finaux (1.21.x dans `build/libs/`, 26.1 dans `/tmp/FlashbackTurbo-26.1/build/libs/`).

- [ ] **Step 3: Vérifier le token Modrinth avant publication**

Run: `set -a && source ~/.claude/.mc-secrets.env && set +a && curl -s -o /dev/null -w "%{http_code}\n" -H "Authorization: $MODRINTH_TOKEN" https://api.modrinth.com/v2/user`
Expected: `200`. (Sinon, stop — token à renouveler, cf. [[reference_publishing]].)

- [ ] **Step 4: Publier sur Modrinth (2 variantes)**

`POST https://api.modrinth.com/v2/version` (multipart `data` JSON + `file` jar) pour :
- `version_number = X.Y.Z`, `game_versions = ["1.21.9","1.21.10","1.21.11"]`, `loaders=["fabric"]`, deps required Flashback `4das1Fjq` + Fabric API `P7dR8mSH`, `version_type=release`.
- `version_number = X.Y.Z+26.1`, `game_versions = ["26.1","26.1.1","26.1.2"]`, même deps.
Changelog : mentionner H11 (promotion HW, gain mesuré, SSIM ≥ 0.99). NE PAS toucher aux versions déjà publiées.

- [ ] **Step 5: Releases GitHub**

```bash
gh release create vX.Y.Z build/libs/flashbackturbo-X.Y.Z.jar --title "vX.Y.Z" --notes "H11 — promotion software→hardware encode (~<X>x sur configs sans GPU sélectionné, SSIM ≥ 0.99)"
gh release create vX.Y.Z+26.1 /tmp/FlashbackTurbo-26.1/build/libs/flashbackturbo-X.Y.Z.jar --title "vX.Y.Z+26.1" --notes "Variante 26.1 — H11"
```

- [ ] **Step 6: Mettre à jour la description Modrinth (corps)**

`PATCH /v2/project/6o9zaNB9` : ajouter H11 dans la liste des hooks du body. Vérifier la page : https://modrinth.com/mod/flashbackturbo

- [ ] **Step 7: Commit + tag final**

```bash
git add gradle.properties README.md
git commit -m "release(X.Y.Z): H11 promotion software→hardware encode"
git tag vX.Y.Z
git push && git push --tags
```

---

## Notes d'exécution

- Toute la logique H11 est **fail-safe** : aucune exception ne doit empêcher un export. Les Tasks 3, 6, 8 enveloppent tout en try/catch(Throwable).
- Les Tasks 1, 2, 7 (logique pure) sont les seules avec tests unitaires — c'est voulu : le natif FFmpeg / Mixin / GL n'est pas unit-testable et passe par compile + bench en jeu.
- Le gain réel se confirmera aussi en prod via la télémétrie (`encoder_promoted_to`, durées par `gpu_vendor`), cf. [[project_field_telemetry_findings]].
