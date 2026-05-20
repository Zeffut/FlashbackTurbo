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
        LOGGER.info("[FlashbackTurbo] init — liftResolutionCap={} tuneFFmpegThreading={} parallelPngWriter={} (zlib L{}) showExportProgressOverlay={} useFragmentedMp4OnHwEncoders={} fixExportSetupRace={}",
            cfg.liftResolutionCap, cfg.tuneFFmpegThreading, cfg.parallelPngWriter, cfg.pngCompressionLevel, cfg.showExportProgressOverlay, cfg.useFragmentedMp4OnHwEncoders, cfg.fixExportSetupRace);
    }
}
