package fr.zeffut.flashbackturbo.mixin.exporting;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.moulberry.flashback.exporting.ExportJob;
import com.moulberry.flashback.exporting.ExportSettings;
import com.moulberry.flashback.exporting.SaveableFramebufferQueue;
import com.moulberry.flashback.exporting.VideoWriter;
import com.moulberry.flashback.playback.ReplayServer;
import fr.zeffut.flashbackturbo.FlashbackTurboClient;
import fr.zeffut.flashbackturbo.config.TurboConfig;
import fr.zeffut.flashbackturbo.telemetry.ExportContext;
import fr.zeffut.flashbackturbo.telemetry.Telemetry;
import net.minecraft.client.MinecraftClient;
import java.util.HashMap;
import java.util.Map;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
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

    @Shadow public int progressCount;
    @Shadow public int progressOutOf;

    @Shadow
    public abstract ExportSettings getSettings();

    @Inject(method = "setup", at = @At("HEAD"), require = 0)
    private void flashbackturbo$telemetryExportStart(ReplayServer replayServer, CallbackInfo ci) {
        // Filet de sécurité (option ii) : si un export précédent est encore "actif" ici, c'est
        // qu'il ne s'est jamais terminé proprement (ni RETURN normal, ni shutdown). On le signale
        // comme échoué AVANT de réinitialiser le contexte. Ne double-fire jamais avec le RETURN de
        // run() : celui-ci appelle end() → isActive() devient false.
        ExportContext ctx = Telemetry.export();
        if (ctx.isActive()) {
            ctx.put("reason", "incomplete");
            Telemetry.captureExportFailure("unknown", null);
        }

        ctx.begin(System.nanoTime());
        flashbackturbo$enrichFromSettings(ctx);
        Telemetry.capture("fbt_export_started", ctx.snapshot(System.nanoTime()));
    }

    /**
     * Renseigne le contexte avec les métadonnées d'export issues de {@link ExportSettings}
     * (record Flashback). Best-effort : tout échec est avalé, la télémétrie ne doit jamais
     * casser l'export.
     */
    @Unique
    private void flashbackturbo$enrichFromSettings(ExportContext ctx) {
        try {
            ExportSettings s = getSettings();
            if (s == null) return;
            if (s.container() != null) {
                ctx.put("container", s.container().name());
                ctx.put("format", s.container().extension());
            }
            if (s.codec() != null) ctx.put("codec", s.codec().name());
            if (s.encoder() != null) ctx.put("encoder", s.encoder());
            ctx.put("width", s.resolutionX());
            ctx.put("height", s.resolutionY());
            ctx.put("framerate", s.framerate());
            ctx.put("transparent", s.transparent());
            ctx.put("ssaa", s.ssaa());
            ctx.put("bitrate", s.bitrate());
            // Frames attendues = (endTick - startTick) * framerate / ticks-per-second (20).
            int tickSpan = s.endTick() - s.startTick();
            if (tickSpan > 0) {
                long expected = Math.round(tickSpan / 20.0 * s.framerate());
                ctx.put("frame_count", expected);
            }
        } catch (Throwable t) {
            FlashbackTurboClient.LOGGER.debug("[telemetry] enrichissement export ignoré", t);
        }
    }

    /**
     * Émis sur le RETURN NORMAL de {@code run()} — donc APRÈS le bloc finally, que l'export
     * soit allé au bout OU ait été annulé via "Hold [ESC] to cancel" (Flashback met alors
     * {@code running=false}, la boucle sort, run() retourne normalement). On distingue les deux
     * cas en comparant les frames produites à celles attendues.
     *
     * <p>En cas d'EXCEPTION dans run(), ce hook NE se déclenche PAS (l'exception remonte) : ce
     * cas est couvert par l'incomplete-detection (setup HEAD du prochain export + shutdown).
     */
    @Inject(method = "run", at = @At("RETURN"), require = 0)
    private void flashbackturbo$telemetryExportEnd(CallbackInfo ci) {
        ExportContext ctx = Telemetry.export();
        if (!ctx.isActive()) return;

        long now = System.nanoTime();
        Map<String, Object> props = new HashMap<>(ctx.snapshot(now));

        // Annulation : moins de frames rendues que prévu (progressOutOf > 0 et progressCount en deçà).
        boolean cancelled = false;
        try {
            int done = this.progressCount;
            int total = this.progressOutOf;
            props.put("progress_count", done);
            props.put("progress_out_of", total);
            if (total > 0 && done < total) {
                cancelled = true;
            }
        } catch (Throwable ignored) {
            // accès au progrès best-effort
        }

        if (cancelled) {
            Telemetry.capture("fbt_export_cancelled", props);
        } else {
            props.put("success", true);
            Telemetry.capture("fbt_export_finished", props);
        }
        ctx.end();
    }

    /**
     * Enveloppe l'appel à {@code doExport} pour intercepter toute exception non gérée et émettre
     * {@code fbt_export_failed} avec le vrai throwable avant de le relancer intact.
     *
     * <p>{@code require = 0} : si Flashback renomme {@code doExport} dans une future version,
     * l'injection est silencieusement ignorée plutôt que de crasher au chargement du mod.
     *
     * <p>En cas de succès, ce hook n'émet rien : l'event {@code fbt_export_finished} /
     * {@code fbt_export_cancelled} est produit par le RETURN inject de {@code run()}.
     *
     * <p>{@link Telemetry#captureExportFailure} appelle déjà {@link ExportContext#end()},
     * ce qui empêche le filet incomplete-detection (setup HEAD) de double-firer.
     */
    @WrapOperation(
        method = "run",
        at = @At(
            value = "INVOKE",
            target = "Lcom/moulberry/flashback/exporting/ExportJob;doExport(Lcom/moulberry/flashback/exporting/VideoWriter;Lcom/moulberry/flashback/exporting/SaveableFramebufferQueue;)V"
        ),
        require = 0
    )
    private void flashbackturbo$captureExportFailure(
            ExportJob self,
            VideoWriter videoWriter,
            SaveableFramebufferQueue framebufferQueue,
            Operation<Void> original) {
        try {
            original.call(self, videoWriter, framebufferQueue);
        } catch (Throwable t) {
            try {
                Telemetry.captureExportFailure("export", t);
                // captureExportFailure appelle déjà EXPORT.end() — pas de double-appel nécessaire.
            } catch (Throwable ignored) {
                // Filet de sécurité : la télémétrie ne doit jamais altérer le comportement de l'export.
            }
            throw t; // Relance le throwable original inchangé pour que Flashback gère l'erreur normalement.
        }
    }

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

        boolean timeoutHit = mc.world == null;
        long waitMs = (System.nanoTime() - (deadlineNs - 60_000_000_000L)) / 1_000_000L;
        if (timeoutHit) {
            FlashbackTurboClient.LOGGER.warn(
                "[H10] mc.level toujours null après {} runClientTick — race ExportJob.setup non contournée", ticks);
            ExportContext failCtx = Telemetry.export();
            failCtx.put("reason", "level_load_timeout");
            failCtx.put("ticks_pumped", ticks);
            failCtx.put("wait_ms", waitMs);
            Telemetry.captureExportFailure("setup", null);
        } else if (ticks > 0) {
            FlashbackTurboClient.LOGGER.info(
                "[H10] monde du replay chargé après {} runClientTick supplémentaires — race ExportJob.setup contournée", ticks);
        }
        if (ticks > 0) {
            Map<String, Object> props = new HashMap<>();
            props.put("ticks_pumped", ticks);
            props.put("wait_ms", waitMs);
            props.put("timeout_hit", timeoutHit);
            Telemetry.capture("fbt_setup_race_recovered", props);
        }
    }
}
