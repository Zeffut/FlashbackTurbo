package fr.zeffut.flashbackturbo.encoder;

import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Détermine le meilleur encodeur H.264 matériel réellement UTILISABLE (compilé dans le FFmpeg
 * bytedeco ET GPU/driver présents au runtime).
 *
 * <p>La sélection {@link #select} est pure et testable. L'ouverture réelle d'un encodeur (création
 * d'un {@code FFmpegFrameRecorder} jetable) est isolée dans {@link #realOpener()} et n'est jamais
 * exécutée en test unitaire.
 */
public final class HwEncoderProbe {

    /** Candidats par ordre de préférence. h264_mf exclu (95 s mesuré > openh264) ; amf non compilé. */
    public static final List<String> DEFAULT_CANDIDATES = List.of("h264_nvenc", "h264_qsv");

    private static volatile Optional<String> cached; // null = pas encore probé

    /** Résultat du probe (pour la télémétrie). */
    public record ProbeResult(List<String> probed, String selected, long probeMs) {}

    private static volatile ProbeResult lastResult;

    /** Meilleur encodeur HW utilisable, probé une seule fois puis mémoïsé. Best-effort. */
    public static synchronized Optional<String> bestH264Hardware() {
        return bestH264Hardware(realOpener());
    }

    /**
     * Overload testable : même logique de mémoïsation mais avec un opener injecté.
     * Permet de tester le cache et les ProbeResult sans native FFmpeg.
     */
    static synchronized Optional<String> bestH264Hardware(Predicate<String> opener) {
        if (cached != null) return cached;
        long start = System.nanoTime();
        Optional<String> sel = select(DEFAULT_CANDIDATES, opener);
        long ms = (System.nanoTime() - start) / 1_000_000L;
        cached = sel;
        lastResult = new ProbeResult(DEFAULT_CANDIDATES, sel.orElse(null), ms);
        fr.zeffut.flashbackturbo.FlashbackTurboClient.LOGGER.info(
            "[H11] probe encodeur HW → {} ({} ms)", sel.orElse("aucun"), ms);
        return sel;
    }

    /** Réinitialise le cache pour les tests. */
    static void resetForTest() {
        cached = null;
        lastResult = null;
    }

    /**
     * Dernier résultat de probe (null si jamais probé). Pour la télémétrie.
     * La lecture est intentionnellement non synchronisée (best-effort, télémétrie uniquement).
     */
    public static ProbeResult lastResult() { return lastResult; }

    /**
     * Opener réel : tente de démarrer un {@code FFmpegFrameRecorder} 64×64 mp4 vers un fichier
     * temporaire avec l'encodeur demandé. Réussit ⇒ encodeur utilisable. Tout échec ⇒ false.
     */
    private static Predicate<String> realOpener() {
        return name -> {
            java.io.File tmp = null;
            org.bytedeco.javacv.FFmpegFrameRecorder rec = null;
            boolean started = false;
            try {
                tmp = java.io.File.createTempFile("fbt-probe-", ".mp4");
                rec = new org.bytedeco.javacv.FFmpegFrameRecorder(tmp, 64, 64);
                rec.setFormat("mp4");
                rec.setVideoCodecName(name);
                rec.setFrameRate(30);
                rec.start();
                started = true;
                try (org.bytedeco.javacv.Java2DFrameConverter conv = new org.bytedeco.javacv.Java2DFrameConverter()) {
                    java.awt.image.BufferedImage img =
                        new java.awt.image.BufferedImage(64, 64, java.awt.image.BufferedImage.TYPE_3BYTE_BGR);
                    rec.record(conv.convert(img));
                }
                return true;
            } catch (Throwable t) {
                return false;
            } finally {
                if (rec != null) {
                    if (started) { try { rec.stop(); } catch (Throwable ignored) {} }
                    try { rec.release(); } catch (Throwable ignored) {}
                }
                if (tmp != null) { try { tmp.delete(); } catch (Throwable ignored) {} }
            }
        };
    }

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
