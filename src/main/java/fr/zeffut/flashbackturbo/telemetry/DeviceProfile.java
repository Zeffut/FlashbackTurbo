package fr.zeffut.flashbackturbo.telemetry;

import fr.zeffut.flashbackturbo.config.TurboConfig;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Collecte one-shot des propriétés système et de versions, attachées comme super-properties à
 * chaque event. Aucune donnée identifiante (pas de pseudo, pas de chemin). Fail-safe : toute
 * propriété qu'on ne peut pas lire est simplement omise.
 */
public final class DeviceProfile {

    private DeviceProfile() {}

    public static Map<String, Object> collect() {
        Map<String, Object> p = new LinkedHashMap<>();
        try {
            p.put("os", System.getProperty("os.name", "unknown"));
            p.put("os_version", System.getProperty("os.version", "unknown"));
            p.put("arch", System.getProperty("os.arch", "unknown"));
            p.put("java_version", System.getProperty("java.version", "unknown"));
            p.put("cpu_cores", Runtime.getRuntime().availableProcessors());
            p.put("max_heap_mb", Runtime.getRuntime().maxMemory() / (1024 * 1024));

            FabricLoader loader = FabricLoader.getInstance();
            p.put("mods_count", loader.getAllMods().size());
            modVersion(loader, "minecraft").ifPresent(v -> p.put("mc_version", v));
            modVersion(loader, "fabricloader").ifPresent(v -> p.put("fabric_loader_version", v));
            modVersion(loader, "flashback").ifPresent(v -> p.put("flashback_version", v));
            modVersion(loader, "flashbackturbo").ifPresent(v -> p.put("fbt_version", v));

            TurboConfig cfg = TurboConfig.current();
            p.put("cfg_liftResolutionCap", cfg.liftResolutionCap);
            p.put("cfg_tuneFFmpegThreading", cfg.tuneFFmpegThreading);
            p.put("cfg_parallelPngWriter", cfg.parallelPngWriter);
            p.put("cfg_pngCompressionLevel", cfg.pngCompressionLevel);
            p.put("cfg_showExportProgressOverlay", cfg.showExportProgressOverlay);
            p.put("cfg_useFragmentedMp4OnHwEncoders", cfg.useFragmentedMp4OnHwEncoders);
            p.put("cfg_fixExportSetupRace", cfg.fixExportSetupRace);
            p.put("cfg_promoteSoftwareToHardwareEncode", cfg.promoteSoftwareToHardwareEncode);
        } catch (Throwable t) {
            // best-effort : on renvoie ce qu'on a pu collecter
        }
            p.putAll(GpuInfo.snapshot()); // gpu_vendor/gpu_renderer si déjà capturés (best-effort)
        return p;
    }

    private static java.util.Optional<String> modVersion(FabricLoader loader, String id) {
        return loader.getModContainer(id)
            .map(ModContainer::getMetadata)
            .map(m -> m.getVersion().getFriendlyString());
    }
}
