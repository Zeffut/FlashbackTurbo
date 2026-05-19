# FlashbackTurbo — Next steps

Before writing any mod code, resolve the four open questions in [SPEC.md §6](./SPEC.md):

1. **Feasibility scan** — clone Flashback, inspect:
   - `AsyncFFmpegVideoWriter` — is it `final`? Is `start()` accessible to a Mixin? Where are the `ProcessBuilder` args constructed?
   - `PNGSequenceVideoWriter` — is the per-frame write a single method?
   - `SaveableFramebufferQueue` — does it use `glReadPixels` directly?

2. **License clarification** — re-read Flashback's `LICENSE.md`, confirm that Mixin-based addons are permitted. If unclear, ask Moulberry directly via the Flashback Discord (`#support` or `#addons`).

3. **Baseline benchmark** — run a vanilla Flashback export on a known 5-min replay and record:
   - total wall time
   - per-stage time if any logs are available
   - CPU / GPU utilisation

4. **Demand validation** — before writing a line of Java, ask 3+ Flashback users (Discord, Modrinth comments) what their typical export time is and whether it bothers them. If nobody complains, this whole project is solving a hypothetical problem.

Only once these four checks are complete should the actual scaffolding (Gradle, Loom, Mixin config, `fabric.mod.json`) begin.
