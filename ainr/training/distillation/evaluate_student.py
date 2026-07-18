#!/usr/bin/env python3
"""Evaluate a distilled checkpoint against teacher and clean-reference caches."""

from __future__ import annotations

import argparse
import json
import math
import time
from collections import defaultdict
from pathlib import Path

import numpy as np
import torch
import torch.nn.functional as functional
from PIL import Image, ImageDraw

from student import DistilledDenoiser, MobileUNetStudent
from student_litedenoise import LiteDenoiseStudent


def metrics(prediction: torch.Tensor, target: torch.Tensor) -> tuple[float, float, float, int]:
    difference = prediction - target
    absolute = difference.abs().sum().item()
    squared = difference.square().sum().item()
    pred_x = prediction[:, :, :, 1:] - prediction[:, :, :, :-1]
    pred_y = prediction[:, :, 1:, :] - prediction[:, :, :-1, :]
    target_x = target[:, :, :, 1:] - target[:, :, :, :-1]
    target_y = target[:, :, 1:, :] - target[:, :, :-1, :]
    edge = (pred_x - target_x).abs().sum().item() + (pred_y - target_y).abs().sum().item()
    edge_count = pred_x.numel() + pred_y.numel()
    return absolute, squared, edge / edge_count, difference.numel()


def summarize(total: list[float]) -> dict[str, float]:
    absolute, squared, edge_sum, count, batches = total
    mse = squared / count
    return {
        "l1": absolute / count,
        "psnr": -10.0 * math.log10(max(mse, 1e-12)),
        "edge_l1": edge_sum / batches,
    }


def add(total: list[float], values: tuple[float, float, float, int]) -> None:
    total[0] += values[0]
    total[1] += values[1]
    total[2] += values[2]
    total[3] += values[3]
    total[4] += 1


def tensor(path: Path) -> torch.Tensor:
    return torch.from_numpy(np.load(path).astype(np.float32)).permute(2, 0, 1)


def tile_image(value: torch.Tensor) -> Image.Image:
    pixels = value.detach().clamp(0, 1).permute(1, 2, 0).cpu().numpy()
    return Image.fromarray(np.rint(pixels * 255).astype(np.uint8), "RGB")


def main() -> None:
    parser = argparse.ArgumentParser()
    root = Path(__file__).parent
    parser.add_argument("--checkpoint", type=Path, default=root / "runs/student_w32_b8/best.pt")
    parser.add_argument("--output", type=Path, default=root / "runs/student_w32_b8/evaluation")
    parser.add_argument("--batch-size", type=int, default=32)
    args = parser.parse_args()
    checkpoint = torch.load(args.checkpoint, map_location="cpu", weights_only=False)
    student_config = checkpoint["config"]["student"]
    if "base_width" in student_config and "encoder_blocks" not in student_config:
        model = LiteDenoiseStudent(**student_config, clamp_output=False)
    elif "base_width" in student_config:
        student_config = {**student_config, "encoder_blocks": tuple(student_config["encoder_blocks"])}
        model = MobileUNetStudent(**student_config)
    else:
        model = DistilledDenoiser(**student_config)
    model.load_state_dict(checkpoint["model"])
    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    model.eval().to(device)
    extended_root = root / "data/extended_cache"
    records = json.loads((extended_root / "manifest.json").read_text(encoding="utf-8"))
    records = [record for record in records if record["split"] == "validation"]
    totals: dict[tuple[str, str], list[float]] = defaultdict(lambda: [0.0] * 5)
    examples = []
    with torch.inference_mode():
        for offset in range(0, len(records), args.batch_size):
            batch_records = records[offset:offset + args.batch_size]
            noisy = torch.stack([tensor(extended_root / item["input"]) for item in batch_records]).to(device)
            teacher = torch.stack([tensor(extended_root / item["teacher"]) for item in batch_records]).to(device)
            clean = torch.stack([tensor(extended_root / item["clean"]) for item in batch_records]).to(device)
            prediction = model(noisy)
            for index, item in enumerate(batch_records):
                dataset = item["dataset"]
                add(totals[(dataset, "student_teacher")], metrics(prediction[index:index+1], teacher[index:index+1]))
                add(totals[(dataset, "input_teacher")], metrics(noisy[index:index+1], teacher[index:index+1]))
                add(totals[(dataset, "student_clean")], metrics(prediction[index:index+1], clean[index:index+1]))
                add(totals[(dataset, "teacher_clean")], metrics(teacher[index:index+1], clean[index:index+1]))
                add(totals[(dataset, "input_clean")], metrics(noisy[index:index+1], clean[index:index+1]))
                if len([entry for entry in examples if entry[0] == dataset]) < 3:
                    examples.append((dataset, noisy[index].cpu(), teacher[index].cpu(), prediction[index].cpu(), clean[index].cpu()))

    args.output.mkdir(parents=True, exist_ok=True)
    report = {f"{dataset}/{comparison}": summarize(values)
              for (dataset, comparison), values in sorted(totals.items())}
    (args.output / "metrics.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    columns = ["Input", "Teacher", "Student", "Clean"]
    canvas = Image.new("RGB", (192 * 4, (192 + 24) * len(examples)), "white")
    draw = ImageDraw.Draw(canvas)
    for row, (dataset, noisy, teacher, prediction, clean) in enumerate(examples):
        top = row * (192 + 24)
        for column, value in enumerate((noisy, teacher, prediction, clean)):
            canvas.paste(tile_image(value), (column * 192, top + 24))
            draw.text((column * 192 + 4, top + 4), f"{dataset}: {columns[column]}", fill="black")
    canvas.save(args.output / "comparison.png")

    sample = torch.rand(args.batch_size, 3, 192, 192, device=device)
    with torch.inference_mode():
        for _ in range(20):
            model(sample)
        if device.type == "cuda":
            torch.cuda.synchronize()
        start = time.perf_counter()
        for _ in range(100):
            model(sample)
        if device.type == "cuda":
            torch.cuda.synchronize()
    per_tile_ms = (time.perf_counter() - start) * 1000 / (100 * args.batch_size)
    parameters = sum(value.numel() for value in model.parameters())
    result = {"parameters": parameters, "cuda_batch": args.batch_size, "cuda_ms_per_tile": per_tile_ms}
    (args.output / "runtime.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
    print(json.dumps({"metrics": report, "runtime": result}, indent=2))


if __name__ == "__main__":
    main()
