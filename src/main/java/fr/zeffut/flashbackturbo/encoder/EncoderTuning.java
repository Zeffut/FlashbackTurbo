package fr.zeffut.flashbackturbo.encoder;

import fr.zeffut.flashbackturbo.FlashbackTurboClient;
import org.bytedeco.javacv.FFmpegFrameRecorder;

/**
 * Tunes FFmpeg lossless par encoder. Applique uniquement des options qui modifient
 * le scheduling / la concurrence, jamais la qualité visuelle de l'output.
 *
 * <p>Sources :
 * <ul>
 *   <li>libx264 : {@code threads=auto} laisse x264 choisir (default déjà bon, mais explicite par sécurité)</li>
 *   <li>libx265 : idem</li>
 *   <li>nvenc : {@code delay=0} pour réduire la latence sans toucher au bitrate/qualité</li>
 *   <li>videotoolbox : peu d'options exposées, par défaut déjà thread-correct</li>
 *   <li>qsv : {@code async_depth} contrôle le nombre de frames en vol, lossless</li>
 *   <li>libsvtav1 : {@code preset} touche la qualité — on n'y touche pas</li>
 * </ul>
 *
 * <p>Toutes les options listées ici sont documentées dans la doc FFmpeg comme n'affectant pas
 * la sortie binaire encodée pour un même input.
 */
public final class EncoderTuning {

    private static final int CPU_CORES = Math.max(1, Runtime.getRuntime().availableProcessors());

    private EncoderTuning() {}

    /** Applique les tunes adaptés à l'encoder courant du recorder. À appeler avant {@code recorder.start()}. */
    public static void applyThreadingTunes(FFmpegFrameRecorder recorder) {
        String encoder = recorder.getVideoCodecName();
        if (encoder == null) {
            return;
        }

        // Universel : explicite "auto" si non set.
        if (recorder.getVideoOption("threads") == null) {
            recorder.setVideoOption("threads", "auto");
        }

        switch (encoder) {
            case "libx264", "libx265" -> {
                // x264/x265 sont déjà bien threadés en auto. Rien d'autre à toucher
                // sans risquer un changement de qualité (sliced threads, etc.).
            }
            case "h264_nvenc", "hevc_nvenc", "av1_nvenc" -> {
                // delay=0 réduit le retard sans changer la sortie.
                if (recorder.getVideoOption("delay") == null) {
                    recorder.setVideoOption("delay", "0");
                }
            }
            case "h264_qsv", "hevc_qsv", "av1_qsv" -> {
                // async_depth >1 permet plus de parallélisme sans altérer le bitstream.
                if (recorder.getVideoOption("async_depth") == null) {
                    recorder.setVideoOption("async_depth", Integer.toString(Math.min(8, CPU_CORES)));
                }
            }
            case "h264_amf", "hevc_amf", "av1_amf" -> {
                // AMF a query_timeout pour non-blocking submit, lossless.
                if (recorder.getVideoOption("query_timeout") == null) {
                    recorder.setVideoOption("query_timeout", "1000");
                }
            }
            default -> {
                // Encoders inconnus (videotoolbox, libaom-av1, libsvtav1) : on ne touche à rien.
            }
        }

        FlashbackTurboClient.LOGGER.info("[H6] tunes appliqués pour encoder={} (threads={})",
            encoder, recorder.getVideoOption("threads"));
    }
}
