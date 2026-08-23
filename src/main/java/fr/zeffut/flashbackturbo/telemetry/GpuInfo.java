package fr.zeffut.flashbackturbo.telemetry;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Holder best-effort du vendor/renderer GL de Minecraft. Rempli une seule fois depuis le thread
 * client (END_CLIENT_TICK, où le contexte GL est courant) via {@link #captureFromGl}. Si jamais
 * rempli, {@link #snapshot()} renvoie une map vide → les propriétés sont omises de la télémétrie.
 */
public final class GpuInfo {

    private static volatile String vendor;
    private static volatile String renderer;

    private GpuInfo() {}

    /** À appeler sur le render thread (contexte GL courant). Best-effort, ne lève jamais. */
    public static void captureFromGl() {
        if (vendor != null) return; // déjà capturé
        try {
            String v = org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_VENDOR);
            String r = org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_RENDERER);
            vendor = v == null ? "" : v;
            renderer = r == null ? "" : r;
        } catch (Throwable t) {
            vendor = ""; renderer = "";
        }
    }

    /** Propriétés à fusionner dans les super-properties. Vide si non capturé ou valeurs blanches. */
    public static Map<String, Object> snapshot() {
        Map<String, Object> p = new LinkedHashMap<>();
        String v = vendor, r = renderer;
        if (v != null && !v.isBlank()) p.put("gpu_vendor", v);
        if (r != null && !r.isBlank()) p.put("gpu_renderer", r);
        return p;
    }

    // --- test hooks ---
    static void resetForTest() { vendor = null; renderer = null; }
    static void setForTest(String v, String r) { vendor = v; renderer = r; }
}
