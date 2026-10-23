# Anima Pocket — deep GPU diagnostics / v0.3.3

Experimental offline Anima image generation for Android ARM64 using stable-diffusion.cpp.

### Required files (not bundled in APK)
- Main Anima Turbo Q4_0 GGUF: https://huggingface.co/vanes430/Anima-Turbo-V1.1-GGUF/resolve/main/anima-turbo-v1.1-Q4_0.gguf?download=true
- **Qwen3-0.6B-Base Q4_K_M GGUF encoder**: https://huggingface.co/mradermacher/Qwen3-0.6B-Base-GGUF
- TAEW2.1 tiny decoder: https://github.com/madebyollin/taehv/raw/refs/heads/main/safetensors/taew2_1.safetensors

Files are selected through the Android file picker. A quick format check verifies genuine GGUF or safetensors signatures **immediately after selection**, before copying into the private working cache. The recommended text-encoder link opens a model repository; choose its Q4_K_M GGUF file, not other variants or zipped downloads. The original browser downloads survive app resets. Qwen3-0.6B-Base safetensors remains compatible, but GGUF is recommended for lower memory usage. Do not substitute Qwen3 Instruct.

### GPU and memory profiles
- **Default: Vulkan auto** for the diffusion model, with Qwen and decoder on CPU. GPU probing is not performed on launch. This mode sets no explicit max_vram; it does not guarantee driver-safe memory usage and previously overloaded the device.
- Optional **Vulkan 1.5 GiB managed** profile to lower weights/runner buffer caching. This is not a hard OS/driver cap. CPU-only remains a slow diagnostic fallback.
- Vulkan is using shared phone memory; setting auto may cause system UI stalls or reboot. Explicit confirmation required for Vulkan runs.
- Runtime: diffusion on Vulkan, TE and VAE on CPU, or CPU for all in diagnostic mode.
- Weights: diffusion=disk, te=cpu, vae=disk by default, retaining Qwen's compact quantized GGUF in RAM for faster conditioning. The optional low-RAM encoder toggle uses disk-backed TE instead.
- `auto_fit=false`, `disable_prefetch=true`, `eager_load=false`, `enable_mmap=true`, `n_threads=2`, `batched_cfg=false`, conditioning cache off. Leave segmented compute enabled for managed Vulkan budgets. Disk-backed inference can be extremely slow.
- **No fixed free-RAM threshold**. Preflight only rejects Android's actual lowMemory=true state. 5-second memory monitoring logs RAM and process PSS. Low-memory cancellation is best-effort and **cannot interrupt native context creation**. Android's UI_HIDDEN (trim level 20) is no longer treated as critical pressure.
- 384×384, 4 steps default. 512×512 optional. Full VAE uses tiled decoding. Flash attention defaults off.
- On-device native engine logs, model header inspection, copy/share logs.
- GGUF main-model provider file < 200 MiB rejected, and all invalid headers rejected before model initialization.

GitHub Actions workflow **Anima Pocket Android** builds debug signed APKs on the `anima-pocket` branch. The GitHub-hosted debug signing key is ephemeral, so updating a previous test APK may require uninstalling it first.

The Qwen3 GGUF is passed to upstream stable-diffusion.cpp using `sd_ctx_params_t.llm_path`. Do not load a generic Qwen Instruct GGUF: Anima expects Qwen3-0.6B-Base. The app validates file signatures and a minimum encoder size; architecture and tensor compatibility still require an actual on-device loading test.

**No claim of on-device correctness, a hard RAM ceiling, or reboot prevention.** Build success verifies compilation only.

### v0.3.3 diagnostics

No ADB or root is required. A **HEARTBEAT** is persisted every 3 seconds independently
of the UI thread, with elapsed time, current inference stage, time since the last
native-engine log, Android available memory and lowMemory, process PSS and swapped PSS,
process RSS and VmSwap, global SwapTotal/SwapFree and swap-page deltas, zram compression
usage if readable, native inference thread state and CPU tick delta, and system thermal
status. Unsupported /proc or sysfs fields show n/a/-1 rather than invented values.
A nonzero swappedPSS or pswpin delta suggests swapping activity, not proof of GPU OOM.

The native runner is patched at build time (pinned upstream revision) to log
ANIMA_DIAG_BEGIN / ANIMA_DIAG_END timing around each actual compute segment.
Default Vulkan inference is kept as before: **one monolithic graph** if it fits.
An opt-in **DIAGNOSTIC: profile individual Anima transformer layers** checkbox
forces existing graph-cut segments on Vulkan to reveal layer progress, but
changes allocation and synchronization behavior and can make inference slower.
Compare default mode first; layer mode is an additional experiment, not a
performance benchmark.

The misleading "Sampling 685/685 · 0s" status now reports tensor-loading progress
separately from completed denoising steps. Elapsed time continues updating even when
the first GPU graph produces no progress callback.

Logs are written to private app storage at `files/diagnostics/last-run.txt` and
flushed frequently; older runs move to `previous-run.txt`. On the next launch,
**Copy saved run** recovers the last run even after a process crash/reboot;
**Copy previous run** recovers the one before it. Android process-exit reason
is also recorded when available. Do not uninstall the app before recovering logs:
uninstall deletes private app files. Logs are best effort and cannot capture a
GPU driver crash or system freeze that prevents Android from scheduling the watchdog.

This is a diagnostic build, not a proven stability or inference-speed fix.
