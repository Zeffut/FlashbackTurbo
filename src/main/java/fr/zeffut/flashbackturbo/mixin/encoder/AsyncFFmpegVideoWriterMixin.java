package fr.zeffut.flashbackturbo.mixin.encoder;

import com.moulberry.flashback.exporting.AsyncFFmpegVideoWriter;
import fr.zeffut.flashbackturbo.FlashbackTurboClient;
import fr.zeffut.flashbackturbo.config.TurboConfig;
import fr.zeffut.flashbackturbo.encoder.EncoderTuning;
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

    @ModifyConstant(
        method = "<init>",
        constant = @Constant(intValue = 3840 * 2160),
        require = 0   // tolère l'absence (versions Flashback futures qui retireraient le cap)
    )
    private int flashbackturbo$liftResolutionCap(int original) {
        if (TurboConfig.current().liftResolutionCap) {
            FlashbackTurboClient.LOGGER.info("[H4] cap résolution levé (était {})", original);
            return Integer.MAX_VALUE;
        }
        return original;
    }

    /**
     * H6 — Applique les tunes de threading FFmpeg juste avant recorder.start().
     * Lossless car ne touche qu'au scheduling interne / parallélisme de l'encoder.
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
        if (TurboConfig.current().tuneFFmpegThreading) {
            EncoderTuning.applyThreadingTunes(recorder);
        }
        recorder.start();
    }
}
