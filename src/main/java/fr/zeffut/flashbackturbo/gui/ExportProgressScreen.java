package fr.zeffut.flashbackturbo.gui;

import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.exporting.ExportJob;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

/**
 * Plein écran affiché pendant un export Flashback. Sans cette UI, l'utilisateur
 * voit l'écran figé sur la dernière frame pré-export pendant toute la durée de
 * l'export (ExportJob.run() bloque le render thread).
 *
 * <p>Lit la progression via {@code Flashback.EXPORT_JOB.progressCount / progressOutOf}.
 * Le rendu est forcé par notre Mixin sur {@code ExportJob.runClientTick}, throttle
 * à ~10 fps pour ne pas pénaliser l'export.
 */
public class ExportProgressScreen extends Screen {

    private static final int BG_COLOR = 0xFF101015;
    private static final int BAR_BG_COLOR = 0xFF2A2A35;
    private static final int BAR_FILL_COLOR = 0xFF5BA84F;   // vert MC-like
    private static final int TITLE_COLOR = 0xFFFFFFFF;
    private static final int TEXT_COLOR = 0xFFCCCCCC;
    private static final int ETA_COLOR = 0xFFAAAAAA;

    private final long startNanos;

    public ExportProgressScreen() {
        super(Text.literal("FlashbackTurbo · Export"));
        this.startNanos = System.nanoTime();
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        // Fond opaque plein écran — masque la frame d'export en cours pour ne pas
        // distraire l'utilisateur avec des artefacts intermédiaires.
        ctx.fill(0, 0, this.width, this.height, BG_COLOR);

        ExportJob job = Flashback.EXPORT_JOB;
        int progress = 0;
        int total = 0;
        if (job != null) {
            progress = Math.max(0, job.progressCount);
            total = Math.max(progress, job.progressOutOf);
        }
        double pct = total > 0 ? (double) progress / total : 0.0;

        // Layout : centré verticalement
        int cx = this.width / 2;
        int titleY = this.height / 2 - 40;
        int frameY = this.height / 2 - 10;
        int barY   = this.height / 2 + 6;
        int etaY   = this.height / 2 + 28;

        // Titre
        ctx.drawCenteredTextWithShadow(this.textRenderer, "Exporting Replay", cx, titleY, TITLE_COLOR);

        // Frame X / Y
        String frameText = total > 0
            ? String.format("Frame %d / %d", progress, total)
            : "Preparing…";
        ctx.drawCenteredTextWithShadow(this.textRenderer, frameText, cx, frameY, TEXT_COLOR);

        // Progress bar : 50% de la largeur, hauteur 8px
        int barW = Math.max(200, this.width / 2);
        int barH = 8;
        int barX = cx - barW / 2;
        ctx.fill(barX, barY, barX + barW, barY + barH, BAR_BG_COLOR);
        int fillW = (int) (barW * pct);
        if (fillW > 0) {
            ctx.fill(barX, barY, barX + fillW, barY + barH, BAR_FILL_COLOR);
        }

        // Pourcentage à droite de la barre
        String pctText = String.format("%d%%", (int) Math.round(pct * 100));
        ctx.drawTextWithShadow(this.textRenderer, pctText, barX + barW + 8, barY, TEXT_COLOR);

        // ETA
        String etaText = computeEtaText(progress, total);
        ctx.drawCenteredTextWithShadow(this.textRenderer, etaText, cx, etaY, ETA_COLOR);
    }

    private String computeEtaText(int progress, int total) {
        if (progress <= 0 || total <= 0) {
            return "ETA: —";
        }
        long elapsedMs = (System.nanoTime() - this.startNanos) / 1_000_000L;
        long etaMs = elapsedMs * (total - progress) / Math.max(1, progress);
        return "ETA: " + formatDuration(etaMs);
    }

    private static String formatDuration(long ms) {
        long s = ms / 1000;
        if (s < 60) return s + "s";
        long m = s / 60;
        long rem = s % 60;
        if (m < 60) return m + "m " + rem + "s";
        long h = m / 60;
        return h + "h " + (m % 60) + "m";
    }

    /** Utilitaire pour MC.execute(...) qui prend un Supplier<Screen>. */
    public static ExportProgressScreen create() {
        return new ExportProgressScreen();
    }
}
