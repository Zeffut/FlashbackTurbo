# FlashbackTurbo

[![Modrinth](https://img.shields.io/badge/Modrinth-flashbackturbo-00AF5C?logo=modrinth)](https://modrinth.com/mod/flashbackturbo)

A Fabric addon for [Flashback](https://modrinth.com/mod/flashback) that drastically speeds up video export — **without quality loss**.

> Status: **0.6.0** ([GitHub releases](https://github.com/Zeffut/FlashbackTurbo/releases)).
> Measured speedup: **10.95× at 1080p** on a real Flashback replay, pixels decoded strictly identical.
> Plus animated post-export overlay (H8), ~10× faster MP4 finalize via fragmented muxer (H9),
> H10 — fixes a Flashback export crash on mid-replay exports, and **anonymous usage telemetry**
> (0.4.0, opt-out) to prioritise improvements.
>
> **Proven in the field.** Validated across hundreds of real anonymous exports on **Windows
> (primary platform), macOS and Linux** — fastest on an NVIDIA GPU (~8.5 s for a 1080p 60 fps
> clip). Export failure rate **~1 %**, and every observed failure was external (not enough RAM
> allocated for very long replays, or the game closed mid-export) — never a regression from
> FlashbackTurbo itself.

## What it does

FlashbackTurbo patches Flashback's export pipeline via Mixin to replace serial, low-tuned operations with parallel ones:

| Hook | Target | Impact |
|------|--------|--------|
| **H2** | `PNGSequenceVideoWriter` | Parallel PNG writer using N-1 threads |
| **H3** | `NativeImage.writeToFile` redirect | Configurable zlib level (default L1 instead of Mojang's hardcoded L6) |
| **H4** | `AsyncFFmpegVideoWriter` ctor | Removed the silent 4K downscale cap |
| **H6** | `AsyncFFmpegVideoWriter.start()` | FFmpeg threading tunes per encoder (nvenc, qsv, videotoolbox, libopenh264) |
| **H7** | `PNGSequenceVideoWriter.encode()` | PNG color type 2 (RGB) when transparency is off, eliminates alpha cleanup loop |
| **H8** | `AsyncFFmpegVideoWriter.finish()` | Animated "Saving..." overlay during post-export finalize phase (Flashback's own progress display doesn't update once export loop ends) |
| **H9** | `AsyncFFmpegVideoWriter` recorder options | Fragmented MP4 (`movflags=+frag_keyframe+empty_moov`) on hardware encoders — eliminates the moov atom rewrite, ~10× faster finalize |
| **H10** | `ExportJob.setup()` | Fixes a Flashback crash: `setup()` reads `mc.level` after a single `runClientTick`, which is null on a mid-replay export (server seek reloads the world) → `NullPointerException`. Pumps `runClientTick` until the level is loaded. |
| **H11** | `AsyncFFmpegVideoWriter.start()` | Auto-promotes `libopenh264` (slow software encoder) → `h264_nvenc`/`h264_qsv` when a usable GPU encoder is detected — for the ~13% of exports on software-only configs. Fail-safe fallback to software. SSIM ≥ 0.99. |

All hooks are opt-in via `<game>/config/flashbackturbo.json` and default to safe values. Toggle any of them off to fall back to vanilla Flashback behaviour.

## Telemetry & privacy

Since **0.4.0**, FlashbackTurbo sends **anonymous** usage telemetry (PostHog) to help prioritise
improvements — which export formats, resolutions and encoders are used, how often each hook fires,
and **export failures** (the most useful signal for a mod whose job is *not* to break your exports).

- **Anonymous.** Your identifier is a random UUID generated locally (`config/flashbackturbo_telemetry.json`).
  No username, no IP, no file paths, no world names — exception messages are sanitised before sending.
- **Fail-safe.** Telemetry runs off the export path and swallows all its own errors. It can never
  slow down or break an export, even if the network is down.
- **Opt-out.** Set `"enableTelemetry": false` in `config/flashbackturbo.json` to disable it
  completely — no network calls, no identifier file created.

Events collected: mod load, export start/finish/failed/cancelled (with format, encoder, resolution,
framerate, duration), and hook activations (H4/H8/H10).

## Performance

### Controlled benchmark (PNG sequence path)

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

### Real-world field data (MP4 export)

Median export time across real anonymous exports (telemetry, ~1080p clips), by encoder:

| Platform · encoder        | Median export |
|---------------------------|---------------|
| Windows · NVIDIA `h264_nvenc` | **~8.5 s** (60 fps) |
| Windows · Intel `h264_qsv`    | ~23 s (60 fps) |
| macOS · `h264_videotoolbox`   | ~26 s (30 fps) |
| Software fallback `libopenh264` | ~55 s |

The fastest path is Windows + an NVIDIA GPU. The slowest is the pure-software fallback
(`libopenh264`) on machines where no hardware encoder is picked — the next release targets
exactly this case by auto-promoting those exports to the GPU encoder when one is available.

## Quality

**Lossless PNG output.** zlib level only changes file size, decoded pixels are bit-identical. Color type 2 vs 6 (RGB vs RGBA) only differs when transparency is disabled — visually identical.

For the FFmpeg side (H4 + H6), output is byte-identical to vanilla (threading tunes don't affect bitstream).

## Hardware encoders

Flashback's bundled FFmpeg (bytedeco, LGPL) ships these H.264 encoders: `libopenh264` (software),
`h264_nvenc` (NVIDIA), `h264_qsv` (Intel) and `h264_videotoolbox` (macOS). Pick the hardware
encoder in Flashback's export UI for the fastest result; FlashbackTurbo's H6 tunes its threading
on top. (Note: `libx264` is **not** in the bundled build — that's why "H.264" defaults to the
slower `libopenh264` when no hardware encoder is selected.)

## Auto-update

Since **0.6.0**, FlashbackTurbo silently keeps your Zeffut mods up to date. On launch, a background
thread hashes the jars in `mods/`, asks Modrinth for the latest matching version (current loader +
MC version), downloads verified updates into `.autoupdate/staging/`, and swaps them in at game exit
(a detached helper finishes the swap on Windows where jars stay locked). Only mods owned by the
configured account are touched.

Opt-out / tune in `config/flashbackturbo.json`:

```json
{
  "autoUpdate": true,
  "updateOwner": "Zeffut",
  "updateAll": false,
  "updateExclude": ""
}
```

Set `"autoUpdate": false` to disable. Override at runtime with `-Dautoupdate.enabled=false`.

## Compatibility

| MC version | Flashback version | FlashbackTurbo version | Java |
|------------|-------------------|------------------------|------|
| 1.21.9 / 1.21.10 / 1.21.11 | ≥ 0.39.0 | `0.6.0` | 21 |
| 26.1 / 26.1.1 / 26.1.2 | ≥ 0.40.0 | `0.6.0+26.1` | 25 |

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
  "showExportProgressOverlay": true,
  "useFragmentedMp4OnHwEncoders": true,
  "fixExportSetupRace": true,
  "promoteSoftwareToHardwareEncode": true
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
