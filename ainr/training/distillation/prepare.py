#!/usr/bin/env python3
"""Create deterministic 192 px input/teacher-target patch pairs."""

from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import random
import sys
from pathlib import Path

import numpy as np
import torch
import yaml
from PIL import Image, ImageOps
from tqdm import tqdm

IMAGE_SUFFIXES = {".jpg", ".jpeg", ".png", ".tif", ".tiff", ".bmp"}


def load_config(path: Path) -> dict:
    with path.open("r", encoding="utf-8") as handle:
        return yaml.safe_load(handle)


def image_files(root: Path) -> list[Path]:
    if not root.exists():
        return []
    return sorted(
        path for path in root.rglob("*")
        if path.is_file() and path.suffix.lower() in IMAGE_SUFFIXES
    )


def is_noisy_polyu(path: Path) -> bool:
    return path.stem.lower().endswith("real")


def is_scunet_input(path: Path) -> bool:
    name = path.name.lower()
    return not any(token in name for token in ("mean", "clean", "gt", "result", "scunet"))


def discover(config: dict, base: Path) -> list[dict]:
    data = config["data"]
    records: list[dict] = []
    polyu = (base / data["polyu_root"]).resolve()
    # The packaged 512 px crops are derived from these same scenes. Using both
    # would duplicate content and risk scene leakage across the split.
    original_polyu = polyu / "OriginalImages"
    if original_polyu.exists():
        polyu = original_polyu
    for path in image_files(polyu):
        if is_noisy_polyu(path):
            records.append({"path": path, "dataset": "polyu", "group": path.stem[:-4]})

    scunet = (base / data["scunet_testsets_root"]).resolve()
    for path in image_files(scunet):
        if is_scunet_input(path):
            records.append({"path": path, "dataset": "scunet", "group": path.parent.name + "/" + path.stem})

    seen: set[Path] = set()
    for value in data["iso_roots"]:
        root = (base / value).resolve()
        for path in image_files(root):
            if path in seen or any(part in {".build", "build"} for part in path.parts):
                continue
            seen.add(path)
            name = path.name.lower()
            if "iso" in name and not any(token in name for token in ("comparison", "-vs-", "gpu", "npu", "dual")):
                records.append({"path": path, "dataset": "iso", "group": path.stem})
    return records


def split_for(group: str, validation_fraction: float) -> str:
    value = int(hashlib.sha256(group.encode("utf-8")).hexdigest()[:8], 16) / 0xFFFFFFFF
    return "validation" if value < validation_fraction else "train"


def load_teacher(repo: Path, checkpoint: Path, device: torch.device) -> torch.nn.Module:
    module_path = repo / "models" / "network_scunet.py"
    spec = importlib.util.spec_from_file_location("network_scunet", module_path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"Cannot load SCUNet architecture from {module_path}")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    model = module.SCUNet(in_nc=3, config=[4, 4, 4, 4, 4, 4, 4], dim=64)
    state = torch.load(checkpoint, map_location="cpu", weights_only=True)
    model.load_state_dict(state, strict=True)
    return model.eval().to(device)


def crop_positions(width: int, height: int, tile: int, count: int, seed: int) -> list[tuple[int, int]]:
    rng = random.Random(seed)
    return [
        (rng.randint(0, width - tile), rng.randint(0, height - tile))
        for _ in range(count)
    ]


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--config", type=Path, default=Path(__file__).with_name("config.yaml"))
    parser.add_argument("--limit", type=int, help="Limit source images for a smoke test")
    args = parser.parse_args()
    config_path = args.config.resolve()
    config = load_config(config_path)
    base = config_path.parent
    records = discover(config, base)
    if args.limit:
        records = records[: args.limit]
    if not records:
        raise SystemExit("No source images found. Run download_data.sh and add ISO originals.")

    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    teacher_config = config["teacher"]
    teacher = load_teacher(
        (base / teacher_config["scunet_repo"]).resolve(),
        (base / teacher_config["checkpoint"]).resolve(),
        device,
    )
    tile = int(config["tile_size"])
    cache = (base / config["data"]["cache_root"]).resolve()
    manifest: list[dict] = []

    for record in tqdm(records, desc="Sources"):
        split = split_for(record["dataset"] + ":" + record["group"], config["validation_fraction"])
        count_key = "validation_patches_per_image" if split == "validation" else "patches_per_image"
        count = int(config["data"][count_key])
        try:
            with Image.open(record["path"]) as raw:
                image = ImageOps.exif_transpose(raw).convert("RGB")
        except OSError:
            continue
        if image.width < tile or image.height < tile:
            continue
        source_key = hashlib.sha256(str(record["path"]).encode("utf-8")).hexdigest()[:16]
        positions = crop_positions(image.width, image.height, tile, count, config["seed"] ^ int(source_key[:8], 16))
        inputs: list[np.ndarray] = []
        for left, top in positions:
            patch = np.asarray(image.crop((left, top, left + tile, top + tile)), dtype=np.float32) / 255.0
            inputs.append(patch)
        batch_size = int(teacher_config["batch_size"])
        targets: list[np.ndarray] = []
        with torch.inference_mode():
            for offset in range(0, len(inputs), batch_size):
                batch = torch.from_numpy(np.stack(inputs[offset:offset + batch_size])).permute(0, 3, 1, 2).to(device)
                prediction = teacher(batch).clamp(0, 1).permute(0, 2, 3, 1).cpu().numpy()
                targets.extend(prediction)
        destination = cache / split / record["dataset"]
        destination.mkdir(parents=True, exist_ok=True)
        for index, (input_patch, target_patch) in enumerate(zip(inputs, targets, strict=True)):
            stem = f"{source_key}_{index:03d}"
            input_path = destination / f"{stem}_input.npy"
            target_path = destination / f"{stem}_target.npy"
            np.save(input_path, input_patch.astype(np.float16))
            np.save(target_path, target_patch.astype(np.float16))
            manifest.append({
                "input": str(input_path.relative_to(cache)),
                "target": str(target_path.relative_to(cache)),
                "split": split,
                "dataset": record["dataset"],
                "source": str(record["path"]),
            })
    cache.mkdir(parents=True, exist_ok=True)
    with (cache / "manifest.json").open("w", encoding="utf-8") as handle:
        json.dump(manifest, handle, indent=2)
    counts = {split: sum(item["split"] == split for item in manifest) for split in ("train", "validation")}
    print(f"Cached {counts['train']} training and {counts['validation']} validation pairs on {device}.")


if __name__ == "__main__":
    main()
