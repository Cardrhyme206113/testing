# Anima Pocket — NPU transformer operation feasibility / v0.5.1

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


### v0.4.0 performance lab

- GitHub Actions now calls **assembleRelease** so the NDK/CMake GGML and
  stable-diffusion.cpp build use optimized Release compile flags. The release APK
  is signed using GitHub's ephemeral Android debug signing identity for testing.
  Native engine diagnostic logs remain available. No claim of a measurable speedup
  before a device comparison.
- The original **disk-backed diffusion weights** mode remains default. An opt-in
  GPU-resident diffusion weight mode uses \`params_backend=diffusion=vulkan0\`.
  On 1.5 GiB managed Vulkan, resident model weights plus activations may not fit.
  The optional **2.5 GiB managed budget** is a less restrictive alternative;
  auto is still unlimited and can stall or restart the phone.
- Selectable **256×256** and **320×320** performance-test resolutions alongside
  existing 384/512 settings. The Anima model's quality may degrade at very low
  resolution; these are speed experiments, not final image-quality presets.
- A manual **Benchmark selected settings (2 diffusion steps)** button runs
  the current settings with two steps, saves a generated PNG, and records step 1
  (initialization) and step 2 (warm) durations to
  \`files/performance-benchmarks.csv\`; use **Copy performance history** to export.
  Layer profiling must be off for comparisons. No unattended multi-run stress
  loop: a Vulkan driver stall/reboot is possible.
- **Benchmark Mali GPU Q4_0 vs FP16 kernels** runs a small synthetic 2048×2048
  by 2048×128 GGML matrix multiplication on Vulkan0 in both formats and compares
  its output against a CPU backend with the same quantized weights. Reports
  first-iteration time, mean of three warmed iterations, numerical RMSE and
  nonfinite counts. It does **not** quantize or replace the real model.
- Maintain the existing persistent logs, normal inference path, selectable
  Flash Attention, and diagnostic layer-profile opt-in. Shader/kernel
  performance must be measured on the real Mali GPU; successful GitHub
  compilation alone does not verify runtime inference or numerical correctness.


### v0.4.1: Q4_0 -> temporary FP16 Vulkan matrix compute

An optional checkbox forces Q4_0 multi-column matmuls through the already
available GGML Vulkan Q4_0 dequant shader into reusable FP16 scratch, followed
by an FP16 matrix kernel. It does not change file quantization, keep all weights
expanded, or affect non-Q4_0 operations. It may be faster or slower and increase
transient GPU memory. Default remains OFF. Benchmark the standalone Q4 native,
Q4 temporary FP16 and FP16 paths at N=128 and N=576 before full generation.
The two-step benchmark now exports q4ComputeMode as an additional CSV column.
Testing runtime speed and correctness requires the real Mali GPU.

### v0.4.2 Speed experiments

Original 384x384 four-step inference is unchanged by default. For comparison,
the app now exposes 2-step and 3-step sampling (quality can degrade);
upstream EasyCache OFF/conservative threshold=0.10/balanced threshold=0.20
(approximate reuse of denoiser outputs, quality may change); and configurable
2/4/6 CPU threads for the text encoder and TAE decode. None of these is a
measured full-quality speedup until tested on Mali-G720. The engine logs
how many sampling steps EasyCache actually skips. Benchmark CSV adds
cacheProfile, cpuThreads and steps columns. The TAE decoder heartbeat
now correctly identifies image decoding instead of diffusion step 4/4.
Do not run experimental Vulkan profiles unattended.

### v0.5.0 NPU hardware feasibility

This adds a separate hardware-only NPU test without touching Anima's working
GGML Vulkan diffusion path. The button enumerates Android NNAPI devices,
selects only an explicitly reported dedicated accelerator or a vendor-named
NPU (not GPU or CPU), checks FP16 FULLY_CONNECTED support, and compiles one
64x1024 -> 64x1024 projection. Compilation uses
ANeuralNetworksCompilation_createForDevices with exactly the hardware device,
which **disables NNAPI CPU fallback**. It measures three warmed executions and
checks 32 output values against a CPU mathematical reference.

The Android NNAPI API was deprecated in Android 15; absence of an NNAPI NPU
driver does not rule out using LiteRT/NeuroPilot's separate vendor runtime.
This test does NOT port the Anima transformer yet. A successful test only
proves one FP16 operation runs on an advertised accelerator without CPU
fallback. No model assets, root, or ADB required. The selected device name
and exact status are displayed and copied with Copy NPU probe report.


### v0.5.1 NPU operation-level transformer feasibility

Following the confirmed MTK-MDLA NNAPI FP16 result, this build probes
**both MDLA and Neuron** rather than stopping after the first successful
device. It checks support and *hardware-only compilation* for ten
Anima-related FP16 operators: BATCH_MATMUL, SOFTMAX, ADD, MUL, MEAN, RSQRT,
TRANSPOSE, LOGISTIC, TANH, and RESHAPE. These are **isolated synthetic
operations**, not the full fused Anima attention or RMSNorm graph. If a
device rejects an operation, it may still run via LiteRT/NeuroPilot or a
different graph representation.

The original 64x1024 FP16 projection benchmark is retained, and any
successful MDLA or Neuron device is additionally tested on a
**128x2048 by 2048x2048 FP16 dense projection**, matching the dimensional
shape of the earlier Vulkan benchmark (but not identical kernels,
quantization, or scheduling). Reports include compilation time, warm
execution, and sampled numerical error. CPU fallback remains disabled.
Original Vulkan generation is unchanged; no Anima weights are used.
