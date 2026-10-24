#!/usr/bin/env python3
"""Export Anima Turbo's missing model components for MediaTek A16W4.

Use alongside npu-export/compile_transformer_blocks.py (28 DiT blocks).
This contains genuine checkpoint weights and operator graphs, not placeholders.
It deliberately does not claim a complete working Android runtime: the separate
graphs must be connected in the proper order and verified against PyTorch.
"""
from __future__ import annotations

import argparse
import gc
import hashlib
import json
from pathlib import Path
import time

import torch
import torch.nn.functional as F
from safetensors import safe_open
from safetensors.torch import save_file
from torchao.quantization.pt2e.quantize_pt2e import convert_pt2e, prepare_pt2e
from executorch import exir
from executorch.backends.mediatek import NeuropilotPartitioner, NeuropilotQuantizer, Precision
from executorch.exir.backend.backend_details import CompileSpec
from executorch.exir.program._program import to_edge_transform_and_lower


ROOT = "model.diffusion_model."


class RMSNorm(torch.nn.Module):
    def __init__(self, width: int):
        super().__init__()
        self.weight = torch.nn.Parameter(torch.ones(width))
        self.eps = 1e-6

    def forward(self, x):
        return x * torch.rsqrt(x.square().mean(-1, keepdim=True) + self.eps) * self.weight


class Stem(torch.nn.Module):
    """Real x_embedder after 2x2 patchification on the CPU: 17*2*2=68."""
    def __init__(self):
        super().__init__()
        self.proj = torch.nn.Linear(68, 2048, bias=False)

    def forward(self, patches):
        return self.proj(patches)


class Timestep(torch.nn.Module):
    """Input: Comfy's 2048-wide sinusoidal timestep (precomputed on CPU)."""
    def __init__(self):
        super().__init__()
        self.linear_1 = torch.nn.Linear(2048, 2048, bias=False)
        self.linear_2 = torch.nn.Linear(2048, 6144, bias=False)
        self.norm = RMSNorm(2048)

    def forward(self, sinusoidal):
        ada_lora = self.linear_2(F.silu(self.linear_1(sinusoidal)))
        return self.norm(sinusoidal), ada_lora


class Final(torch.nn.Module):
    """Return 64 values per 2x2 patch; unpatchify on CPU to 16 channels."""
    def __init__(self):
        super().__init__()
        self.adaln_1 = torch.nn.Linear(2048, 256, bias=False)
        self.adaln_2 = torch.nn.Linear(256, 4096, bias=False)
        self.linear = torch.nn.Linear(2048, 64, bias=False)

    def forward(self, x, step, ada_lora):
        shift, scale = (self.adaln_2(self.adaln_1(F.silu(step))) + ada_lora[:, :4096]).chunk(2, -1)
        norm = F.layer_norm(x, (2048,), eps=1e-6)
        return self.linear(norm * (1 + scale[:, None, :]) + shift[:, None, :])


def rotate_half(x):
    a, b = x.chunk(2, dim=-1)
    return torch.cat((-b, a), dim=-1)


class AdapterAttention(torch.nn.Module):
    def __init__(self):
        super().__init__()
        self.q_proj = torch.nn.Linear(1024, 1024, bias=False)
        self.k_proj = torch.nn.Linear(1024, 1024, bias=False)
        self.v_proj = torch.nn.Linear(1024, 1024, bias=False)
        self.q_norm = RMSNorm(64)
        self.k_norm = RMSNorm(64)
        self.o_proj = torch.nn.Linear(1024, 1024, bias=False)

    def forward(self, x, context, cos_x, sin_x, cos_ctx, sin_ctx):
        b, q_len, _ = x.shape
        k_len = context.shape[1]
        q = self.q_norm(self.q_proj(x).reshape(b, q_len, 16, 64).transpose(1, 2))
        k = self.k_norm(self.k_proj(context).reshape(b, k_len, 16, 64).transpose(1, 2))
        v = self.v_proj(context).reshape(b, k_len, 16, 64).transpose(1, 2)
        q = q * cos_x[None, None, :, :] + rotate_half(q) * sin_x[None, None, :, :]
        k = k * cos_ctx[None, None, :, :] + rotate_half(k) * sin_ctx[None, None, :, :]
        attention = torch.softmax((q @ k.transpose(-2, -1)) * (64 ** -0.5), -1)
        output = (attention @ v).transpose(1, 2).reshape(b, q_len, 1024)
        return self.o_proj(output)


class AdapterBlock(torch.nn.Module):
    """One genuine LLMAdapter transformer block, matching ComfyUI state_dict."""
    def __init__(self):
        super().__init__()
        self.norm_self_attn = RMSNorm(1024)
        self.self_attn = AdapterAttention()
        self.norm_cross_attn = RMSNorm(1024)
        self.cross_attn = AdapterAttention()
        self.norm_mlp = RMSNorm(1024)
        self.mlp = torch.nn.Sequential(
            torch.nn.Linear(1024, 4096, bias=True),
            torch.nn.GELU(),
            torch.nn.Linear(4096, 1024, bias=True),
        )

    def forward(self, x, context, cos_x, sin_x, cos_ctx, sin_ctx):
        normed = self.norm_self_attn(x)
        x = x + self.self_attn(normed, normed, cos_x, sin_x, cos_x, sin_x)
        normed = self.norm_cross_attn(x)
        x = x + self.cross_attn(normed, context, cos_x, sin_x, cos_ctx, sin_ctx)
        return x + self.mlp(self.norm_mlp(x))


class AdapterOutput(torch.nn.Module):
    def __init__(self):
        super().__init__()
        self.out_proj = torch.nn.Linear(1024, 1024, bias=True)
        self.norm = RMSNorm(1024)

    def forward(self, x):
        return self.norm(self.out_proj(x))


def rope(seq: int):
    theta = torch.arange(0, 64, 2, dtype=torch.float32) / 64
    inverse = 1.0 / (10000 ** theta)
    freqs = torch.arange(seq, dtype=torch.float32)[:, None] * inverse[None, :]
    doubled = torch.cat((freqs, freqs), -1)
    return doubled.cos(), doubled.sin()


def sample_args(name: str, tokens: int, adapter_tokens: int, context_tokens: int, seed: int):
    g = torch.Generator().manual_seed(seed)
    def rand(*shape):
        return torch.randn(shape, generator=g, dtype=torch.float32) * 0.1
    if name == "stem":
        return (rand(1, tokens, 68),)
    if name == "timestep":
        return (rand(1, 2048),)
    if name == "final":
        return rand(1, tokens, 2048), rand(1, 2048), rand(1, 6144)
    if name.startswith("adapter_block_"):
        cos_x, sin_x = rope(adapter_tokens)
        cos_c, sin_c = rope(context_tokens)
        return (rand(1, adapter_tokens, 1024), rand(1, context_tokens, 1024),
                cos_x, sin_x, cos_c, sin_c)
    if name == "adapter_output":
        return (rand(1, adapter_tokens, 1024),)
    raise ValueError(f"Unknown component {name}")


def load_weights(module, handle, name: str):
    if name == "stem":
        names = {"proj.weight": ROOT + "x_embedder.proj.1.weight"}
    elif name == "timestep":
        names = {
            "linear_1.weight": ROOT + "t_embedder.1.linear_1.weight",
            "linear_2.weight": ROOT + "t_embedder.1.linear_2.weight",
            "norm.weight": ROOT + "t_embedding_norm.weight",
        }
    elif name == "final":
        names = {
            "adaln_1.weight": ROOT + "final_layer.adaln_modulation.1.weight",
            "adaln_2.weight": ROOT + "final_layer.adaln_modulation.2.weight",
            "linear.weight": ROOT + "final_layer.linear.weight",
        }
    elif name.startswith("adapter_block_"):
        block_idx = int(name.split("_")[-1])
        base = ROOT + f"llm_adapter.blocks.{block_idx}."
        names = {k: base + k for k in module.state_dict()}
    elif name == "adapter_output":
        names = {k: ROOT + "llm_adapter." + k for k in module.state_dict()}
    else:
        raise ValueError(name)

    known = set(handle.keys())
    missing = [v for v in names.values() if v not in known]
    if missing:
        raise ValueError(f"Checkpoint is missing real {name} weights: {missing}")
    found = {}
    for dst, src in names.items():
        t = handle.get_tensor(src)
        expected = module.state_dict()[dst].shape
        if tuple(t.shape) != tuple(expected):
            raise ValueError(f"Invalid shape for {src}: {tuple(t.shape)} vs {tuple(expected)}")
        found[dst] = t.float().contiguous()
    module.load_state_dict(found, strict=True)
    return names


def compile_component(model, name, args, device, outdir):
    model.eval()
    with torch.no_grad():
        print(f"{name}: capture", flush=True)
        captured = torch.export.export(model, args, strict=True).module()
        quantizer = NeuropilotQuantizer()
        quantizer.setup_precision(Precision.A16W4)
        prepared = prepare_pt2e(captured, quantizer)
        for seed in (13, 42, 101):
            prepared(*sample_args(name, args[0].shape[1] if name in ("stem", "final") else 1024,
                                  args[0].shape[1] if name.startswith("adapter_block_") or name == "adapter_output" else 512,
                                  args[1].shape[1] if name.startswith("adapter_block_") else 512, seed))
        quantized = convert_pt2e(prepared, fold_quantize=False)
        exported = torch.export.export(quantized, args, strict=True)
        lowered = to_edge_transform_and_lower(
            exported,
            compile_config=exir.EdgeCompileConfig(_check_ir_validity=False),
            partitioner=[NeuropilotPartitioner([CompileSpec("platform-config", device.encode())])],
        )
        data = lowered.to_executorch(config=exir.ExecutorchBackendConfig()).buffer
        if b"NeuropilotBackend" not in data:
            raise RuntimeError(f"{name}: no Neuron delegate found; no output accepted")
    out = outdir / f"{name}_{device}_a16w4.pte"
    out.write_bytes(data)
    return {"name": name, "filename": out.name, "bytes": len(data),
            "sha256": hashlib.sha256(data).hexdigest(),
            "precision": "A16W4", "contains_neuron_delegate": True,
            "warning": "A PTE with a backend marker does not prove every op is delegated or that the device accepts it."}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--checkpoint", type=Path, required=True)
    parser.add_argument("--out-dir", type=Path, default=Path("out/anima-a16w4"))
    parser.add_argument("--platform", default="mt6989")
    parser.add_argument("--embedding-precision", choices=("int4", "fp16"), default="int4")
    parser.add_argument("--check-only", action="store_true", help="Verify all checkpoint mappings, no PTE compiler")
    parser.add_argument("--tokens", type=int, default=1024, help="512x512 latent image uses 1024 tokens")
    parser.add_argument("--adapter-tokens", type=int, default=512)
    parser.add_argument("--context-tokens", type=int, default=512)
    parser.add_argument("--only", default="all", choices=("all", "stem", "timestep", "final",
                                                         "adapter", "adapter_output", "embedding"))
    opts = parser.parse_args()
    opts.out_dir.mkdir(parents=True, exist_ok=True)
    manifest = {"model": "Anima Turbo v1.1", "platform": opts.platform,
                "diffusion_tokens": opts.tokens, "adapter_tokens": opts.adapter_tokens,
                "context_tokens": opts.context_tokens, "precision": "A16W4",
                "embedding_precision": opts.embedding_precision,
                "other_parts": [], "complete_model": False,
                "runtime_stitched": False, "device_inference_verified": False}
    parts = (["stem", "timestep", "final"] +
             [f"adapter_block_{i:02d}" for i in range(6)] +
             ["adapter_output", "embedding"])
    if opts.only != "all":
        if opts.only == "adapter":
            parts = [x for x in parts if x.startswith("adapter_block_")]
        else:
            parts = [opts.only]
    started = time.monotonic()
    existing = opts.out_dir / "remaining_manifest.json"
    if existing.is_file():
        old = json.loads(existing.read_text())
        metadata_keys = ("platform", "diffusion_tokens", "adapter_tokens",
                         "context_tokens", "embedding_precision")
        if all(old.get(k) == manifest.get(k) for k in metadata_keys):
            manifest["other_parts"] = [
                p for p in old.get("other_parts", [])
                if (opts.out_dir / p["filename"]).is_file()
                and (opts.out_dir / p["filename"]).stat().st_size == p.get("bytes")
            ]
            print(f"Resuming: {len(manifest['other_parts'])} existing components", flush=True)
    with safe_open(str(opts.checkpoint), framework="pt", device="cpu") as handle:
        for name in parts:
            if not opts.check_only and any(p["name"] == name for p in manifest["other_parts"]):
                print(f"Skipping existing {name}", flush=True)
                continue
            tick = time.monotonic()
            if name == "embedding":
                k = ROOT + "llm_adapter.embed.weight"
                if k not in handle.keys():
                    raise ValueError(f"Missing embedding {k}")
                if opts.check_only:
                    print("CHECK OK embedding: checkpoint weight exists", flush=True)
                    continue
                tensor = handle.get_tensor(k).to(torch.float32).contiguous()
                if tuple(tensor.shape) != (32128, 1024):
                    raise ValueError(f"Unexpected llm_adapter embedding shape: {list(tensor.shape)}")
                if opts.embedding_precision == "int4":
                    scale = (tensor.abs().amax(dim=-1, keepdim=True) / 7).clamp_min(1e-8)
                    q = (tensor / scale).round().clamp(-8, 7).to(torch.int16)
                    packed = ((q[:, 0::2] & 15) | ((q[:, 1::2] & 15) << 4)).to(torch.uint8)
                    # Runtime: low/high nibble interpreted as signed 4-bit, multiplied by row scale.
                    reconstructed = q.float() * scale
                    error = (tensor - reconstructed).abs().mean().item()
                    dest = opts.out_dir / "llm_adapter_embedding_int4.safetensors"
                    save_file({"packed_int4": packed.contiguous(),
                               "row_scale_fp16": scale.half().contiguous()}, str(dest))
                    result = {"name": name, "filename": dest.name, "bytes": dest.stat().st_size,
                              "precision": "SYM_INT4_ROW", "quantized_dims": [32128, 1024],
                              "packing": "low_nibble_even; high_nibble_odd; signed_twos_complement",
                              "execution": "CPU token-table unpack and scale before 6 NPU blocks",
                              "quantization_mean_absolute_error": round(error, 7),
                              "sha256": hashlib.sha256(dest.read_bytes()).hexdigest()}
                    del q, packed, scale, reconstructed
                else:
                    dest = opts.out_dir / "llm_adapter_embedding_fp16.safetensors"
                    save_file({"embedding.weight": tensor.half().contiguous()}, str(dest))
                    result = {"name": name, "filename": dest.name, "bytes": dest.stat().st_size,
                              "precision": "FP16", "execution": "CPU token-table lookup before 6 NPU blocks",
                              "sha256": hashlib.sha256(dest.read_bytes()).hexdigest()}
                del tensor
            else:
                model = (Stem() if name == "stem" else Timestep() if name == "timestep"
                         else Final() if name == "final" else AdapterOutput() if name == "adapter_output"
                         else AdapterBlock())
                mapped = load_weights(model, handle, name)
                if opts.check_only:
                    print(f"CHECK OK {name}: {len(mapped)} mapped checkpoint tensors", flush=True)
                    del model
                    continue
                args = sample_args(name, opts.tokens, opts.adapter_tokens, opts.context_tokens, 123)
                result = compile_component(model, name, args, opts.platform, opts.out_dir)
                result["source_parameters"] = len(mapped)
                del model, args
            result["compile_seconds"] = round(time.monotonic() - tick, 1)
            manifest["other_parts"].append(result)
            (opts.out_dir / "remaining_manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
            print(f"Exported {name} in {result['compile_seconds']}s: {result['bytes']:,} bytes", flush=True)
            gc.collect()
    if opts.check_only:
        print("All selected checkpoint component mappings validated (compilation not run).", flush=True)
        return
    dit_path = opts.out_dir / "manifest.json"
    if dit_path.is_file():
        dit = json.loads(dit_path.read_text())
        compiled = dit.get("blocks", [])
        dit_ready = (dit.get("complete_transformer") and len(compiled) == 28
                     and dit.get("latent_tokens") == opts.tokens
                     and all((opts.out_dir / x["filename"]).is_file() for x in compiled))
        extra_ready = len({part["name"] for part in manifest["other_parts"]}) == 11
        manifest["all_weights_exported"] = bool(dit_ready and extra_ready)
        manifest["all_component_artifacts_ready"] = bool(dit_ready and extra_ready)
        manifest["full_graph_partitioned"] = False
        manifest["runtime_stitched"] = False
        (opts.out_dir / "combined_manifest.json").write_text(
            json.dumps({"dit": dit, "other": manifest}, indent=2) + "\n")
    print(f"All selected components exported in {time.monotonic()-started:.1f}s", flush=True)
    print("RUNTIME INTEGRATION AND NUMERICAL VALIDATION STILL REQUIRED", flush=True)


if __name__ == "__main__":
    main()
