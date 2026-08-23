package fr.zeffut.flashbackturbo.mixin.encoder;

import com.moulberry.flashback.exporting.AsyncFFmpegVideoWriter;
import com.moulberry.flashback.exporting.FlashbackFFmpegFrameRecorder;
import fr.zeffut.flashbackturbo.FlashbackTurboClient;
import fr.zeffut.flashbackturbo.config.TurboConfig;
import fr.zeffut.flashbackturbo.encoder.EncoderTuning;
import fr.zeffut.flashbackturbo.telemetry.Telemetry;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * H6+H9+H11+H11b for Flashback 26.2.
 *
 * <p>Flashback 0.43.x moved FFmpeg setup from the constructor into
 * {@code tryStart(int)} and replaced JavaCV's public recorder type with
 * {@link FlashbackFFmpegFrameRecorder}. This redirect keeps the original
 * fail-safe behavior: tune/promote immediately before {@code recorder.start()},
 * then fall back to the original encoder if a promoted hardware encoder fails.
 */
@Mixin(AsyncFFmpegVideoWriter.class)
public abstract class AsyncFFmpegVideoWriterMixin {

    @Unique
    private static volatile boolean flashbackturbo$hwProbeReported = false;

    /**
     * H4 — 26.2 no longer has a folded 3840×2160 constant. The cap is now
     * delegated to EncoderQuirks.maximumFrameArea(encoder), so lift that result
     * instead while keeping Flashback's minimum-frame-size quirks intact.
     */
    @Redirect(
        method = "tryStart",
        at = @At(
            value = "INVOKE",
            target = "Lcom/moulberry/flashback/exporting/EncoderQuirks;maximumFrameArea(Ljava/lang/String;)I"
        ),
        require = 0
    )
    private int flashbackturbo$liftResolutionCap(String encoder) {
        int original = com.moulberry.flashback.exporting.EncoderQuirks.maximumFrameArea(encoder);
        if (TurboConfig.current().liftResolutionCap) {
            FlashbackTurboClient.LOGGER.info("[H4] cap résolution levé pour encoder={} (était {})", encoder, original);
            Telemetry.capture("fbt_resolution_cap_lifted", Map.of("encoder", encoder, "original_max_area", original));
            return Integer.MAX_VALUE;
        }
        return original;
    }

    @Redirect(
        method = "tryStart",
        at = @At(
            value = "INVOKE",
            target = "Lcom/moulberry/flashback/exporting/FlashbackFFmpegFrameRecorder;start()V"
        ),
        require = 0
    )
    private void flashbackturbo$tuneRecorderBeforeStart(FlashbackFFmpegFrameRecorder recorder)
            throws FlashbackFFmpegFrameRecorder.Exception {
        String promotedFrom = null;
        String promotedTo = null;

        try {
            if (TurboConfig.current().promoteSoftwareToHardwareEncode) {
                String current = flashbackturbo$getVideoCodecName(recorder);
                java.util.Optional<String> hw = fr.zeffut.flashbackturbo.encoder.EncoderPromotion.choose(
                    current, true, fr.zeffut.flashbackturbo.encoder.HwEncoderProbe.bestH264Hardware());

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

                if (promotedTo == null) {
                    java.util.Optional<String> hwHevc = fr.zeffut.flashbackturbo.encoder.EncoderPromotion.chooseHevc(
                        current, true, fr.zeffut.flashbackturbo.encoder.HwEncoderProbe.bestHevcHardware());
                    if (hwHevc.isPresent()) {
                        recorder.setVideoCodecName(hwHevc.get());
                        promotedFrom = current;
                        promotedTo = hwHevc.get();
                        FlashbackTurboClient.LOGGER.info("[H11b] promotion HEVC {} → {}", current, hwHevc.get());
                    }
                }
            }
        } catch (Throwable t) {
            FlashbackTurboClient.LOGGER.warn("[H11] promotion ignorée (fail-safe)", t);
            promotedFrom = null;
            promotedTo = null;
        }

        if (promotedTo != null) {
            try {
                fr.zeffut.flashbackturbo.encoder.ExportContextHolder.recordPromotion(promotedFrom, promotedTo);
            } catch (Throwable ignored) {}
        }

        if (TurboConfig.current().tuneFFmpegThreading) {
            EncoderTuning.applyThreadingTunes(recorder);
        }

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
                recorder.start();
            } else if (t instanceof FlashbackFFmpegFrameRecorder.Exception e) {
                throw e;
            } else {
                throw new RuntimeException(t);
            }
        }
    }

    @Unique
    private static String flashbackturbo$getVideoCodecName(FlashbackFFmpegFrameRecorder recorder) {
        try {
            Field f = FlashbackFFmpegFrameRecorder.class.getDeclaredField("videoCodecName");
            f.setAccessible(true);
            Object value = f.get(recorder);
            return value instanceof String s ? s : null;
        } catch (Throwable t) {
            FlashbackTurboClient.LOGGER.debug("[H11] lecture réflexive videoCodecName ignorée", t);
            return null;
        }
    }
}
