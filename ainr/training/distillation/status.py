#!/usr/bin/env python3
"""Print progress from checkpoints written by the active training run."""

from __future__ import annotations

import argparse
import subprocess
import time
from pathlib import Path

import torch


def load(path: Path) -> dict | None:
    if not path.exists():
        return None
    return torch.load(path, map_location="cpu", weights_only=False)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--run",
        type=Path,
        default=Path(__file__).parent / "runs" / "student_w32_b8",
    )
    args = parser.parse_args()
    latest = load(args.run / "latest.pt")
    best = load(args.run / "best.pt")
    if latest is None:
        raise SystemExit("No completed training epoch yet.")
    total = int(latest["config"]["training"]["epochs"])
    epoch = int(latest["epoch"])
    age = int(time.time() - (args.run / "latest.pt").stat().st_mtime)
    print(f"Completed epoch: {epoch}/{total} ({100 * epoch / total:.1f}%)")
    print(f"Latest validation L1: {latest['validation_l1']:.6f}")
    if best is not None:
        print(f"Best validation L1:   {best['validation_l1']:.6f} (epoch {best['epoch']})")
    print(f"Checkpoint updated: {age}s ago")
    try:
        gpu = subprocess.run(
            [
                "nvidia-smi",
                "--query-gpu=utilization.gpu,memory.used,temperature.gpu",
                "--format=csv,noheader,nounits",
            ],
            check=True,
            capture_output=True,
            text=True,
        ).stdout.strip()
        utilization, memory, temperature = (value.strip() for value in gpu.split(","))
        print(f"GPU: {utilization}% utilization, {memory} MiB, {temperature} C")
    except (OSError, subprocess.SubprocessError, ValueError):
        pass


if __name__ == "__main__":
    main()
