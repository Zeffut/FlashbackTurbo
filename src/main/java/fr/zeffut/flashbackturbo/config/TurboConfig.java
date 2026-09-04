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

    /**
     * H8 (0.3.5+) : affiche un écran "Saving…" animé pendant le post-export Flashback
     * (drain des queues encode + attente threads dans {@code AsyncFFmpegVideoWriter.finish()}).
     * Sans cet écran, le main thread bloqué donne l'impression que le jeu a planté.
     * <p>Ne s'applique qu'au mode MP4/MKV/etc. (FFmpeg writer). Le PNG sequence n'a pas
     * besoin (la finish PNG est rapide vu qu'on parallélise les writes).
     */
    public boolean showExportProgressOverlay = true;

    /**
     * H9 (0.3.5+) : utilise fragmented MP4 (movflags=+frag_keyframe+empty_moov) avec les
     * hardware encoders (videotoolbox, nvenc, qsv, amf). Le finalize FFmpeg passe de
     * ~10-15s à ~1-2s en éliminant l'écriture du moov atom géant à la fin du fichier.
     * <p>Tradeoff : fichier ~1-3% plus gros. Compatible VLC/IINA/Premiere/DaVinci/Discord/
     * YouTube et tout player MP4 récent. Désactiver si tu produces pour des pipelines pros
     * stricts (broadcast, archivage long-terme).
     */
    public boolean useFragmentedMp4OnHwEncoders = true;

    /**
     * H10 (0.3.8+) : contourne une race de Flashback dans {@code ExportJob.setup()}. Flashback
     * fait un seul {@code runClientTick()} puis lit {@code mc.level} ; si le monde du replay
     * n'a pas fini de (re)charger — fréquent sur les replays serveur lourds — {@code mc.level}
     * est null → {@code NullPointerException}, l'export plante avant la première frame.
     * <p>Ce hook pompe {@code runClientTick()} après le premier tick jusqu'à ce que le niveau
     * soit chargé (timeout 60 s). No-op si le niveau est déjà prêt (cas normal). Bug Flashback
     * pur — FlashbackTurbo ne fait que le rendre robuste.
     */
    public boolean fixExportSetupRace = true;

    /**
     * H11 : si Flashback encode en {@code libopenh264} (software, mono-thread, lent) mais qu'un
     * encodeur matériel ({@code h264_nvenc} puis {@code h264_qsv}) est réellement utilisable,
     * redirige l'export vers lui. ~4-6× plus rapide, qualité égale ou supérieure à débit égal.
     * Fail-safe : si la promotion échoue au démarrage, on retombe sur {@code libopenh264}.
     */
    public boolean promoteSoftwareToHardwareEncode = true;

    /**
     * Télémétrie PostHog anonyme (toujours active par défaut). Aucune donnée identifiante :
     * distinct_id = UUID aléatoire local, messages d'exception sanitisés. Mettre à {@code false}
     * désactive totalement l'envoi (aucun appel réseau, aucun fichier d'id créé).
     */
    public boolean enableTelemetry = true;

    /** AutoUpdate : mise à jour silencieuse des mods Zeffut via Modrinth (défaut activé). */
    public boolean autoUpdate = true;
    /** AutoUpdate : si true, met à jour TOUS les mods Modrinth (pas seulement ceux de update_owner). */
    public boolean updateAll = false;
    /** AutoUpdate : compte Modrinth dont les mods sont éligibles à la mise à jour. */
    public String updateOwner = "Zeffut";
    /** AutoUpdate : slugs/ids de projets à exclure de la mise à jour, séparés par des virgules. */
    public String updateExclude = "";

    /**
     * Accès « clé→valeur string » utilisé par le module AutoUpdate (mappe les 4 options ci-dessus).
     * Renvoie {@code fallback} pour toute clé inconnue.
     */
    public String setting(String key, String fallback) {
        return switch (key) {
            case "auto_update" -> String.valueOf(autoUpdate);
            case "update_all" -> String.valueOf(updateAll);
            case "update_owner" -> updateOwner != null ? updateOwner : fallback;
            case "update_exclude" -> updateExclude != null ? updateExclude : fallback;
            default -> fallback;
        };
    }

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
