package fr.zeffut.flashbackturbo.telemetry;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Accumule les propriétés d'un export en cours, renseignées au fil des points d'injection
 * (résolution, encoder, format, nb frames…) + le timestamp de début, et produit la map de
 * propriétés pour les events de fin. Un seul export à la fois côté client Flashback, donc une
 * instance statique partagée suffit (voir {@link Telemetry}). Thread-safety : toutes les
 * méthodes sont synchronized car start/encode/finish peuvent toucher des threads différents.
 */
public final class ExportContext {

    private final Map<String, Object> props = new LinkedHashMap<>();
    private long startNanos = 0L;
    private boolean active = false;

    public synchronized void begin(long nowNanos) {
        props.clear();
        startNanos = nowNanos;
        active = true;
    }

    public synchronized void put(String key, Object value) {
        props.put(key, value);
    }

    public synchronized boolean isActive() {
        return active;
    }

    public synchronized void end() {
        active = false;
    }

    /** Copie des propriétés + duration_ms si un export est actif. */
    public synchronized Map<String, Object> snapshot(long nowNanos) {
        Map<String, Object> out = new LinkedHashMap<>(props);
        if (active) {
            out.put("duration_ms", (nowNanos - startNanos) / 1_000_000L);
        }
        return out;
    }
}
