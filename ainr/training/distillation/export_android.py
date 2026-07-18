#!/usr/bin/env python3
"""Export the selected Mobile U-Net checkpoint to a fixed-shape LiteRT model."""

from __future__ import annotations

import argparse
from pathlib import Path

import ai_edge_torch
import numpy as np
import torch
from torch import nn

try:
    from ai_edge_litert.interpreter import Interpreter
except ImportError:
    from tensorflow.lite.python.interpreter import Interpreter

from student import MobileUNetStudent


def main() -> None:
    root = Path(__file__).resolve().parent
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--checkpoint", type=Path,
        default=root / "runs/mobile_unet_w24_detail_plateau/best.pt")
    parser.add_argument(
        "--output", type=Path,
        default=root.parents[1] / "app/src/main/assets/models/mobile_unet_w24_sharp.tflite")
    args = parser.parse_args()

    checkpoint = torch.load(args.checkpoint, map_location="cpu", weights_only=False)
    config = dict(checkpoint["config"]["student"])
    config["encoder_blocks"] = tuple(config["encoder_blocks"])
    model = MobileUNetStudent(**config, clamp_output=False).eval()
    model.load_state_dict(checkpoint["model"])
    for module in model.modules():
        if isinstance(module, nn.ReLU):
            module.inplace = False

    generator = torch.Generator().manual_seed(1337)
    sample = torch.rand(1, 3, 192, 192, generator=generator)
    converted = ai_edge_torch.convert(model, (sample,))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    converted.export(str(args.output))

    interpreter = Interpreter(model_path=str(args.output))
    interpreter.allocate_tensors()
    input_detail = interpreter.get_input_details()[0]
    output_detail = interpreter.get_output_details()[0]
    value = np.random.default_rng(1337).random((1, 3, 192, 192), dtype=np.float32)
    with torch.inference_mode():
        expected = model(torch.from_numpy(value)).numpy()
    interpreter.set_tensor(input_detail["index"], value)
    interpreter.invoke()
    actual = interpreter.get_tensor(output_detail["index"])
    maximum_error = float(np.max(np.abs(expected - actual)))
    if maximum_error > 1e-5:
        args.output.unlink(missing_ok=True)
        raise RuntimeError(f"LiteRT validation failed: maximum error {maximum_error}")
    print(f"Exported {args.output} ({args.output.stat().st_size} bytes, max error {maximum_error:.3g})")


if __name__ == "__main__":
    main()
