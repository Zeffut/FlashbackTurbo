package fr.zeffut.flashbackturbo.mixin.png;

import com.mojang.blaze3d.platform.NativeImage;
import com.moulberry.flashback.exporting.ExportSettings;
import com.moulberry.flashback.exporting.ImageFrame;
import com.moulberry.flashback.exporting.PNGSequenceVideoWriter;
import fr.zeffut.flashbackturbo.FlashbackTurboClient;
import fr.zeffut.flashbackturbo.config.TurboConfig;
import fr.zeffut.flashbackturbo.png.ParallelPngEncoder;
import fr.zeffut.flashbackturbo.png.PngPathResolver;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * H2 + H3 + H7 — Refonte du PNG writer for Flashback 26.2.
 *
 * <p>Flashback 0.43.x changed {@code VideoWriter.encode} from NativeImage to
 * {@link ImageFrame}. We convert to Flashback's opaque RGBA NativeImage at the
 * mixin boundary, then keep the existing parallel PNG writer path. This matches
 * the 26.2 vanilla PNG sequence path, which also writes NativeImage frames.
 */
@Mixin(PNGSequenceVideoWriter.class)
public abstract class PngSequenceVideoWriterMixin {

    @Shadow @Final private ExportSettings settings;
    @Shadow private int sequenceNumber;
    @Shadow @Final private AtomicBoolean finishEncodeThread;
    @Shadow @Final private AtomicBoolean finishedWriting;

    @Unique private ParallelPngEncoder flashbackturbo$encoder;
    @Unique private PngPathResolver flashbackturbo$pathResolver;
    @Unique private boolean flashbackturbo$active;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void flashbackturbo$initParallel(ExportSettings settings, CallbackInfo ci) {
        if (!TurboConfig.current().parallelPngWriter) {
            this.flashbackturbo$active = false;
            return;
        }
        this.flashbackturbo$encoder = new ParallelPngEncoder(settings.transparent());
        this.flashbackturbo$pathResolver = new PngPathResolver(settings);
        this.flashbackturbo$active = true;
    }

    /** Saute le démarrage du Thread vanilla quand turbo est actif — notre pool prend le relais. */
    @Redirect(
        method = "<init>",
        at = @At(value = "INVOKE", target = "Ljava/lang/Thread;start()V"),
        require = 0
    )
    private void flashbackturbo$maybeStartVanillaThread(Thread vanilla) {
        if (TurboConfig.current().parallelPngWriter) {
            FlashbackTurboClient.LOGGER.info("[H2] thread vanilla PNG bypassé, pool parallèle actif");
            return;
        }
        vanilla.start();
    }

    @Inject(method = "encode", at = @At("HEAD"), cancellable = true)
    private void flashbackturbo$encodeParallel(ImageFrame frame, CallbackInfo ci) {
        if (!this.flashbackturbo$active) {
            return;
        }
        if (frame.audioBuffer != null) {
            frame.close();
            throw new RuntimeException("PNG Sequence does not support encoding audio");
        }
        if (this.finishEncodeThread.get() || this.finishedWriting.get()) {
            frame.close();
            throw new IllegalStateException("Cannot encode after finish()");
        }

        NativeImage image = frame.toOpaqueRgbaU8NativeImage();
        frame.close();

        this.sequenceNumber += 1;
        var path = this.flashbackturbo$pathResolver.resolve(this.sequenceNumber);
        this.flashbackturbo$encoder.submit(image, path);
        ci.cancel();
    }

    @Inject(method = "finish", at = @At("HEAD"), cancellable = true)
    private void flashbackturbo$finishParallel(Consumer<String> progressConsumer, CallbackInfo ci) {
        if (!this.flashbackturbo$active) {
            return;
        }
        this.flashbackturbo$encoder.finish();
        this.flashbackturbo$encoder.close();
        this.finishEncodeThread.set(true);
        this.finishedWriting.set(true);
        if (progressConsumer != null) {
            progressConsumer.accept("parallel PNG writer");
        }
        ci.cancel();
    }

    @Inject(method = "close", at = @At("HEAD"), cancellable = true)
    private void flashbackturbo$closeParallel(CallbackInfo ci) {
        if (!this.flashbackturbo$active) {
            return;
        }
        if (this.flashbackturbo$encoder != null) {
            this.flashbackturbo$encoder.close();
            this.flashbackturbo$encoder = null;
        }
        this.finishEncodeThread.set(true);
        this.finishedWriting.set(true);
        ci.cancel();
    }
}
