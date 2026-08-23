package fr.zeffut.flashbackturbo.encoder;

import fr.zeffut.flashbackturbo.telemetry.ExportContext;
import fr.zeffut.flashbackturbo.telemetry.Telemetry;

/** Petit pont fail-safe pour annoter le contexte d'export actif avec la promotion d'encodeur. */
public final class ExportContextHolder {
    private ExportContextHolder() {}

    /** Pose (ou efface si null) les propriétés de promotion sur le contexte d'export courant. */
    public static void recordPromotion(String from, String to) {
        ExportContext ctx = Telemetry.export();
        if (ctx == null) return;
        ctx.put("encoder_promoted_from", from);
        ctx.put("encoder_promoted_to", to);
    }
}
