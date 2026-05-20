package fr.zeffut.flashbackturbo.mixin.exporting;

import com.moulberry.flashback.exporting.ExportJob;
import com.moulberry.flashback.playback.ReplayServer;
import fr.zeffut.flashbackturbo.FlashbackTurboClient;
import fr.zeffut.flashbackturbo.config.TurboConfig;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * H10 — Contourne une race de Flashback dans {@code ExportJob.setup()}.
 *
 * <p>Au début de l'export, Flashback fait :
 * <pre>{@code
 *   setServerTickAndWait(replayServer, startTick - 40, true);
 *   runClientTick(false);
 *   ... for (Entity e : mc.level.entitiesForRendering()) ...   // NPE si mc.level == null
 * }</pre>
 *
 * <p>Un seul {@code runClientTick()} ne suffit pas toujours à reconnecter le client au monde
 * du replay : la séquence login → configuration → play → join-level s'étale sur plusieurs
 * ticks, surtout sur un replay serveur lourd (beaucoup de datapacks à recharger).
 * {@code mc.level} reste {@code null} → {@code NullPointerException}, l'export plante avant
 * la première frame.
 *
 * <p>Ce hook s'injecte juste après le premier {@code runClientTick()} et, si {@code mc.level}
 * est encore {@code null}, continue à pomper {@code runClientTick()} jusqu'à ce que le niveau
 * soit chargé (timeout 60 s). Quand le niveau est déjà prêt — cas normal — c'est un no-op.
 *
 * <p>C'est un bug de Flashback, pas de FlashbackTurbo : ce hook ne fait que rendre l'export
 * robuste face à un rechargement de monde lent.
 */
@Mixin(ExportJob.class)
public abstract class ExportJobMixin {

    @Invoker("runClientTick")
    abstract void flashbackturbo$runClientTick(boolean paused);

    @Inject(
        method = "setup",
        at = @At(
            value = "INVOKE",
            target = "Lcom/moulberry/flashback/exporting/ExportJob;runClientTick(Z)V",
            ordinal = 0,
            shift = At.Shift.AFTER
        ),
        require = 0
    )
    private void flashbackturbo$ensureLevelLoaded(ReplayServer replayServer, CallbackInfo ci) {
        if (!TurboConfig.current().fixExportSetupRace) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.world != null) return;

        long deadlineNs = System.nanoTime() + 60_000_000_000L; // 60 s de garde
        int ticks = 0;
        while (mc.world == null && System.nanoTime() < deadlineNs) {
            flashbackturbo$runClientTick(false);
            ticks++;
            try {
                // Laisse le thread du ReplayServer progresser entre deux ticks client.
                Thread.sleep(2L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        if (mc.world == null) {
            FlashbackTurboClient.LOGGER.warn(
                "[H10] mc.level toujours null après {} runClientTick — race ExportJob.setup non contournée", ticks);
        } else if (ticks > 0) {
            FlashbackTurboClient.LOGGER.info(
                "[H10] monde du replay chargé après {} runClientTick supplémentaires — race ExportJob.setup contournée", ticks);
        }
    }
}
