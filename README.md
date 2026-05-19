# FlashbackTurbo

[![Modrinth](https://img.shields.io/badge/Modrinth-flashbackturbo-00AF5C?logo=modrinth)](https://modrinth.com/mod/flashbackturbo)

A Fabric addon for [Flashback](https://modrinth.com/mod/flashback) that drastically speeds up video export — **without quality loss**.

> Status: **0.2.0 published** ([Modrinth](https://modrinth.com/mod/flashbackturbo)).
> Measured speedup: **10.95× at 1080p** on a real Flashback replay, pixels decoded strictly identical.

## What it does

FlashbackTurbo patches Flashback's export pipeline via Mixin to replace serial, low-tuned operations with parallel ones:

| Hook | Target | Impact |
|------|--------|--------|
| **H2** | `PNGSequenceVideoWriter` | Parallel PNG writer using N-1 threads |
| **H3** | `NativeImage.writeToFile` redirect | Configurable zlib level (default L1 instead of Mojang's hardcoded L6) |
| **H4** | `AsyncFFmpegVideoWriter` ctor | Removed the silent 4K downscale cap |
| **H6** | `AsyncFFmpegVideoWriter.start()` | FFmpeg threading tunes per encoder (nvenc, qsv, amf, x264) |
| **H7** | `PNGSequenceVideoWriter.encode()` | PNG color type 2 (RGB) when transparency is off, eliminates alpha cleanup loop |
| **H8** | `ExportJob.run()` + `runClientTick()` | Full-screen progress overlay during export (frame X/Y, %, ETA) — replaces the frozen window |

All hooks are opt-in via `<game>/config/flashbackturbo.json` and default to safe values. Toggle any of them off to fall back to vanilla Flashback behaviour.

## Performance

Measured on a real 1.21.11 Flashback replay, 10-second slice at 1920×1080 (603 frames):

| Mode    | Total time | Per frame |
|---------|------------|-----------|
| Vanilla | 96 081 ms  | 159 ms   |
| Turbo   |  8 774 ms  |  14 ms   |

**Speedup: 10.95×** end-to-end.

Gains scale with CPU core count and disk speed:
- 16-core + NVMe: ~12-15×
- 4-core + SSD: ~4-6×
- 2-core + HDD: ~2-3× (parallelism limited)

## Quality

**Lossless PNG output.** zlib level only changes file size, decoded pixels are bit-identical. Color type 2 vs 6 (RGB vs RGBA) only differs when transparency is disabled — visually identical.

For the FFmpeg side (H4 + H6), output is byte-identical to vanilla (threading tunes don't affect bitstream).

## Hardware encoders

Flashback already exposes hardware encoders (`h264_nvenc`, `h264_videotoolbox`, `h264_qsv`, `h264_amf`, etc.) via its export UI dropdown — **FlashbackTurbo doesn't replace that selection logic**. Just pick the hardware encoder in Flashback's UI; FlashbackTurbo's H6 will tune its threading on top.

## Compatibility

| MC version | Flashback version | FlashbackTurbo version | Java |
|------------|-------------------|------------------------|------|
| 1.21.9 / 1.21.10 / 1.21.11 | ≥ 0.39.0 | `0.2.0` | 21 |
| 26.1 / 26.1.1 / 26.1.2 | ≥ 0.40.0 | `0.2.0+26.1` | 25 |

Fabric Loader ≥ 0.19.2. Fabric API required.

## Installation

1. Install [Flashback](https://modrinth.com/mod/flashback) (required).
2. Install [Fabric API](https://modrinth.com/mod/fabric-api) (required).
3. Drop the matching FlashbackTurbo jar from [Modrinth](https://modrinth.com/mod/flashbackturbo) into your `mods/` folder.

## Building from source

```bash
./scripts/fetch-flashback.sh   # downloads Flashback jar into libs/ (gitignored)
./gradlew build                # produces build/libs/flashbackturbo-x.y.z.jar
```

For the 26.1.x branch (Mojang mappings + Loom 1.15-SNAPSHOT), see [docs/HOOKS.md](docs/HOOKS.md) §H5 and [docs/SPEC.md](docs/SPEC.md) §8.

## Configuration

`<game>/config/flashbackturbo.json` is created with safe defaults on first launch:

```json
{
  "liftResolutionCap": true,
  "tuneFFmpegThreading": true,
  "parallelPngWriter": true,
  "pngCompressionLevel": 1,
  "gpuColorspaceConversion": false,
  "showExportProgressOverlay": true
}
```

## What this addon does NOT do

- It does **not** redistribute any Flashback code. The Flashback jar is compile-only at build time (gitignored under `libs/`). At runtime, your installed copy of Flashback is patched in-memory via Mixin.
- It does **not** modify Flashback's render or PerfectFrames stabilization — that is Flashback's quality guarantee.
- It does **not** silently change any output without an explicit user setting.

## License

This addon is licensed All-Rights-Reserved by Zeffut (mirroring Flashback's license posture). It is designed not to violate Flashback's "do not redistribute" clause: no Flashback bytes ship in our jar.

## Related

Sibling project by the same author: [MultiView](https://github.com/Zeffut/MultiView) — multi-POV Flashback replays.
