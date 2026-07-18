#!/usr/bin/env python3
"""Index paired extended datasets without generating teacher targets."""

from __future__ import annotations

import argparse
import json
import re
from collections import Counter
from pathlib import Path

from PIL import Image

IMAGE_SUFFIXES = {".jpg", ".jpeg", ".png", ".tif", ".tiff"}
ISO_PATTERN = re.compile(r"^(NIND_.+)_ISO([^.]*)$", re.IGNORECASE)


def images(root: Path) -> list[Path]:
    if not root.exists():
        return []
    return sorted(path for path in root.rglob("*") if path.suffix.lower() in IMAGE_SUFFIXES)


def relative(path: Path, root: Path) -> str:
    return str(path.resolve().relative_to(root.resolve()))


def valid_pair(noisy: Path, clean: Path) -> bool:
    try:
        with Image.open(noisy) as first, Image.open(clean) as second:
            if first.size != second.size or min(*first.size, *second.size) < 192:
                return False
            first.verify()
            second.verify()
        return True
    except (OSError, SyntaxError):
        return False


def sidd_pairs(root: Path, base: Path) -> list[dict]:
    noisy: dict[str, Path] = {}
    clean: dict[str, Path] = {}
    for path in images(root):
        name = path.name.upper()
        key = re.sub(r"_(NOISY|GT)_SRGB", "", path.stem.upper())
        if "_NOISY_SRGB" in name:
            noisy[key] = path
        elif "_GT_SRGB" in name:
            clean[key] = path
    records = []
    for key in sorted(noisy.keys() & clean.keys()):
        if not valid_pair(noisy[key], clean[key]):
            continue
        scene = noisy[key].parent.name.split("_")[0]
        records.append({"dataset": "sidd", "scene": scene, "input": relative(noisy[key], base),
                        "clean": relative(clean[key], base)})
    return records


def nind_pairs(root: Path, base: Path) -> list[dict]:
    grouped: dict[str, list[tuple[str, Path]]] = {}
    for path in images(root):
        match = ISO_PATTERN.match(path.stem)
        if match:
            grouped.setdefault(match.group(1).lower(), []).append((match.group(2), path))
    records = []
    for scene, variants in sorted(grouped.items()):
        def rank(item: tuple[str, Path]) -> tuple[int, int]:
            iso = item[0].upper()
            numeric = re.match(r"(?:H)?(\d+)", iso)
            value = int(numeric.group(1)) if numeric else 999999
            return (1 if iso.startswith("H") else 0, value)
        variants.sort(key=rank)
        clean = variants[0][1]
        for _, noisy in variants[1:]:
            if not valid_pair(noisy, clean):
                continue
            records.append({"dataset": "nind", "scene": scene, "input": relative(noisy, base),
                            "clean": relative(clean, base)})
    return records


def midd_pairs(root: Path, base: Path) -> list[dict]:
    records = []
    for sensor in sorted(path for path in root.iterdir() if path.is_dir()) if root.exists() else []:
        noisy_files = [path for path in images(sensor) if any(token in path.as_posix().lower()
                                                               for token in ("original", "noisy"))]
        clean_files = [path for path in images(sensor) if any(token in path.as_posix().lower()
                                                               for token in ("denoised", "clean", "ground"))]
        clean_by_stem = {re.sub(r"(original|noisy|denoised|clean|ground.?truth)", "", path.stem.lower()): path
                         for path in clean_files}
        for noisy in noisy_files:
            key = re.sub(r"(original|noisy|denoised|clean|ground.?truth)", "", noisy.stem.lower())
            clean = clean_by_stem.get(key)
            if clean and valid_pair(noisy, clean):
                scene = f"{sensor.name}/{key}"
                records.append({"dataset": "midd", "scene": scene, "input": relative(noisy, base),
                                "clean": relative(clean, base)})
    return records


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=Path(__file__).parent / "data" / "sources")
    parser.add_argument("--output", type=Path,
                        default=Path(__file__).parent / "data" / "extended_manifest.json")
    args = parser.parse_args()
    root = args.root.resolve()
    records = (
        sidd_pairs(root / "sidd-medium-srgb", root)
        + nind_pairs(root / "nind", root)
        + midd_pairs(root / "midd", root)
    )
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(records, indent=2), encoding="utf-8")
    counts = Counter(item["dataset"] for item in records)
    scenes = Counter((item["dataset"], item["scene"]) for item in records)
    print(f"Indexed {len(records)} pairs: {dict(counts)}")
    print(f"Unique dataset/scene groups: {len(scenes)}")


if __name__ == "__main__":
    main()
