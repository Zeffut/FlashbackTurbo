package fr.zeffut.flashbackturbo.mixin.gui;

import com.moulberry.flashback.exporting.ExportJob;
import fr.zeffut.flashbackturbo.config.TurboConfig;
import fr.zeffut.flashbackturbo.gui.ExportProgressScreen;
import net.minecraft.client.MinecraftClient;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * H8 — Affiche {@link ExportProgressScreen} pendant ExportJob.run() et force un
 * render + swap toutes les ~100ms pour débloquer visuellement l'utilisateur.
 *
 * <p>Sans ce hook, MC's main loop est bloqué dans ExportJob.run() — l'utilisateur
 * voit le dernier frame pré-export figé jusqu'à la fin de l'export.
 */
@Mixin(ExportJob.class)
public abstract class ExportJobProgressMixin {

    @Unique private static final long FLASHBACKTURBO$REDRAW_INTERVAL_NS = 100_000_000L; // ~10 fps
    @Unique private long flashbackturbo$lastRedrawNs = 0L;

    @Inject(method = "run", at = @At("HEAD"), require = 0)
    private void flashbackturbo$installProgressScreen(CallbackInfo ci) {
        if (!TurboConfig.current().showExportProgressOverlay) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc != null) {
            mc.execute(() -> mc.setScreen(new ExportProgressScreen()));
        }
        this.flashbackturbo$lastRedrawNs = 0L;
    }

    @Inject(method = "run", at = @At("RETURN"), require = 0)
    private void flashbackturbo$removeProgressScreenOnReturn(CallbackInfo ci) {
        flashbackturbo$cleanup();
    }

    @Inject(method = "run", at = @At("THROW"), require = 0)
    private void flashbackturbo$removeProgressScreenOnThrow(CallbackInfo ci) {
        flashbackturbo$cleanup();
    }

    @Unique
    private void flashbackturbo$cleanup() {
        if (!TurboConfig.current().showExportProgressOverlay) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc != null && mc.currentScreen instanceof ExportProgressScreen) {
            mc.execute(() -> mc.setScreen(null));
        }
    }

    /**
     * Après chaque runClientTick : si notre overlay est actif, throttle et force
     * un render + swapBuffers pour rafraîchir la fenêtre visible.
     *
     * <p>Sans le swapBuffers, la fenêtre reste figée même si on render dans le back
     * buffer — MC ne fait son swap qu'en sortie du main runTick, qu'on n'atteint pas
     * tant que ExportJob.run() ne rend pas la main.
     */
    @Inject(method = "runClientTick", at = @At("RETURN"), require = 0)
    private void flashbackturbo$forceVisibleRedraw(boolean frozen, CallbackInfo ci) {
        if (!TurboConfig.current().showExportProgressOverlay) return;

        long now = System.nanoTime();
        if (now - this.flashbackturbo$lastRedrawNs < FLASHBACKTURBO$REDRAW_INTERVAL_NS) {
            return;
        }
        this.flashbackturbo$lastRedrawNs = now;

        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null) return;

        // Re-asserter notre Screen si ExportJob a fait setScreen(null) en interne.
        if (!(mc.currentScreen instanceof ExportProgressScreen)) {
            mc.setScreen(new ExportProgressScreen());
        }

        try {
            mc.gameRenderer.render(mc.getRenderTickCounter(), true);
            GLFW.glfwSwapBuffers(mc.getWindow().getHandle());
            GLFW.glfwPollEvents();
        } catch (Throwable t) {
            // En cas de problème de rendu (context GL, etc.) on continue silencieusement
            // pour ne pas tuer l'export. L'absence d'overlay vaut mieux qu'un crash.
        }
    }
}
