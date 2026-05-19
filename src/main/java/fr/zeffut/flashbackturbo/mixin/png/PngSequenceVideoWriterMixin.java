package fr.zeffut.flashbackturbo.mixin.png;

import com.moulberry.flashback.exporting.ExportSettings;
import com.moulberry.flashback.exporting.PNGSequenceVideoWriter;
import fr.zeffut.flashbackturbo.FlashbackTurboClient;
import fr.zeffut.flashbackturbo.config.TurboConfig;
import fr.zeffut.flashbackturbo.png.ParallelPngEncoder;
import fr.zeffut.flashbackturbo.png.PngPathResolver;
import net.minecraft.client.texture.NativeImage;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.FloatBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * H2 + H3 + H7 — Refonte du PNG writer :
 * <ul>
 *   <li>H2 : encodage parallèle sur N-1 threads au lieu d'un seul</li>
 *   <li>H3 : niveau zlib configurable (default L1 = rapide, vs L6 hardcodé Mojang)</li>
 *   <li>H7 : pas d'alpha cleanup (PNG color type 2 = RGB si transparency off, plus de canal alpha à nettoyer)</li>
 * </ul>
 *
 * <p>Stratégie : on saute le démarrage du thread vanilla et on remplace le flux
 * complet par notre {@link ParallelPngEncoder}. Si {@code TurboConfig.current().parallelPngWriter}
 * est désactivé, le comportement vanilla reste intact.
 *
 * <p>Lossless visuel : sortie décodée strictement identique aux pixels source. Le seul
 * delta vs vanilla (avec {@code transparent=false}) est que notre PNG est en RGB (3 canaux)
 * au lieu de RGBA avec alpha=255, ce qui produit un fichier plus petit mais visuellement identique.
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

    /**
     * Saute le démarrage du Thread vanilla quand turbo est actif — notre pool prend le relais.
     */
    @Redirect(
        method = "<init>",
        at = @At(value = "INVOKE", target = "Ljava/lang/Thread;start()V"),
        require = 0
    )
    private void flashbackturbo$maybeStartVanillaThread(Thread vanilla) {
        if (TurboConfig.current().parallelPngWriter) {
            // Notre encoder parallèle gère tout, le thread vanilla resterait à idle.
            FlashbackTurboClient.LOGGER.info("[H2] thread vanilla PNG bypassé, pool parallèle actif");
            return;
        }
        vanilla.start();
    }

    @Inject(method = "encode", at = @At("HEAD"), cancellable = true)
    private void flashbackturbo$encodeParallel(NativeImage src, FloatBuffer audioBuffer, CallbackInfo ci) {
        if (!this.flashbackturbo$active) {
            return;
        }
        if (audioBuffer != null) {
            src.close();
            throw new RuntimeException("PNG Sequence does not support encoding audio");
        }
        if (this.finishEncodeThread.get() || this.finishedWriting.get()) {
            src.close();
            throw new IllegalStateException("Cannot encode after finish()");
        }

        this.sequenceNumber += 1;
        var path = this.flashbackturbo$pathResolver.resolve(this.sequenceNumber);
        this.flashbackturbo$encoder.submit(src, path);
        ci.cancel();
    }

    @Inject(method = "finish", at = @At("HEAD"), cancellable = true)
    private void flashbackturbo$finishParallel(CallbackInfo ci) {
        if (!this.flashbackturbo$active) {
            return;
        }
        this.flashbackturbo$encoder.finish();
        this.flashbackturbo$encoder.close();
        this.finishEncodeThread.set(true);
        this.finishedWriting.set(true);
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
