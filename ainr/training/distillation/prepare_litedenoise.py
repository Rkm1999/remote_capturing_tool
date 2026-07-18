#!/usr/bin/env python3
"""Build 192 px student patches from SCUNet targets inferred with larger context."""

from __future__ import annotations

import argparse
import hashlib
import json
import random
from pathlib import Path

import numpy as np
import torch
import yaml
from PIL import Image, ImageOps
from tqdm import tqdm

from prepare import load_teacher, split_for


def crop_positions(width: int, height: int, tile: int, count: int, seed: int) -> list[tuple[int, int]]:
    rng = random.Random(seed)
    return [(rng.randint(0, width - tile), rng.randint(0, height - tile)) for _ in range(count)]


def contextual_crop(image: Image.Image, left: int, top: int, tile: int, context: int) -> np.ndarray:
    margin = (context - tile) // 2
    array = np.asarray(image, dtype=np.uint8)
    padded = np.pad(array, ((margin, margin), (margin, margin), (0, 0)), mode="reflect")
    return padded[top:top + context, left:left + context].astype(np.float32) / 255.0


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--config", type=Path, default=Path(__file__).with_name("config_litedenoise.yaml"))
    parser.add_argument("--limit", type=int)
    args = parser.parse_args()
    config_path = args.config.resolve()
    config = yaml.safe_load(config_path.read_text(encoding="utf-8"))
    base = config_path.parent
    data = config["data"]
    source_root = (base / data["source_root"]).resolve()
    records = json.loads((base / data["extended_manifest"]).read_text(encoding="utf-8"))
    if args.limit:
        records = records[:args.limit]
    cache = (base / data["cache_root"]).resolve()
    tile, context = int(config["tile_size"]), int(config["teacher_context"])
    if context < tile or (context - tile) % 2 or context % 64:
        raise ValueError("teacher_context must be an even, >= tile_size multiple of 64")
    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    teacher_config = config["teacher"]
    teacher = load_teacher(
        (base / teacher_config["scunet_repo"]).resolve(),
        (base / teacher_config["checkpoint"]).resolve(), device,
    )
    output: list[dict] = []
    margin = (context - tile) // 2
    for record in tqdm(records, desc="Contextual teacher targets"):
        noisy_path, clean_path = source_root / record["input"], source_root / record["clean"]
        with Image.open(noisy_path) as raw_noisy, Image.open(clean_path) as raw_clean:
            noisy = ImageOps.exif_transpose(raw_noisy).convert("RGB")
            clean = ImageOps.exif_transpose(raw_clean).convert("RGB")
            width, height = min(noisy.width, clean.width), min(noisy.height, clean.height)
            if width < tile or height < tile:
                continue
            count = int(data["patches_per_pair"][record["dataset"]])
            key = hashlib.sha256(f"{record['dataset']}:{record['scene']}:{record['input']}".encode()).hexdigest()[:16]
            crops = crop_positions(width, height, tile, count, config["seed"] ^ int(key[:8], 16))
            contexts = [contextual_crop(noisy, x, y, tile, context) for x, y in crops]
            inputs = [patch[margin:margin + tile, margin:margin + tile] for patch in contexts]
            cleans = [np.asarray(clean.crop((x, y, x + tile, y + tile)), dtype=np.float32) / 255.0 for x, y in crops]
        targets: list[np.ndarray] = []
        batch_size = int(teacher_config["batch_size"])
        with torch.inference_mode():
            for offset in range(0, count, batch_size):
                batch = torch.from_numpy(np.stack(contexts[offset:offset + batch_size])).permute(0, 3, 1, 2).to(device)
                prediction = teacher(batch).clamp(0, 1)[:, :, margin:margin + tile, margin:margin + tile]
                targets.extend(prediction.permute(0, 2, 3, 1).cpu().numpy())
        split = split_for(record["dataset"] + ":" + record["scene"], config["validation_fraction"])
        destination = cache / split / record["dataset"]
        destination.mkdir(parents=True, exist_ok=True)
        for index, (source, target, clean_patch) in enumerate(zip(inputs, targets, cleans, strict=True)):
            stem = f"{key}_{index:02d}"
            item = {"dataset": record["dataset"], "scene": record["scene"], "split": split}
            for name, value in (("input", source), ("teacher", target), ("clean", clean_patch)):
                path = destination / f"{stem}_{name}.npy"
                np.save(path, value.astype(np.float16))
                item[name] = str(path.relative_to(cache))
            output.append(item)
    cache.mkdir(parents=True, exist_ok=True)
    (cache / "manifest.json").write_text(json.dumps(output, indent=2), encoding="utf-8")
    print(json.dumps({split: sum(x["split"] == split for x in output) for split in ("train", "validation")}, indent=2))


if __name__ == "__main__":
    main()
