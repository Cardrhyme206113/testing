# DreamLite MTK — experimental Android app

**Status: source prototype; APK build is automated, but image generation and NPU execution have not been validated on a Dimensity 9300+.** This is deliberately not advertised as a verified production app.

The branch was created separately from the Pages repo. The GitHub Actions workflow produces a debug APK artifact on pushes to this feature branch.

## What is implemented

- Native Android UI: arbitrary custom text prompts, 512/1024 selection, 1/2/4 steps, integer seed, image preview, PNG save.
- Offline user-selected document-tree importer. Files are copied to app-private storage (including ONNX external-data files), because ONNX Runtime needs local paths for large model initializers.
- No INTERNET permission and no weight download in the APK.
- Pure Java byte-level BPE for Qwen-compatible tokenizer.json; full export-specific Qwen3-VL chat templating must be provided in model-pack.json and verified against the Python reference.
- ONNX Runtime sessions for a separate text encoder, UNet, VAE decoder, and a basic four-step flow-matching Euler loop.
- 3 execution choices: NNAPI strict, NNAPI preferred, CPU. Strict disables NNAPI CPU device selection and the ORT CPU EP fallback.
- Timing and descriptive errors for unsupported model inputs.

## Build

From repository root:

    cd dreamlite-android
    gradle :app:assembleDebug

Or open the **Actions** tab, select **DreamLite MTK Android debug APK**, and download the **dreamlite-mtk-debug-apk** artifact when the workflow succeeds. The app is not published to any app store.

## Model files are *not* included

In a local PC folder put:

    model-pack.json
    tokenizer.json
    te_fp16.onnx
    te_fp16.onnx.data   (if the ONNX references external weights)
    unet_masked.onnx
    vae_decoder.onnx

Filename choices are taken from model-pack.json; you can use different filenames, provided the manifest references them.

The Android app asks you to select the directory. The app copies all top-level .onnx, .data, .json, .bin files. Only import model files you trust. Have enough free storage for a private copy, potentially >7 GB. Files are not downloaded over the network.

Start from the included model-pack.example.json, rename it to model-pack.json and adjust **all** I/O names and data layouts to match your actual exported graphs. If model-pack.json is absent, the importer generates a generic default; **that does not make arbitrary ONNX conversions compatible**.

### Sources of experimental ONNX exports

- DreamLite official implementation: https://github.com/ByteVisionLab/DreamLite
- Experimental QDD110 ONNX package: https://huggingface.co/QDD110/DreamLite-mobile-ONNX
- xay2001's test graph: https://huggingface.co/xay2001/dreamlite-android

The xay2001 fixed-prompt test package does **not** include the Qwen3-VL prompt encoder, so it cannot provide custom prompts by itself. The QDD110 package has a 2B Qwen-derived text encoder, but its actual ONNX input and output names, tokenizer, prompt chat-template, embedding slices, and inference scheduler need to be checked against the app contract before claiming successful generation. An ONNX file's existence alone does not prove an accurate end-to-end pipeline.

## Critical NPU limitation

Selecting **NNAPI strict** requests Android NNAPI from ONNX Runtime and sets:
- NNAPI CPU_DISABLED and FP16 enabled;
- ONNX Runtime's session.disable_cpu_ep_fallback=1.

This **does not select the MediaTek NPU specifically**: NNAPI can route to GPU or APU, and operators may be unsupported. No benchmark or working DreamLite W8A8 compile on your exact 9300+ has been verified here. Production-grade APU deployment should use MediaTek NeuroPilot's Neuron backend / ExecuTorch MediaTek backend, compile the precise diffusion graphs offline for the SoC, and perform real device profiling. The official backend has additional proprietary NeuroPilot build dependencies that cannot be embedded in a public reproducible APK workflow without appropriate SDK access.

References:
- https://docs.pytorch.org/executorch/stable/backends-mediatek.html
- https://onnxruntime.ai/docs/execution-providers/NNAPI-ExecutionProvider.html
- https://developer.android.com/ndk/guides/neuralnetworks

## Inference contract

The experimental renderer currently assumes:
- Text encoder receives input_ids and attention_mask, and returns floating [1,seq,hidden] embeddings.
- UNet receives sample, timestep, encoder_hidden_states (optional attention_mask), and predicts latent velocity. A double-width sample input can be filled with [noise,zero-condition].
- VAE decoder consumes [1,C,H,W] and emits [1,3,H,W]. Default output range is [-1,1].
- Flow-matching sigma decreases 1 -> 0 in 1–4 evenly-spaced steps, with 1000x timestep scaling by default; use timestep_multiplier=1 if required by your export.
- Image editing, exact Qwen3-VL multi-modal position IDs, prefix token dropping, DMD2-specific shift schedules, and export-specific additional UNet conditioning are **not implemented**.

Do not treat images from this early adapter as validated DreamLite outputs until a seeded image comparison with official Python generation passes. A proper conversion and APU compiler validation are the remaining blockers.

Model weights are governed by their own CC BY-NC license and upstream research-only restrictions; no model weights are redistributed in this repository.
