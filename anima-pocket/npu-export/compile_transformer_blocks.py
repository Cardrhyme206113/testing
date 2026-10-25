#!/usr/bin/env python3
"""Export ALL 28 genuine Anima Turbo transformer blocks as MediaTek A16W4 PTEs.

Each PTE accepts x, context, timestep embeddings and rotary factors.
This exports the complete denoiser's *transformer blocks*, not the text
adapter, input/output layers, sampler or VAE. Those require a runtime
bridge before full image generation is possible. Never claim otherwise.

Run one block first via --count 1 to validate the platform compiler.
"""
import argparse
import gc
import hashlib
import json
import zipfile
import numpy as np
from pathlib import Path
import time

import torch
from safetensors import safe_open
from torchao.quantization.pt2e.quantize_pt2e import convert_pt2e, prepare_pt2e
from executorch import exir
from executorch.backends.mediatek import NeuropilotPartitioner, NeuropilotQuantizer, Precision
from executorch.exir.backend.backend_details import CompileSpec
from executorch.exir.program._program import to_edge_transform_and_lower


class RMSNorm(torch.nn.Module):
    def __init__(self, dim):
        super().__init__()
        self.weight = torch.nn.Parameter(torch.ones(dim))

    def forward(self, x):
        return x * torch.rsqrt(x.square().mean(dim=-1, keepdim=True) + 1e-6) * self.weight


class AdaLayerNormZero(torch.nn.Module):
    """Matches ComfyUI Anima's AdaLN-LoRA formulation."""
    def __init__(self, dim):
        super().__init__()
        self.linear_1 = torch.nn.Linear(dim, 256, bias=False)
        self.linear_2 = torch.nn.Linear(256, 3 * dim, bias=False)

    def forward(self, x, step, temb):
        shift, scale, gate = (self.linear_2(self.linear_1(torch.nn.functional.silu(step))) + temb).chunk(3, -1)
        norm = torch.nn.functional.layer_norm(x, (x.shape[-1],), eps=1e-6)
        return norm * (1 + scale[:, None, :]) + shift[:, None, :], gate[:, None, :]


class Attention(torch.nn.Module):
    def __init__(self, cross=False):
        super().__init__()
        inner = 2048
        source = 1024 if cross else 2048
        self.cross = cross
        self.q_proj = torch.nn.Linear(inner, inner, bias=False)
        self.k_proj = torch.nn.Linear(source, inner, bias=False)
        self.v_proj = torch.nn.Linear(source, inner, bias=False)
        self.q_norm = RMSNorm(128)
        self.k_norm = RMSNorm(128)
        self.output_proj = torch.nn.Linear(inner, inner, bias=False)

    @staticmethod
    def apply_rope(x, cos, sin):
        left, right = x.chunk(2, dim=-1)
        rotate = torch.cat((-right, left), dim=-1)
        return x * cos[None, None, :, :] + rotate * sin[None, None, :, :]

    def forward(self, x, context, cos, sin):
        b, t, _ = x.shape
        kv = context if self.cross else x
        q = self.q_norm(self.q_proj(x).reshape(b, t, 16, 128).transpose(1, 2))
        k = self.k_norm(self.k_proj(kv).reshape(b, kv.shape[1], 16, 128).transpose(1, 2))
        v = self.v_proj(kv).reshape(b, kv.shape[1], 16, 128).transpose(1, 2)
        if not self.cross:
            q = self.apply_rope(q, cos, sin)
            k = self.apply_rope(k, cos, sin)
        # Explicit batch matmul/softmax: NNAPI supports these on mtk-neuron_shim.
        weights = torch.softmax(torch.matmul(q, k.transpose(-2, -1)) * (128 ** -0.5), dim=-1)
        out = torch.matmul(weights, v).transpose(1, 2).reshape(b, t, 2048)
        return self.output_proj(out)


class FeedForward(torch.nn.Module):
    def __init__(self):
        super().__init__()
        self.layer1 = torch.nn.Linear(2048, 8192, bias=False)
        self.layer2 = torch.nn.Linear(8192, 2048, bias=False)

    def forward(self, x):
        return self.layer2(torch.nn.functional.gelu(self.layer1(x)))


class AnimaTransformerBlock(torch.nn.Module):
    """One full original Anima DiT transformer block, 3 AdaLN + 2 attention + MLP."""
    def __init__(self):
        super().__init__()
        self.adaln_modulation_self_attn = AdaLayerNormZero(2048)
        self.adaln_modulation_cross_attn = AdaLayerNormZero(2048)
        self.adaln_modulation_mlp = AdaLayerNormZero(2048)
        self.self_attn = Attention()
        self.cross_attn = Attention(cross=True)
        self.mlp = FeedForward()

    def forward(self, x, context, step, temb, cos, sin):
        y, gate = self.adaln_modulation_self_attn(x, step, temb)
        x = x + gate * self.self_attn(y, context, cos, sin)
        y, gate = self.adaln_modulation_cross_attn(x, step, temb)
        x = x + gate * self.cross_attn(y, context, cos, sin)
        y, gate = self.adaln_modulation_mlp(x, step, temb)
        x = x + gate * self.mlp(y)
        return x


def weight_names(handle, index):
    prefix = f"model.diffusion_model.blocks.{index}."
    names = {}
    # Reference Linear "1"/"2" are model state_dict keys in ComfyUI.
    for attr in ("adaln_modulation_self_attn", "adaln_modulation_cross_attn", "adaln_modulation_mlp"):
        for layer, native in ((1, "linear_1"), (2, "linear_2")):
            names[f"{attr}.{native}.weight"] = prefix + f"{attr}.{layer}.weight"
    for attr in ("self_attn", "cross_attn"):
        for proj in ("q_proj", "k_proj", "v_proj", "output_proj"):
            names[f"{attr}.{proj}.weight"] = prefix + f"{attr}.{proj}.weight"
        for norm in ("q_norm", "k_norm"):
            names[f"{attr}.{norm}.weight"] = prefix + f"{attr}.{norm}.weight"
    for layer in ("layer1", "layer2"):
        names[f"mlp.{layer}.weight"] = prefix + f"mlp.{layer}.weight"
    # Anima Turbo v1.1 safetensors use model.diffusion_model.blocks.N directly.
    known = set(handle.keys())
    missing = [original for original in names.values() if original not in known]
    if missing:
        # Fail rather than silently compiling random/zero weights!
        raise ValueError(
            f"Missing {len(missing)} Anima block {index} tensors, first {missing[:5]}. "
            f"Real checkpoint example keys: {[k for k in list(handle.keys()) if '.blocks.0.' in k][:12]}"
        )
    return names


def load_block(handle, index):
    model = AnimaTransformerBlock()
    mapped = weight_names(handle, index)
    weights = {}
    for param, original in mapped.items():
        t = handle.get_tensor(original)
        weights[param] = t.to(torch.float32).contiguous()
    model.load_state_dict(weights, strict=True)
    return model.eval(), mapped


def sample_inputs(tokens, seed=123):
    generator = torch.Generator().manual_seed(seed)
    return (
        torch.randn(1, tokens, 2048, generator=generator) * 0.15,
        torch.randn(1, 512, 1024, generator=generator) * 0.15,
        torch.randn(1, 2048, generator=generator) * 0.1,
        torch.randn(1, 6144, generator=generator) * 0.05,
        torch.cos(torch.randn(tokens, 128, generator=generator) * 0.3),
        torch.sin(torch.randn(tokens, 128, generator=generator) * 0.3),
    )


def read_real_calibration(archive, tokens):
    # Android stores its actual graph-cut input arrays as little-endian FP32.
    shapes = {
        "x": (1, tokens, 2048),
        "context": (1, 512, 1024),
        "step": (1, 2048),
        "temb": (1, 6144),
        "cos": (tokens, 128),
        "sin": (tokens, 128),
    }
    with zipfile.ZipFile(archive) as zipped:
        inputs = []
        for key, shape in shapes.items():
            raw = zipped.read(key + ".f32")
            expected = int(np.prod(shape)) * 4
            if len(raw) != expected:
                raise ValueError(f"{key}: expected {expected} bytes, got {len(raw)}")
            tensor = torch.from_numpy(np.frombuffer(raw, dtype="<f4").copy()).reshape(shape)
            if not torch.isfinite(tensor).all():
                raise ValueError(f"Nonfinite real calibration values in {key}")
            print(f"Real calibration {key}: rms={tensor.square().mean().sqrt().item():.5f}"
                  f" max={tensor.abs().max().item():.5f}", flush=True)
            inputs.append(tensor)
    return tuple(inputs)


def export_block(model, index, tokens, platform, output, calibration=None):
    inputs = sample_inputs(tokens)
    with torch.no_grad():
        print(f"Block {index}: torch.export graph capture at {tokens} latent tokens", flush=True)
        captured = torch.export.export(model, inputs, strict=True).module()
        quantizer = NeuropilotQuantizer()
        quantizer.setup_precision(Precision.A16W4)
        prepared = prepare_pt2e(captured, quantizer)
        if calibration is not None:
            print(f"Block {index}: calibrating INT16 activations from real Anima inputs",
                  flush=True)
            prepared(*calibration)
        else:
            # Synthetic calibration can clip real activations; hardware demo only.
            print("WARNING: synthetic A16W4 calibration; NOT image-quality validated",
                  flush=True)
            for seed in (13, 42, 101):
                prepared(*sample_inputs(tokens, seed))
        quantized = convert_pt2e(prepared, fold_quantize=False)
        exported = torch.export.export(quantized, inputs, strict=True)
        partitioner = NeuropilotPartitioner(
            [CompileSpec("platform-config", platform.encode())]
        )
        edge = to_edge_transform_and_lower(
            exported,
            compile_config=exir.EdgeCompileConfig(_check_ir_validity=False),
            partitioner=[partitioner],
        )
        program = edge.to_executorch(config=exir.ExecutorchBackendConfig())
        data = program.buffer
        if b"NeuropilotBackend" not in data:
            raise RuntimeError(f"block {index} compiled without a real MediaTek delegate")
        output.write_bytes(data)
    return dict(
        block=index, tokens=tokens, bytes=len(data),
        sha256=hashlib.sha256(data).hexdigest(),
        delegate="NeuropilotBackend", precision="A16W4",
        interfaces=["x", "conditioned_text", "step_embedding", "temb", "rope_cos", "rope_sin"],
        outputs=["x_next"],
        caveat="Transformer-block export only; not yet integrated into denoiser runtime.",
    )


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--checkpoint", required=True, type=Path)
    parser.add_argument("--out-dir", default="out/anima-transformer-a16w4", type=Path)
    parser.add_argument("--platform", default="mt6989")
    parser.add_argument("--tokens", type=int, default=576, help="384x384 latent tokens after patchification")
    parser.add_argument("--first", type=int, default=0)
    parser.add_argument("--calibration-zip", type=Path,
                        help="Real phone block-0 input capture ZIP; calibrates A16W4 on actual activations")
    parser.add_argument("--count", type=int, default=28)
    args = parser.parse_args()
    if args.calibration_zip and (args.first != 0 or args.count != 1 or args.tokens != 1024):
        parser.error("For now, real capture calibrates only block 0: --first 0 --count 1 --tokens 1024")
    if args.first < 0 or args.count < 1 or args.first + args.count > 28:
        parser.error("Select blocks in [0,28)")
    args.out_dir.mkdir(parents=True, exist_ok=True)
    manifest = {
        "model": "Anima Turbo v1.1",
        "checkpoint_sha256_verified_by_workflow": True,
        "architecture": "28 diffusion transformer blocks, 6 text adapter blocks",
        "platform": args.platform,
        "precision": "A16W4",
        "latent_tokens": args.tokens,
        "total_transformer_blocks": 28,
        "blocks": [],
        "complete_transformer": False,
        "complete_image_generation": False,
    }
    started = time.monotonic()
    calibration = read_real_calibration(args.calibration_zip, args.tokens) if args.calibration_zip else None
    with safe_open(str(args.checkpoint), framework="pt", device="cpu") as handle:
        for index in range(args.first, args.first + args.count):
            t0 = time.monotonic()
            block, mapped = load_block(handle, index)
            filename = f"anima_block_{index:02d}_{args.tokens}_{args.platform}_a16w4.pte"
            info = export_block(block, index, args.tokens, args.platform,
                                args.out_dir / filename, calibration=calibration)
            info["calibration"] = "real-block0" if calibration is not None else "synthetic"
            info["filename"] = filename
            info["source_parameters"] = len(mapped)
            info["compile_seconds"] = round(time.monotonic() - t0, 1)
            manifest["blocks"].append(info)
            manifest["complete_transformer"] = len(manifest["blocks"]) == 28 and args.first == 0
            (args.out_dir / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
            print(f"Compiled block {index} in {info['compile_seconds']}s ({info['bytes']:,} bytes)", flush=True)
            del block
            gc.collect()
    print(f"Finished {len(manifest['blocks'])}/28 blocks in {time.monotonic()-started:.1f}s", flush=True)
    if args.count == 28 and not manifest["complete_transformer"]:
        raise RuntimeError("Incomplete 28-block A16W4 model")


if __name__ == "__main__":
    main()
