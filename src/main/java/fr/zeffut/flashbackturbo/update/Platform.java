package fr.zeffut.flashbackturbo.update;

import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Abstraction loader minimale pour le module AutoUpdate, version Fabric-only de FlashbackTurbo
 * (remplace le Platform multi-loader Stonecutter du template). Référence UNIQUEMENT l'API
 * FabricLoader — aucune classe {@code net.minecraft} → le module reste MC-agnostique.
 */
public final class Platform {

    private Platform() {}

    /** Toujours "fabric" pour FlashbackTurbo. */
    public static String loader() {
        return "fabric";
    }

    /** Version conviviale de ce mod, ou "unknown". */
    public static String modVersion() {
        return FabricLoader.getInstance().getModContainer("flashbackturbo")
                .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("unknown");
    }

    /** Version conviviale de Minecraft, ou "unknown". */
    public static String mcVersion() {
        return FabricLoader.getInstance().getModContainer("minecraft")
                .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("unknown");
    }

    /** True si on tourne en environnement de développement. */
    public static boolean isDevelopment() {
        return FabricLoader.getInstance().isDevelopmentEnvironment();
    }

    /** Chemin absolu du répertoire d'instance/jeu. */
    public static Path gameDir() {
        return FabricLoader.getInstance().getGameDir();
    }
}
