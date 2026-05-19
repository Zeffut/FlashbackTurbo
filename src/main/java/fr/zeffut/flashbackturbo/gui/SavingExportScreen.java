package fr.zeffut.flashbackturbo.gui;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

/**
 * Affichée pendant le post-export Flashback (après la barre 100%) — quand le main thread
 * est occupé par {@code AsyncFFmpegVideoWriter.finish()} / {@code Files.move()} et que
 * Flashback's own progress overlay ne refresh plus.
 *
 * <p>Animation de points "Saving export..." + elapsed time pour prouver visuellement que
 * le mod n'a pas planté.
 */
public class SavingExportScreen extends Screen {

    private static final int BG_COLOR = 0xFF101015;
    private static final int TITLE_COLOR = 0xFFFFFFFF;
    private static final int TEXT_COLOR = 0xFFCCCCCC;
    private static final int DIM_COLOR = 0xFFAAAAAA;

    private final long startNanos;

    public SavingExportScreen() {
        super(Text.literal("Saving export…"));
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
        ctx.fill(0, 0, this.width, this.height, BG_COLOR);

        int cx = this.width / 2;
        int titleY = this.height / 2 - 24;
        int textY = this.height / 2;
        int elapsedY = this.height / 2 + 20;

        // Animation des points
        long elapsedMs = (System.nanoTime() - this.startNanos) / 1_000_000L;
        int dotCount = (int) ((elapsedMs / 400) % 4); // 0, 1, 2, 3 dots, change every 400ms
        StringBuilder dots = new StringBuilder();
        for (int i = 0; i < dotCount; i++) dots.append('.');
        for (int i = dotCount; i < 3; i++) dots.append(' ');

        ctx.drawCenteredTextWithShadow(this.textRenderer, "Finalizing export", cx, titleY, TITLE_COLOR);
        ctx.drawCenteredTextWithShadow(this.textRenderer, "Saving" + dots, cx, textY, TEXT_COLOR);
        ctx.drawCenteredTextWithShadow(this.textRenderer, formatElapsed(elapsedMs), cx, elapsedY, DIM_COLOR);
    }

    private static String formatElapsed(long ms) {
        long s = ms / 1000;
        if (s < 60) return s + "s";
        long m = s / 60;
        return m + "m " + (s % 60) + "s";
    }
}
