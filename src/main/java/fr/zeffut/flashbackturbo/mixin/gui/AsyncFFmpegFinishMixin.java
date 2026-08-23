package fr.zeffut.flashbackturbo.mixin.gui;

/**
 * Disabled for the 26.2 port.
 *
 * <p>Flashback 0.41+ added its own progress messages during final export steps,
 * and Minecraft 26.2 moved GUI rendering toward render-state extraction with
 * private DeltaTracker plumbing. The old H8 manual render/swap hook is therefore
 * intentionally not listed in flashbackturbo.mixins.json for 26.2.
 */
public final class AsyncFFmpegFinishMixin {
    private AsyncFFmpegFinishMixin() {}
}
