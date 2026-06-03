package fr.zeffut.flashbackturbo.telemetry;

import com.posthog.java.PostHog;
import fr.zeffut.flashbackturbo.FlashbackTurboClient;
import fr.zeffut.flashbackturbo.config.TurboConfig;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Façade statique de télémétrie PostHog. Anonyme, toujours active (sauf kill-switch config),
 * et STRICTEMENT fail-safe : aucune méthode ne propage jamais d'exception — un échec de
 * télémétrie ne doit jamais casser un export. Tout l'I/O réseau est asynchrone (géré par le SDK).
 */
public final class Telemetry {

    private static final String API_KEY = "phc_zdMj4p5wo8EvfVApjb2EbfUHJ76zgYGM5wAGz5YJC359";
    private static final String HOST = "https://eu.i.posthog.com";
    private static final String ID_FILENAME = "flashbackturbo_telemetry.json";

    private static volatile PostHog client; // null = désactivé ou init échoué → capture() no-op
    private static volatile String distinctId;
    private static volatile Map<String, Object> superProps = Map.of();
    private static final ExportContext EXPORT = new ExportContext();

    private Telemetry() {}

    /** Accès au contexte d'export partagé (jamais null). */
    public static ExportContext export() {
        return EXPORT;
    }

    /** Initialise le client. No-op total si la télémétrie est désactivée. Ne lève jamais. */
    public static void init() {
        try {
            if (!TurboConfig.current().enableTelemetry) {
                FlashbackTurboClient.LOGGER.info("[telemetry] désactivée (enableTelemetry=false)");
                return;
            }
            Path idFile = FabricLoader.getInstance().getConfigDir().resolve(ID_FILENAME);
            distinctId = AnonymousId.loadOrCreate(idFile);
            superProps = DeviceProfile.collect();
            client = new PostHog.Builder(API_KEY).host(HOST).build();
            Runtime.getRuntime().addShutdownHook(new Thread(Telemetry::shutdown, "fbt-telemetry-shutdown"));
            capture("fbt_mod_loaded", null);
            FlashbackTurboClient.LOGGER.info("[telemetry] initialisée (anonyme, host={})", HOST);
        } catch (Throwable t) {
            client = null;
            FlashbackTurboClient.LOGGER.debug("[telemetry] init échouée — télémétrie désactivée", t);
        }
    }

    /** Envoie un event. No-op si non initialisée. Ne lève jamais. */
    public static void capture(String event, Map<String, Object> props) {
        try {
            PostHog c = client;
            if (c == null || distinctId == null) return;
            Map<String, Object> merged = new HashMap<>(superProps);
            if (props != null) merged.putAll(props);
            c.capture(distinctId, event, merged);
        } catch (Throwable t) {
            FlashbackTurboClient.LOGGER.debug("[telemetry] capture('{}') échouée", event, t);
        }
    }

    /** Flush + ferme le client. Idempotent. Ne lève jamais. */
    public static void shutdown() {
        try {
            PostHog c = client;
            if (c != null) c.shutdown();
        } catch (Throwable t) {
            FlashbackTurboClient.LOGGER.debug("[telemetry] shutdown échouée", t);
        }
    }
}
