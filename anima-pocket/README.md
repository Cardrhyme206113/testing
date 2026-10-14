# Anima Pocket · experimental Android native inference
Local offline Anima 2B / Anima Turbo app using stable-diffusion.cpp Vulkan backend.
Use Android browser to download model files, then select each using Android's document picker. Original downloads remain outside app; a private working copy is made once per selected file.

Required weights:
- Diffusion: https://huggingface.co/vanes430/Anima-Turbo-V1.1-GGUF/resolve/main/anima-turbo-v1.1-Q4_0.gguf?download=true
- Qwen3 encoder: https://huggingface.co/circlestone-labs/Anima/resolve/main/split_files/text_encoders/qwen_3_06b_base.safetensors?download=true
- Tiny decoder: https://github.com/madebyollin/taehv/raw/refs/heads/main/safetensors/taew2_1.safetensors
- Full alternative VAE: https://huggingface.co/circlestone-labs/Anima/resolve/main/split_files/vae/qwen_image_vae.safetensors?download=true

Build: Actions → Anima Pocket Android → latest successful build → AnimaPocket-ARM64-APK.
This is not tested on a Dimensity 9300+. Vulkan numerics and performance may fail. CPU mode is offered as a diagnostic fallback; keep the screen on while generating. GitHub Actions produces a debug-signed APK (not a verified release).
