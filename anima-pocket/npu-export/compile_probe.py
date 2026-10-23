#!/usr/bin/env python3
"""Compile ONE real Anima FP16 projection to MediaTek A16W4 ExecuTorch.

This is a hardware-targeted, genuine INT4-weight NPU compiler smoke test.
It is NOT a compiled Anima transformer or a usable image-generation model.
"""
import argparse
import hashlib
import json
from pathlib import Path
import sys

import torch
from safetensors import safe_open
from torchao.quantization.pt2e.quantize_pt2e import convert_pt2e, prepare_pt2e
from executorch import exir
from executorch.backends.mediatek import NeuropilotPartitioner, NeuropilotQuantizer, Precision
from executorch.exir.backend.backend_details import CompileSpec
from executorch.exir.program._program import to_edge_transform_and_lower


class Projection(torch.nn.Module):
    def __init__(self, weight: torch.Tensor):
        super().__init__()
        rows, columns = weight.shape
        self.linear = torch.nn.Linear(columns, rows, bias=False)
        with torch.no_grad():
            self.linear.weight.copy_(weight.to(torch.float32))

    def forward(self, x):
        return self.linear(x)


def choose_anima_tensor(checkpoint: Path):
    with safe_open(str(checkpoint), framework="pt", device="cpu") as handle:
        options = []
        for name in handle.keys():
            if not name.endswith(".weight"):
                continue
            shape = handle.get_slice(name).get_shape()
            if (len(shape) != 2 or min(shape) < 512 or max(shape) > 8192
                    or shape[0] % 32 or shape[1] % 32
                    or shape[0] * shape[1] > 12_000_000):
                continue
            # First actual transformer block, preferably self-attention.
            is_first = ".blocks.0." in name or ".transformer_blocks.0." in name
            is_attention = "attn" in name or "attention" in name
            options.append((int(is_first), int(is_attention), name, shape))
        if not options:
            raise RuntimeError("No compatible 2D Anima projection found in safetensors")
        options.sort(key=lambda item: (-item[0], -item[1], item[2]))
        _, _, name, shape = options[0]
        weight = handle.get_tensor(name).to(torch.float32).contiguous()
    print(f"Selected REAL Anima parameter: {name} shape={shape}", flush=True)
    return name, weight


def export(model, output: Path, device: str):
    torch.manual_seed(42)
    k = model.linear.in_features
    example = (torch.randn(16, k, dtype=torch.float32) * 0.3,)
    fp32_reference = model(*example)
    model.eval()

    print("torch.export capture...", flush=True)
    captured = torch.export.export(model, example, strict=True).module()
    quantizer = NeuropilotQuantizer()
    quantizer.setup_precision(Precision.A16W4)
    calibrated = prepare_pt2e(captured, quantizer)
    print("Calibrating A16W4: three small projection batches...", flush=True)
    with torch.no_grad():
        for factor in (0.2, 0.5, 1.0):
            calibrated(torch.randn(16, k) * factor)
    quant_model = convert_pt2e(calibrated, fold_quantize=False)
    with torch.no_grad():
        quant_result = quant_model(*example)
    rmse = ((quant_result - fp32_reference).square().mean().sqrt() /
            fp32_reference.square().mean().sqrt().clamp_min(1e-12)).item()
    print(f"CPU quantization normalized RMSE = {rmse:.6f}", flush=True)

    aten = torch.export.export(quant_model, example, strict=True)
    partitioner = NeuropilotPartitioner([CompileSpec("platform-config", device.encode())])
    print(f"Compiling A16W4 for MediaTek {device} (strict delegate required)...", flush=True)
    edge = to_edge_transform_and_lower(
        aten,
        compile_config=exir.EdgeCompileConfig(_check_ir_validity=False),
        partitioner=[partitioner],
    )
    program = edge.to_executorch(config=exir.ExecutorchBackendConfig())
    data = program.buffer
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_bytes(data)
    if b"NeuropilotBackend" not in data:
        output.unlink(missing_ok=True)
        raise RuntimeError("Export did not contain NeuropilotBackend delegate")
    print(f"Success: {output} ({len(data):,} bytes, NeuropilotBackend present)")
    return {
        "device": device, "precision": "A16W4", "delegated": True,
        "quant_cpu_normalized_rmse": rmse,
        "output_bytes": len(data),
        "output_sha256": hashlib.sha256(data).hexdigest(),
        "note": "One Anima projection ONLY; whole Anima diffusion does not run on NPU.",
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--checkpoint", type=Path)
    parser.add_argument("--synthetic", action="store_true")
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--device", default="mt6989")
    args = parser.parse_args()
    if args.synthetic:
        torch.manual_seed(29)
        name, weight = "synthetic.preflight.weight", torch.randn(512, 512) * 0.02
    else:
        if not args.checkpoint or not args.checkpoint.is_file():
            parser.error("--checkpoint is required for real Anima projection")
        name, weight = choose_anima_tensor(args.checkpoint)
    result = export(Projection(weight), args.out, args.device)
    result["source_parameter"] = name
    result["source_shape"] = list(weight.shape)
    report = args.out.parent / (args.out.stem + ".json")
    report.write_text(json.dumps(result, indent=2) + "\n")
    print(f"Report: {report}")


if __name__ == "__main__":
    main()
