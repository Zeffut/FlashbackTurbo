package fr.zeffut.flashbackturbo.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import fr.zeffut.flashbackturbo.FlashbackTurboClient;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Configuration runtime de FlashbackTurbo, chargée depuis {@code <game>/config/flashbackturbo.json}.
 *
 * <p>Chaque flag contrôle un hook Mixin opt-in. Les Mixins lisent ces champs en temps réel
 * via {@link #current()}.
 *
 * <p>Le fichier est créé avec les défauts si absent. En cas de JSON corrompu, on log un warning
 * et on retombe sur les défauts en mémoire (sans toucher au fichier sur disque, pour ne pas
 * écraser une config user qu'il pourrait vouloir corriger à la main).
 */
public final class TurboConfig {

    private static final String FILENAME = "flashbackturbo.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static volatile TurboConfig INSTANCE = new TurboConfig();

    /** Lève le cap de résolution 4K imposé par AsyncFFmpegVideoWriter. */
    public boolean liftResolutionCap = true;

    /** Force setVideoOption("threads","auto") + tunes par encoder. */
    public boolean tuneFFmpegThreading = true;

    /** Parallélise l'écriture PNG sequence sur N-1 threads. */
    public boolean parallelPngWriter = true;

    /** Niveau de compression zlib pour PNG (1=rapide, 9=compact). Lossless dans tous les cas. */
    public int pngCompressionLevel = 1;

    /** Réservé pour une optimisation future (memset natif alpha). Pas utilisé en 0.2.x — l'alpha cleanup
     * est éliminée par H7 qui passe en PNG color type 2. */
    public boolean nativeAlphaCleanup = true;

    /** Conversion RGB→YUV GPU. Désactivé tant que H5 n'est pas implémenté (cf docs/HOOKS.md). */
    public boolean gpuColorspaceConversion = false;

    private TurboConfig() {}

    public static TurboConfig current() {
        return INSTANCE;
    }

    /** Charge la config depuis le disque ; crée le fichier avec les défauts si absent. */
    public static synchronized void load() {
        Path configPath = FabricLoader.getInstance().getConfigDir().resolve(FILENAME);
        if (!Files.exists(configPath)) {
            INSTANCE = new TurboConfig();
            save();
            FlashbackTurboClient.LOGGER.info("[config] créé {} avec les défauts", configPath);
            return;
        }

        try {
            String json = Files.readString(configPath);
            TurboConfig loaded = GSON.fromJson(json, TurboConfig.class);
            if (loaded == null) {
                FlashbackTurboClient.LOGGER.warn("[config] {} vide, utilisation des défauts en mémoire", configPath);
                return;
            }
            // Clamp pngCompressionLevel dans [1,9]
            loaded.pngCompressionLevel = Math.max(1, Math.min(9, loaded.pngCompressionLevel));
            INSTANCE = loaded;
            FlashbackTurboClient.LOGGER.info("[config] chargé depuis {}", configPath);
        } catch (IOException | JsonSyntaxException e) {
            FlashbackTurboClient.LOGGER.warn("[config] erreur lecture {}, utilisation des défauts en mémoire (fichier non modifié)", configPath, e);
        }
    }

    /** Sauvegarde la config courante sur disque. */
    public static synchronized void save() {
        Path configPath = FabricLoader.getInstance().getConfigDir().resolve(FILENAME);
        try {
            Files.createDirectories(configPath.getParent());
            Files.writeString(configPath, GSON.toJson(INSTANCE));
        } catch (IOException e) {
            FlashbackTurboClient.LOGGER.error("[config] échec écriture {}", configPath, e);
        }
    }
}
