package fr.zeffut.flashbackturbo.mixin.gui;

import com.moulberry.flashback.exporting.AsyncFFmpegVideoWriter;
import fr.zeffut.flashbackturbo.FlashbackTurboClient;
import fr.zeffut.flashbackturbo.config.TurboConfig;
import fr.zeffut.flashbackturbo.gui.SavingExportScreen;
import fr.zeffut.flashbackturbo.telemetry.Telemetry;
import java.util.Map;
import net.minecraft.client.MinecraftClient;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.locks.LockSupport;

/**
 * H8 take 2 — Pendant le post-export (quand AsyncFFmpegVideoWriter.finish() drain les
 * queues encode/rescale et attend les threads), le render thread est bloqué. Flashback's
 * finishFrame ne tourne plus → écran figé sur la dernière frame "Saving…" sans animation.
 *
 * <p>Cette Mixin :
 * <ul>
 *   <li>HEAD finish() : installe {@link SavingExportScreen} avec animation des dots.</li>
 *   <li>@Redirect sur les LockSupport.parkNanos dans les wait loops : après le park,
 *       on appelle render + glfwSwapBuffers throttled à 250ms pour animer l'écran.</li>
 *   <li>TAIL finish() : laisse Flashback faire son setScreen(null) habituel.</li>
 * </ul>
 *
 * <p>Différence majeure vs H8 v1 : ici on agit UNIQUEMENT pendant le post-export
 * (jamais pendant la boucle export elle-même). Donc pas de conflit avec le FBO de
 * Flashback (qui n'est plus actif à ce point), et pas de double-tick (gameRenderer.render
 * timer = false).
 */
@Mixin(AsyncFFmpegVideoWriter.class)
public abstract class AsyncFFmpegFinishMixin {

    @Unique private static final long FBT$REDRAW_INTERVAL_NS = 250_000_000L; // 4 fps
    @Unique private long fbt$lastRedrawNs = 0L;
    @Unique private boolean fbt$savingActive = false;
    @Unique private long fbt$finishStartNs = 0L;

    @Inject(method = "finish", at = @At("HEAD"), require = 0)
    private void fbt$installSavingScreen(CallbackInfo ci) {
        if (!TurboConfig.current().showExportProgressOverlay) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null) return;
        mc.setScreen(new SavingExportScreen());
        this.fbt$savingActive = true;
        this.fbt$finishStartNs = System.nanoTime();
        this.fbt$lastRedrawNs = 0L;
        // Premier render immédiat pour montrer l'écran tout de suite.
        fbt$tryRender(mc);
    }

    /**
     * Intercepte chaque {@code LockSupport.parkNanos(String, long)} dans les wait loops
     * de finish() (3 occurrences). On park comme prévu, puis on render+swap si throttle
     * écoulé. Pas de modification du comportement de wait.
     */
    @Redirect(
        method = "finish",
        at = @At(value = "INVOKE", target = "Ljava/util/concurrent/locks/LockSupport;parkNanos(Ljava/lang/String;J)V"),
        require = 0
    )
    private void fbt$parkAndAnimate(String blocker, long nanos) {
        LockSupport.parkNanos(blocker, nanos);
        if (!this.fbt$savingActive) return;

        long now = System.nanoTime();
        if (now - this.fbt$lastRedrawNs < FBT$REDRAW_INTERVAL_NS) return;
        this.fbt$lastRedrawNs = now;

        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null) return;
        fbt$tryRender(mc);
    }

    @Inject(method = "finish", at = @At("TAIL"), require = 0)
    private void fbt$disableSaving(CallbackInfo ci) {
        long now = System.nanoTime();
        if (this.fbt$savingActive && this.fbt$finishStartNs > 0L) {
            long shownMs = (now - this.fbt$finishStartNs) / 1_000_000L;
            Telemetry.capture("fbt_saving_overlay_shown", Map.of("shown_ms", shownMs));
        }
        this.fbt$savingActive = false;

        // NB : fbt_export_finished + ExportContext.end() sont désormais émis au niveau ExportJob
        // (run() RETURN), qui couvre AUSSI le PNG sequence writer (lequel n'a pas de finish()
        // côté FFmpeg). Ici on ne gère plus que l'overlay "Saving…" spécifique au writer FFmpeg.

        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc != null && mc.currentScreen instanceof SavingExportScreen) {
            mc.setScreen(null);
        }
    }

    @Unique
    private void fbt$tryRender(MinecraftClient mc) {
        try {
            // Re-asserter si Flashback a remplacé notre écran entre-temps.
            if (!(mc.currentScreen instanceof SavingExportScreen)) {
                mc.setScreen(new SavingExportScreen());
            }
            mc.gameRenderer.render(mc.getRenderTickCounter(), false);
            GLFW.glfwSwapBuffers(mc.getWindow().getHandle());
        } catch (Throwable t) {
            FlashbackTurboClient.LOGGER.debug("[H8] render fail (ignored)", t);
        }
    }
}
