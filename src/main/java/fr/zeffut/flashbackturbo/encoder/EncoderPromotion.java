package fr.zeffut.flashbackturbo.encoder;

import java.util.Optional;

/**
 * Décision pure de promotion d'encodeur. Aucune dépendance FFmpeg/Minecraft → testable seule.
 *
 * <p>On ne promeut QUE les encodeurs software identifiés comme défaillants ou sous-performants :
 * <ul>
 *   <li>{@code libopenh264} (H264 software, mono-thread, lent) → {@code h264_nvenc} / {@code h264_qsv}</li>
 *   <li>{@code hevc_mf} (H265 Windows Media Foundation, systématiquement défaillant sur Windows 11)
 *       → {@code hevc_nvenc} / {@code hevc_qsv}</li>
 * </ul>
 * Si l'utilisateur a déjà choisi un encodeur matériel fonctionnel, on ne touche à rien.
 */
public final class EncoderPromotion {

    /** Encodeur software H264 qu'on cherche à remplacer par du matériel. */
    public static final String SOFTWARE_H264 = "libopenh264";

    /**
     * Encodeur Windows Media Foundation H265 qu'on cherche à remplacer par du matériel.
     * hevc_mf échoue systématiquement (avcodec_send_frame error -542398533) sur Windows 11
     * dans la build JavaCV/FFmpeg utilisée par Flashback — zéro export réussi en télémétrie.
     */
    public static final String SOFTWARE_HEVC_MF = "hevc_mf";

    private EncoderPromotion() {}

    /**
     * Décision de promotion H264 : libopenh264 → encodeur HW H264.
     *
     * @param current      encodeur actuellement configuré sur le recorder (peut être null)
     * @param enabled      flag de config {@code promoteSoftwareToHardwareEncode}
     * @param hwAvailable  meilleur encodeur HW H264 utilisable détecté (vide si aucun)
     * @return l'encodeur HW vers lequel promouvoir, ou vide si on ne promeut pas
     */
    public static Optional<String> choose(String current, boolean enabled, Optional<String> hwAvailable) {
        if (!enabled) return Optional.empty();
        if (!SOFTWARE_H264.equals(current)) return Optional.empty();
        return hwAvailable;
    }

    /**
     * Décision de promotion H265 : hevc_mf → encodeur HW H265 (hevc_nvenc / hevc_qsv).
     *
     * @param current      encodeur actuellement configuré sur le recorder (peut être null)
     * @param enabled      flag de config {@code promoteSoftwareToHardwareEncode}
     * @param hwAvailable  meilleur encodeur HW H265 utilisable détecté (vide si aucun)
     * @return l'encodeur HW vers lequel promouvoir, ou vide si on ne promeut pas
     */
    public static Optional<String> chooseHevc(String current, boolean enabled, Optional<String> hwAvailable) {
        if (!enabled) return Optional.empty();
        if (!SOFTWARE_HEVC_MF.equals(current)) return Optional.empty();
        return hwAvailable;
    }
}
