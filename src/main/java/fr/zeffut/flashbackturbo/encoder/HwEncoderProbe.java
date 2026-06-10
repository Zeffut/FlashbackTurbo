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
