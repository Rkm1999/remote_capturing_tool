#!/usr/bin/env python3
"""Cache aligned clean and SCUNet-teacher targets for extended datasets."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path

import numpy as np
import torch
from PIL import Image
from tqdm import tqdm

from prepare import crop_positions, load_config, load_teacher, split_for


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--config", type=Path, default=Path(__file__).with_name("config.yaml"))
    parser.add_argument("--limit", type=int)
    args = parser.parse_args()
    config_path = args.config.resolve()
    config = load_config(config_path)
    base = config_path.parent
    data = config["data"]
    source_root = (base / "data" / "sources").resolve()
    manifest_path = (base / data["extended_manifest"]).resolve()
    records = json.loads(manifest_path.read_text(encoding="utf-8"))
    if args.limit:
        records = records[: args.limit]
    cache = (base / data["extended_cache_root"]).resolve()
    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    teacher_config = config["teacher"]
    teacher = load_teacher(
        (base / teacher_config["scunet_repo"]).resolve(),
        (base / teacher_config["checkpoint"]).resolve(),
        device,
    )
    tile = int(config["tile_size"])
    output_manifest: list[dict] = []
    for record in tqdm(records, desc="Extended pairs"):
        noisy_path = source_root / record["input"]
        clean_path = source_root / record["clean"]
        with Image.open(noisy_path) as noisy_image, Image.open(clean_path) as clean_image:
            noisy_image = noisy_image.convert("RGB")
            clean_image = clean_image.convert("RGB")
            count = int(data["extended_patches_per_pair"][record["dataset"]])
            key = hashlib.sha256(
                f"{record['dataset']}:{record['scene']}:{record['input']}".encode("utf-8")
            ).hexdigest()[:16]
            positions = crop_positions(
                noisy_image.width, noisy_image.height, tile, count,
                config["seed"] ^ int(key[:8], 16),
            )
            inputs = [
                np.asarray(noisy_image.crop((x, y, x + tile, y + tile)), dtype=np.float32) / 255.0
                for x, y in positions
            ]
            clean = [
                np.asarray(clean_image.crop((x, y, x + tile, y + tile)), dtype=np.float32) / 255.0
                for x, y in positions
            ]
        teacher_targets: list[np.ndarray] = []
        batch_size = int(teacher_config["batch_size"])
        with torch.inference_mode():
            for offset in range(0, len(inputs), batch_size):
                batch = torch.from_numpy(np.stack(inputs[offset:offset + batch_size]))
                batch = batch.permute(0, 3, 1, 2).to(device)
                result = teacher(batch).clamp(0, 1).permute(0, 2, 3, 1).cpu().numpy()
                teacher_targets.extend(result)
        split = split_for(record["dataset"] + ":" + record["scene"], config["validation_fraction"])
        destination = cache / split / record["dataset"]
        destination.mkdir(parents=True, exist_ok=True)
        for index, (input_patch, teacher_patch, clean_patch) in enumerate(
            zip(inputs, teacher_targets, clean, strict=True)
        ):
            stem = f"{key}_{index:02d}"
            paths = {
                "input": destination / f"{stem}_input.npy",
                "teacher": destination / f"{stem}_teacher.npy",
                "clean": destination / f"{stem}_clean.npy",
            }
            np.save(paths["input"], input_patch.astype(np.float16))
            np.save(paths["teacher"], teacher_patch.astype(np.float16))
            np.save(paths["clean"], clean_patch.astype(np.float16))
            output_manifest.append({
                **{name: str(path.relative_to(cache)) for name, path in paths.items()},
                "dataset": record["dataset"], "scene": record["scene"], "split": split,
            })
    cache.mkdir(parents=True, exist_ok=True)
    (cache / "manifest.json").write_text(json.dumps(output_manifest, indent=2), encoding="utf-8")
    counts = {name: sum(item["split"] == name for item in output_manifest)
              for name in ("train", "validation")}
    print(f"Cached {counts['train']} training and {counts['validation']} validation extended patches.")


if __name__ == "__main__":
    main()
