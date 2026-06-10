package fr.zeffut.flashbackturbo.mixin.encoder;

import com.moulberry.flashback.exporting.AsyncFFmpegVideoWriter;
import fr.zeffut.flashbackturbo.FlashbackTurboClient;
import fr.zeffut.flashbackturbo.config.TurboConfig;
import fr.zeffut.flashbackturbo.encoder.EncoderTuning;
import fr.zeffut.flashbackturbo.telemetry.Telemetry;
import java.util.HashMap;
import java.util.Map;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * H4 — Lever le cap de résolution 4K silencieux dans AsyncFFmpegVideoWriter.
 *
 * <p>Le constructeur original contient :
 * <pre>{@code
 * final int maxResolutionArea = 3840 * 2160;
 * if (width*height > maxResolutionArea) {
 *     double factor = (width*height) / (double) maxResolutionArea;
 *     factor = Math.sqrt(factor);
 *     width = (int) Math.floor(width / factor);
 *     height = (int) Math.floor(height / factor);
 * }
 * }</pre>
 *
 * <p>La constante {@code 3840 * 2160} = {@code 8294400} est folded à la compilation.
 * On la remplace par {@link Integer#MAX_VALUE} pour que la branche de downscale
 * ne soit jamais empruntée. L'utilisateur récupère exactement la résolution demandée.
 *
 * <p>Aucune perte de qualité — au contraire, on évite un downscale silencieux qui
 * détériorait la qualité au-delà de 4K.
 */
@Mixin(AsyncFFmpegVideoWriter.class)
public abstract class AsyncFFmpegVideoWriterMixin {

    @org.spongepowered.asm.mixin.Unique
    private static volatile boolean flashbackturbo$hwProbeReported = false;

    @ModifyConstant(
        method = "<init>",
        constant = @Constant(intValue = 3840 * 2160),
        require = 0   // tolère l'absence (versions Flashback futures qui retireraient le cap)
    )
    private int flashbackturbo$liftResolutionCap(int original) {
        if (TurboConfig.current().liftResolutionCap) {
            FlashbackTurboClient.LOGGER.info("[H4] cap résolution levé (était {})", original);
            Telemetry.capture("fbt_resolution_cap_lifted", Map.of("original_max_area", original));
            return Integer.MAX_VALUE;
        }
        return original;
    }

    /**
     * H6+H11 — Applique les tunes de threading FFmpeg et tente la promotion HW
     * juste avant recorder.start(). Lossless / fail-safe : aucune exception de ce
     * code ne peut empêcher le démarrage de l'export (fallback software garanti).
     */
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
                    Map<String, Object> pp = new HashMap<>();
                    pp.put("probed", pr.probed());
                    pp.put("selected", pr.selected());
                    pp.put("probe_ms", pr.probeMs());
                    Telemetry.capture("fbt_hw_promotion_probe", pp);
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
                fr.zeffut.flashbackturbo.encoder.ExportContextHolder.recordPromotion(promotedFrom, promotedTo);
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
                try { fr.zeffut.flashbackturbo.encoder.ExportContextHolder.recordPromotion(null, null); } catch (Throwable ignored) {}
                recorder.start(); // si ça relève ici, on laisse remonter (comportement vanilla)
            } else {
                throw t;
            }
        }
    }
}
