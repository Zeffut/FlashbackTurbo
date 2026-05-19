# FlashbackTurbo

A Fabric addon for [Flashback](https://modrinth.com/mod/flashback) that speeds up video export by using hardware-accelerated encoders and parallel I/O — without touching render quality or the PerfectFrames pipeline.

> Status: **scaffolding** — nothing implemented yet.

## Goals (in scope)

- Hardware video encoding when available:
  - macOS → `h264_videotoolbox` / `hevc_videotoolbox`
  - NVIDIA → `h264_nvenc` / `hevc_nvenc`
  - Intel → `h264_qsv`
  - AMD → `h264_amf`
- Parallel PNG sequence writing with configurable compression level (1–9, default 6).
- Pixel Buffer Object (PBO) double-buffered framebuffer readback to overlap GPU → CPU transfer with the next frame's render.
- A **Fast export** toggle in the export settings UI — opt-in, never silent.

## Non-goals (out of scope)

- Touching the PerfectFrames stabilisation logic — that is Flashback's quality guarantee.
- Replacing or forking Flashback's `AsyncFFmpegVideoWriter` — we hook via Mixin, never duplicate.
- Auto-tuning quality vs. speed without user consent.
- Anything that changes the final visual output without an explicit user setting.

## Compatibility

- Targets the same Minecraft versions as Flashback: 1.21.9 / 1.21.10 / 1.21.11 / 26.1.x.
- Fabric only. Requires Flashback installed.

## License

TBD — must not violate Flashback's restrictive license. No Flashback code is redistributed; this addon only exposes new behaviour via Mixin hooks.

## Repository

This is a sibling project to [MultiView](https://github.com/Zeffut/MultiView). The two mods are independent but target the same user base (Flashback users producing multi-POV recap videos).
