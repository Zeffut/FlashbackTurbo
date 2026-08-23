package fr.zeffut.flashbackturbo;

import fr.zeffut.flashbackturbo.config.TurboConfig;
import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FlashbackTurboClient implements ClientModInitializer {
    public static final String MOD_ID = "flashbackturbo";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitializeClient() {
        TurboConfig.load();
        var cfg = TurboConfig.current();
        LOGGER.info("[FlashbackTurbo] init — liftResolutionCap={} tuneFFmpegThreading={} parallelPngWriter={} (zlib L{}) showExportProgressOverlay={} useFragmentedMp4OnHwEncoders={} fixExportSetupRace={} enableTelemetry={}",
            cfg.liftResolutionCap, cfg.tuneFFmpegThreading, cfg.parallelPngWriter, cfg.pngCompressionLevel, cfg.showExportProgressOverlay, cfg.useFragmentedMp4OnHwEncoders, cfg.fixExportSetupRace, cfg.enableTelemetry);
        fr.zeffut.flashbackturbo.telemetry.Telemetry.init();
        // AutoUpdate : mise à jour silencieuse des mods Zeffut via Modrinth (thread daemon, lock global).
        fr.zeffut.flashbackturbo.update.UpdateService.start();
        // H11 : capturer le vendor/renderer GL une seule fois, sur le thread client (contexte GL courant).
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(client ->
            fr.zeffut.flashbackturbo.telemetry.GpuInfo.captureFromGl()); // no-op après la 1re capture
    }
}
