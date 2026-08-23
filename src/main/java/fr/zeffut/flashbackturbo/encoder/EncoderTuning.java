package fr.zeffut.flashbackturbo.encoder;

import com.moulberry.flashback.exporting.FlashbackFFmpegFrameRecorder;
import fr.zeffut.flashbackturbo.FlashbackTurboClient;
import fr.zeffut.flashbackturbo.config.TurboConfig;

import java.lang.reflect.Field;
import java.util.Map;

/**
 * Tunes FFmpeg lossless par encoder. Applique uniquement des options qui modifient
 * le scheduling / la concurrence, jamais la qualité visuelle de l'output.
 *
 * <p>Flashback 26.2 replaced JavaCV's public {@code FFmpegFrameRecorder} with its
 * own {@link FlashbackFFmpegFrameRecorder}. That wrapper exposes setters but not
 * getters, so this class reads the private option maps by reflection to preserve
 * the older "only set when absent" behavior without changing Flashback itself.
 */
public final class EncoderTuning {

    private static final int CPU_CORES = Math.max(1, Runtime.getRuntime().availableProcessors());

    private EncoderTuning() {}

    /** Nombre de slices OpenH264 = cœurs plafonné à 8, au moins 1. Pur, testable. */
    public static int openh264Slices(int cores) {
        return Math.max(1, Math.min(8, cores));
    }

    /** Applique les tunes adaptés à l'encoder courant du recorder. À appeler avant {@code recorder.start()}. */
    public static void applyThreadingTunes(FlashbackFFmpegFrameRecorder recorder) {
        String encoder = getStringField(recorder, "videoCodecName");
        if (encoder == null) {
            return;
        }

        // MF (Windows Media Foundation) gère son threading en interne. Forcer
        // threads=auto peut provoquer avcodec_send_frame() error -542398533.
        boolean isMfEncoder = "h264_mf".equals(encoder) || "hevc_mf".equals(encoder);
        boolean isHwEncoder = false;

        switch (encoder) {
            case "libx264", "libx265" -> {
                // x264/x265 sont déjà bien threadés en auto. Rien d'autre à toucher.
            }
            case "h264_nvenc", "hevc_nvenc", "av1_nvenc" -> {
                if (getVideoOption(recorder, "delay") == null) {
                    tryVideoOption(recorder, "delay", "0");
                }
                isHwEncoder = true;
            }
            case "h264_qsv", "hevc_qsv", "av1_qsv" -> {
                if (getVideoOption(recorder, "async_depth") == null) {
                    tryVideoOption(recorder, "async_depth", Integer.toString(Math.min(8, CPU_CORES)));
                }
                isHwEncoder = true;
            }
            case "h264_amf", "hevc_amf", "av1_amf" -> {
                if (getVideoOption(recorder, "query_timeout") == null) {
                    tryVideoOption(recorder, "query_timeout", "1000");
                }
                isHwEncoder = true;
            }
            case "h264_videotoolbox", "hevc_videotoolbox", "h264_mf", "hevc_mf" -> {
                isHwEncoder = true;
            }
            case "libopenh264" -> {
                if (getVideoOption(recorder, "slices") == null) {
                    tryVideoOption(recorder, "slices", Integer.toString(openh264Slices(CPU_CORES)));
                }
            }
            default -> {
                // Encoders inconnus: ne rien toucher.
            }
        }

        if (!isMfEncoder && getVideoOption(recorder, "threads") == null) {
            tryVideoOption(recorder, "threads", "auto");
        }

        // H9 : Fragmented MP4 sur HW encoders. Flashback's 26.2 wrapper calls this
        // a muxer option rather than JavaCV's generic setOption("movflags", ...).
        if (isHwEncoder && TurboConfig.current().useFragmentedMp4OnHwEncoders) {
            if (getMuxerOption(recorder, "movflags") == null) {
                if (tryMuxerOption(recorder, "movflags", "+frag_keyframe+empty_moov")) {
                    FlashbackTurboClient.LOGGER.info("[H9] fragmented MP4 actif (movflags=+frag_keyframe+empty_moov)");
                }
            }
        }

        FlashbackTurboClient.LOGGER.info("[H6] tunes appliqués pour encoder={} (threads={}, hw={})",
            encoder, getVideoOption(recorder, "threads"), isHwEncoder);
    }

    private static void tryVideoOption(FlashbackFFmpegFrameRecorder recorder, String key, String value) {
        try {
            recorder.setVideoOption(key, value);
        } catch (Throwable t) {
            FlashbackTurboClient.LOGGER.warn("[H6] setVideoOption({}={}) refusée par FFmpeg, ignorée : {}", key, value, t.toString());
        }
    }

    private static boolean tryMuxerOption(FlashbackFFmpegFrameRecorder recorder, String key, String value) {
        try {
            recorder.setMuxerOption(key, value);
            return true;
        } catch (Throwable t) {
            FlashbackTurboClient.LOGGER.warn("[H6] setMuxerOption({}={}) refusée par FFmpeg, ignorée : {}", key, value, t.toString());
            return false;
        }
    }

    private static String getVideoOption(FlashbackFFmpegFrameRecorder recorder, String key) {
        return getMapValue(recorder, "videoOptions", key);
    }

    private static String getMuxerOption(FlashbackFFmpegFrameRecorder recorder, String key) {
        return getMapValue(recorder, "options", key);
    }

    @SuppressWarnings("unchecked")
    private static String getMapValue(FlashbackFFmpegFrameRecorder recorder, String fieldName, String key) {
        try {
            Field f = FlashbackFFmpegFrameRecorder.class.getDeclaredField(fieldName);
            f.setAccessible(true);
            Object value = f.get(recorder);
            if (value instanceof Map<?, ?> map) {
                Object result = map.get(key);
                return result instanceof String s ? s : null;
            }
        } catch (Throwable t) {
            FlashbackTurboClient.LOGGER.debug("[H6] accès réflexif {} ignoré", fieldName, t);
        }
        return null;
    }

    private static String getStringField(FlashbackFFmpegFrameRecorder recorder, String fieldName) {
        try {
            Field f = FlashbackFFmpegFrameRecorder.class.getDeclaredField(fieldName);
            f.setAccessible(true);
            Object value = f.get(recorder);
            return value instanceof String s ? s : null;
        } catch (Throwable t) {
            FlashbackTurboClient.LOGGER.debug("[H6] accès réflexif {} ignoré", fieldName, t);
            return null;
        }
    }
}
