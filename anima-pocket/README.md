# Anima Pocket — streamed-memory profile / v0.3

Experimental offline Anima image generation for Android ARM64 using stable-diffusion.cpp.

### Required files (not bundled in APK)
- Main Anima Turbo Q4_0 GGUF: https://huggingface.co/vanes430/Anima-Turbo-V1.1-GGUF/resolve/main/anima-turbo-v1.1-Q4_0.gguf?download=true
- **Qwen3-0.6B-Base Q4_K_M GGUF encoder**: https://huggingface.co/Antigma/Qwen3-0.6B-Base-GGUF/resolve/main/qwen3-0.6b-base-q4_k_m.gguf?download=true
- TAEW2.1 tiny decoder: https://github.com/madebyollin/taehv/raw/refs/heads/main/safetensors/taew2_1.safetensors

Files are selected through the Android file picker, copied into a private working cache when necessary, and validated using their headers. The original browser downloads survive app resets. Qwen3-0.6B-Base safetensors remains compatible, but GGUF is recommended for lower memory usage. Do not substitute Qwen3 Instruct.

### Safety-focused profiles
- **Default: CPU only**; GPU probing is not performed on launch. CPU inference can be extremely slow and may still exceed memory.
- Optional explicit-acknowledgement Vulkan profiles: 1.0 GiB or 1.5 GiB **managed Vulkan budgets**, not hard OS/driver caps. A Mali driver stall or kernel OOM can still restart the device.
- Runtime: diffusion on Vulkan, TE and VAE on CPU, or CPU for all in diagnostic mode.
- Weights: by default diffusion=disk, te=disk, vae=disk, keeping the GGUF Qwen encoder file-backed and loaded lazily. Uncheck low-RAM encoder mode to keep TE weights in CPU RAM (faster but larger peak).
- `auto_fit=false`, `disable_prefetch=true`, `eager_load=false`, `enable_mmap=true`, `n_threads=2`, `batched_cfg=false`, conditioning cache off. Leave segmented compute enabled for managed Vulkan budgets. Disk-backed inference can be extremely slow.
- Preflight requires at least 3 GiB available for CPU or 5 GiB for Vulkan; 5-second memory monitoring logs available RAM and process PSS. Cancellation on low-memory is best-effort and **cannot interrupt native context creation**.
- 384×384, 4 steps default. 512×512 optional. Full VAE uses tiled decoding. Flash attention defaults off.
- On-device native engine logs, model header inspection, copy/share logs.
- GGUF main-model provider file < 200 MiB rejected, and all invalid headers rejected before model initialization.

GitHub Actions workflow **Anima Pocket Android** builds debug signed APKs on the `anima-pocket` branch. The GitHub-hosted debug signing key is ephemeral, so updating a previous test APK may require uninstalling it first.

The Qwen3 GGUF is passed to upstream stable-diffusion.cpp using `sd_ctx_params_t.llm_path`. Do not load a generic Qwen Instruct GGUF: Anima expects Qwen3-0.6B-Base. The app validates file signatures and a minimum encoder size; architecture and tensor compatibility still require an actual on-device loading test.

**No claim of on-device correctness, a hard RAM ceiling, or reboot prevention.** Build success verifies compilation only.
