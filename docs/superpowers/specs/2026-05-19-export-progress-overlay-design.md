# H8 — Export Progress Overlay (design)

Date: 2026-05-19
Status: design approved, pending implementation

## Problem

Flashback's `ExportJob.run()` is invoked synchronously on the render thread from `MixinMinecraft.runTick_runAllTasks`. The export loop (`ExportJob.run()`) ticks the game and captures frames but never calls `gameRenderer.render()` or `glfwSwapBuffers()` on the visible window. The user's window stays frozen on the last frame drawn before export started, for the entire duration of the export (potentially minutes).

There is no visual feedback during export. Users assume the game has crashed.

## Goal

Display a full-screen overlay during export with a progress bar, frame counter (X / Y), and ETA in seconds — refreshed at ~10 fps without significantly slowing the export.

## Non-goals

- Cancel button or any user interaction during export (deferred; user said no).
- Speedup-vs-vanilla comparison, fps counter, output path display, or any "rich" UI info (deferred; user picked minimal).
- Persisting the progress UI across crashes (best-effort cleanup only).

## Approach

Inject a custom `Screen` into Minecraft and force a render+swap on the visible framebuffer between export ticks. ExportJob's loop drives the redraw cadence; we throttle to ~10 fps to avoid measurable overhead on the export itself.

### Components

#### 1. `fr.zeffut.flashbackturbo.gui.ExportProgressScreen` (new class)

Extends `net.minecraft.client.gui.screens.Screen`.

- Constructor stores `System.nanoTime()` as `startNanos`.
- Overrides `render(DrawContext, mouseX, mouseY, delta)`:
  - Fill the entire window with opaque black (`fill(0, 0, width, height, 0xFF000000)`).
  - Read `Flashback.EXPORT_JOB` (may be null near startup/shutdown — guard).
  - If non-null, read `progressCount` and `progressOutOf`.
  - Draw title "Exporting Replay" centered, ~scale 1.5.
  - Draw "Frame ${count} / ${total}" centered.
  - Draw progress bar: rectangle width 50 % of window, height 10 px, filled proportionally. Empty part dark grey, filled part green-to-blue gradient (or solid green; final choice during impl).
  - Draw "ETA: ${secs}s" centered.
  - ETA formula:
    - If `progressCount <= 0`: display "ETA: —"
    - Else: `elapsedMs = (now - startNanos) / 1e6`; `eta = elapsedMs / progressCount * (progressOutOf - progressCount)`.
- Overrides `shouldCloseOnEsc()` → returns `false` (no cancel).
- Overrides `isPauseScreen()` → returns `false`.

#### 2. `fr.zeffut.flashbackturbo.mixin.gui.ExportJobProgressMixin` (new Mixin)

Target: `com.moulberry.flashback.exporting.ExportJob`.

- `@Inject(method = "run", at = @At("HEAD"))`:
  - Guard: `if (!TurboConfig.current().showExportProgressOverlay) return;`
  - `Minecraft.getInstance().setScreen(new ExportProgressScreen());`
- `@Inject(method = "run", at = @At("RETURN"))` and `@Inject(method = "run", at = @At("THROW"))`:
  - `Minecraft.getInstance().setScreen(null);`
- `@Inject(method = "runClientTick", at = @At("RETURN"))`:
  - Throttle field `flashbackturbo$lastRenderNanos`.
  - If `(now - last) >= 100_000_000L` (100 ms = ~10 fps):
    - `Minecraft mc = Minecraft.getInstance();`
    - Re-assert our screen if `mc.screen` is not an `ExportProgressScreen` (ExportJob may have called `setScreen(null)` internally — see lines 385, 588 of vanilla `ExportJob.java`).
    - `mc.gameRenderer.render(mc.getTimer(), true);`
    - `GLFW.glfwSwapBuffers(mc.getWindow().getWindow());`
    - Update `lastRenderNanos = now`.

#### 3. `TurboConfig` extension

Add field:

```java
public boolean showExportProgressOverlay = true;
```

Update the init log line in `FlashbackTurboClient.onInitializeClient()` to include it.

#### 4. `flashbackturbo.mixins.json`

Add `"gui.ExportJobProgressMixin"` to the `client` array.

### Data flow

```
ExportJob.run() entry
  → Mixin HEAD inject
    → setScreen(new ExportProgressScreen)

ExportJob loop (repeated):
  → setServerTickAndWait(...)
  → runClientTick(...)
    → Mixin RETURN inject (throttle 10 fps)
      → re-assert ExportProgressScreen if needed
      → gameRenderer.render(timer, true)
      → glfwSwapBuffers(window)
  → frame captured for export
  → progressCount++ inside ExportJob (line 800 vanilla)

ExportJob.run() return / throw
  → Mixin RETURN/THROW inject
    → setScreen(null)
```

### Error handling

- Mixin `@Inject` on both `RETURN` and `THROW` ensures cleanup of the screen even if export crashes.
- The `render()` method guards against `EXPORT_JOB == null` (race during start/finish).
- If `progressOutOf` is 0 (uninitialized), guard the division: display "ETA: —".
- If `progressCount > progressOutOf` (theoretical race): clamp to 100 %.

### Testing

- Manual only. Run `./gradlew runClient` with Flashback installed, start an export, observe overlay.
- Unit test would require a full MC render context; skip.
- Build validation via `MixinTargetValidationTest` is gone (removed during cleanup), so the only check that selectors resolve is the actual runtime test.

### Overhead estimate

- A `gameRenderer.render(...)` call at 1080p costs ~5-30 ms depending on scene complexity (we're rendering whatever ExportJob's last camera state showed).
- At 10 fps throttle on a 60s export, that's 600 redraws × 15 ms ≈ 9 s of overhead.
- For shorter exports (10 s), it's 100 × 15 ms = 1.5 s.
- **Acceptable**: a few percent overhead is fine for the UX improvement of "I can see it's working".
- Mitigation: throttle is configurable via a constant (private static final in the Mixin), can be bumped to 5 fps if needed.

### Versioning

This is feature work, not a metadata fix. Bump to **0.3.0** when shipped.

- New version `0.3.0` for 1.21.9–1.21.11.
- New version `0.3.0+26.1` for 26.1.x (Mojang mappings, separate build as before).
- Both ship as additional Modrinth versions alongside 0.2.x (no deletions).
- GitHub releases v0.3.0 and v0.3.0+26.1 alongside, per the established mirror policy.

## Files touched

| File | Action |
|------|--------|
| `src/main/java/fr/zeffut/flashbackturbo/gui/ExportProgressScreen.java` | new |
| `src/main/java/fr/zeffut/flashbackturbo/mixin/gui/ExportJobProgressMixin.java` | new |
| `src/main/java/fr/zeffut/flashbackturbo/mixin/gui/package-info.java` | new |
| `src/main/resources/flashbackturbo.mixins.json` | add mixin to `client` array |
| `src/main/java/fr/zeffut/flashbackturbo/config/TurboConfig.java` | add `showExportProgressOverlay` field |
| `src/main/java/fr/zeffut/flashbackturbo/FlashbackTurboClient.java` | log new toggle |
| `gradle.properties` | bump `mod_version` to `0.3.0` |
| `docs/TODO.md` | mark H8 done, update next steps |
| `README.md` | mention H8 in hook table |

Total estimated diff: ~180 lines added, ~5 lines modified.

## 26.1.x port differences

The 26.1.x branch (Mojang mappings, Loom 1.15-SNAPSHOT, JDK 25) requires the same logic with these substitutions:

| 1.21.x (yarn) | 26.1.x (Mojang) |
|---------------|-----------------|
| `net.minecraft.client.gui.screen.Screen` | `net.minecraft.client.gui.screens.Screen` |
| `MinecraftClient` | `Minecraft` |
| `DrawContext` | `GuiGraphics` |
| `mc.getTimer()` | needs verification — may be `mc.getDeltaTracker()` in 26.1 |

To verify during impl: run `javap` on the 26.1 `Minecraft.class` and `Screen.class` for exact signatures.
