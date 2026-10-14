# Anima Pocket — Safe profile / v0.2

Experimental offline Anima image generation for Android ARM64 using stable-diffusion.cpp.

### Required files (not bundled in APK)
- Main Anima Turbo Q4_0 GGUF: https://huggingface.co/vanes430/Anima-Turbo-V1.1-GGUF/resolve/main/anima-turbo-v1.1-Q4_0.gguf?download=true
- **Qwen3-0.6B-Base Q4_K_M GGUF encoder**: https://huggingface.co/Antigma/Qwen3-0.6B-Base-GGUF/resolve/main/qwen3-0.6b-base-q4_k_m.gguf?download=true
- TAEW2.1 tiny decoder: https://github.com/madebyollin/taehv/raw/refs/heads/main/safetensors/taew2_1.safetensors

Files are selected through the Android file picker, copied into a private working cache when necessary, and validated using their headers. The original browser downloads survive app resets. Qwen3-0.6B-Base safetensors remains compatible, but GGUF is recommended for lower memory usage. Do not substitute Qwen3 Instruct.

### Safety-focused profiles
- **Default: CPU only**; GPU probing is not performed on launch. CPU inference can be extremely slow and may still exceed memory.
- Optional explicit-acknowledgement Vulkan profiles: 1.5 GiB or 2.0 GiB **managed Vulkan budget**. Not a hard OS/driver memory cap, and a Mali driver stall may still reboot the phone.
- Runtime: diffusion on Vulkan, TE and VAE on CPU, or CPU for all in diagnostic mode.
- Weights: diffusion=disk, te=cpu, vae=cpu for all modes. `auto_fit=false`, `disable_prefetch=true`, `eager_load=false`, `enable_mmap=true`, `n_threads=2`, conditioning cache off. Expect potential slow disk-backed inference.
- Preflight avoids inference if Android reports low memory or < 3 GiB RAM available (not a stability guarantee).
- 512×512, 4 steps default. 384×384 lower-pressure option. Flash attention defaults off.
- On-device native engine logs, model header inspection, copy/share logs.
- GGUF main-model provider file < 200 MiB rejected, and all invalid headers rejected before model initialization.

GitHub Actions workflow **Anima Pocket Android** builds debug signed APKs on the `anima-pocket` branch. The GitHub-hosted debug signing key is ephemeral, so updating a previous test APK may require uninstalling it first.

**No claim of on-device correctness or reboot prevention.** Build success verifies compilation only.
