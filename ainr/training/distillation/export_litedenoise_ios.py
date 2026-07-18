#!/usr/bin/env python3
"""Export the fixed 192 px LiteDenoise student as an FP16 Core ML program."""

from __future__ import annotations

import argparse
import shutil
from pathlib import Path

import coremltools as ct
import numpy as np
import torch

from student_litedenoise import LiteDenoiseStudent


def main() -> None:
    root = Path(__file__).resolve().parent
    parser = argparse.ArgumentParser()
    parser.add_argument("--checkpoint", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    checkpoint = torch.load(args.checkpoint, map_location="cpu", weights_only=False)
    model = LiteDenoiseStudent(**checkpoint["config"]["student"], clamp_output=False).eval()
    model.load_state_dict(checkpoint["model"], strict=True)
    sample = torch.rand(1, 3, 192, 192, generator=torch.Generator().manual_seed(1337))
    traced = torch.jit.trace(model, sample)
    converted = ct.convert(
        traced,
        convert_to="mlprogram",
        minimum_deployment_target=ct.target.iOS17,
        compute_precision=ct.precision.FLOAT16,
        inputs=[ct.TensorType(name="input", shape=sample.shape, dtype=np.float32)],
        outputs=[ct.TensorType(name="output", dtype=np.float32)],
    )
    if args.output.exists():
        shutil.rmtree(args.output)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    converted.save(str(args.output))
    print(f"Exported {args.output} from epoch {checkpoint['epoch']}")


if __name__ == "__main__":
    main()
