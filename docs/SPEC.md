# FlashbackTurbo — Specification

## 1. Problem statement

Flashback exports Minecraft replays to video by re-rendering the timeline in-engine, capturing the framebuffer per tick, and piping frames to FFmpeg (mp4) or writing them as a PNG sequence. On a typical 10-minute replay at 60 fps (36 000 frames), exports commonly take **30–90 minutes** depending on resolution, shaders, and CPU/GPU.

Profiling the existing pipeline (see [Flashback `exporting/` package](https://github.com/Moulberry/Flashback/tree/master/src/main/java/com/moulberry/flashback/exporting)) suggests three improvable areas:

| Stage                          | Approx share | Improvable? |
|--------------------------------|--------------|-------------|
| Tick + render + PerfectFrames  | ~40 %        | No          |
| Framebuffer readback (glReadPixels) | ~30 %   | Yes — PBO double-buffer |
| FFmpeg encode (CPU x264)       | ~20 %        | Yes — hardware encoder  |
| PNG sequence write (zlib L6)   | ~10 %        | Yes — parallel + lower level |

Realistic target: **30–50 % faster export** without quality loss for the user-facing visual output.

## 2. Non-goals

- We do not modify Flashback's render or PerfectFrames code.
- We do not redistribute Flashback code.
- We do not silently change output quality. Every speed-vs-quality knob is **opt-in** via UI.

## 3. Architecture

Three independent hooks, each shippable separately:

### 3.1. Hardware encoder injector

Mixin into `AsyncFFmpegVideoWriter.start()` (or its equivalent entry point — to be confirmed by inspection). Detect platform GPU, inject the corresponding FFmpeg flags before the existing CPU-encode arguments. Fall back silently to CPU x264 if the hardware encoder probe fails.

Detection order (per OS):
- macOS: `videotoolbox`
- Linux + NVIDIA: `nvenc`
- Linux + Intel: `qsv` / `vaapi`
- Linux + AMD: `amf` / `vaapi`
- Windows: same, plus `d3d11va`

### 3.2. Parallel PNG writer

Mixin into `PNGSequenceVideoWriter.write(...)`. Replace the single-threaded zlib compress + file write with a bounded `ExecutorService` (default = number of CPU cores − 1) and configurable compression level (default 6, user-settable 1–9).

### 3.3. PBO framebuffer readback

Mixin into `SaveableFramebufferQueue` (or the framebuffer capture entry point — to be confirmed). Replace the blocking `glReadPixels` with a two-PBO ring: frame N's pixels are read while frame N+1 is rendering.

## 4. UI integration

A single new section in Flashback's export settings dialog:

```
Performance
  [x] Use hardware encoder (when available)        [info: ?]
  [ ] Fast PNG compression (smaller wait, larger files)
  [x] Asynchronous framebuffer readback
```

All three default to safe values (hardware on, PNG fast off, async readback on). The user can disable any of them to fall back to vanilla Flashback behaviour.

## 5. Compatibility

- MC 1.21.9 / 1.21.10 / 1.21.11 / 26.1.x — same compatibility matrix as Flashback and MultiView.
- Requires Flashback ≥ 0.39.0.
- Fabric only.

## 6. Open questions (to resolve before milestone 0.1)

1. Are the Flashback classes we plan to Mixin marked `final` or otherwise un-Mixin-friendly?
2. Does Flashback's license permit Mixin-based behaviour modification by an external addon?
   - The license forbids reuploading and redistributing Flashback, but addons that depend on it via runtime hooks are an established pattern (see MultiView). To confirm explicitly.
3. Which FFmpeg binary does Flashback ship / use? (bundled vs system PATH — affects which encoders are reachable)
4. Is `glReadPixels` actually called once per frame, or already batched? PBO work only pays off if it is currently blocking.

## 7. Validation plan

- Benchmark vanilla Flashback export on a fixed 5-minute replay at 1080p60.
- Apply each hook independently and re-benchmark.
- Compare output frames pixel-by-pixel against vanilla output (hardware encoder will differ — measure SSIM, target ≥ 0.98).
- Test on macOS (Apple Silicon), Linux + NVIDIA, Windows + Intel iGPU as the three priority platforms.

## 8. Roadmap

- **0.1.0** — Hardware encoder injector only, behind opt-in flag.
- **0.2.0** — Parallel PNG writer.
- **0.3.0** — PBO readback.
- **0.4.0** — Combined defaults, polished UI, telemetry-free benchmark command.
