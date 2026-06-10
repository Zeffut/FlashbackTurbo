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
