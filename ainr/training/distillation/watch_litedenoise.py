#!/usr/bin/env python3
"""Display live LiteDenoise training metrics and ETA."""

from __future__ import annotations

import argparse
import json
import os
import time
from datetime import datetime, timedelta
from pathlib import Path


def duration(seconds: float) -> str:
    return str(timedelta(seconds=max(0, round(seconds))))


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--run", type=Path, default=Path(__file__).with_name("runs") / "litedenoise_high_noise_v2")
    parser.add_argument("--epochs", type=int, default=240)
    parser.add_argument("--warmup-epochs", type=int, default=0)
    parser.add_argument("--interval", type=float, default=5.0)
    parser.add_argument("--once", action="store_true")
    args = parser.parse_args()
    started = args.run.stat().st_mtime if args.run.exists() else time.time()
    while True:
        history_path = args.run / "history.json"
        try:
            history = json.loads(history_path.read_text(encoding="utf-8"))
        except (FileNotFoundError, json.JSONDecodeError):
            history = []
        now = time.time()
        if history:
            current = history[-1]
            warmup = [item for item in history if item["epoch"] <= args.warmup_epochs]
            distilled = [item for item in history if item["epoch"] > args.warmup_epochs]
            best_clean = max(warmup or history, key=lambda item: item["clean_psnr"])
            best_teacher = max(distilled or history, key=lambda item: item["teacher_psnr"])
            completed = int(current["epoch"])
            elapsed = now - started
            # A resumed run changes the run directory timestamp. Use the measured
            # workstation rate instead of presenting a misleadingly short ETA.
            per_epoch = 26.0 if completed > 1 and elapsed / completed < 10.0 else elapsed / completed
            eta = per_epoch * (args.epochs - completed)
            lines = [
                "LiteDenoise training",
                f"Progress       {completed}/{args.epochs} epochs ({completed / args.epochs:.1%})",
                f"Clean PSNR     {current['clean_psnr']:.3f} dB",
                f"Teacher PSNR   {current['teacher_psnr']:.3f} dB",
                f"Clean L1       {current['clean_l1']:.6f}",
                f"Learning rate  {current['learning_rate']:.8f}",
                *( [f"Best warmup    {best_clean['clean_psnr']:.3f} dB clean at epoch {best_clean['epoch']}"]
                   if warmup else [] ),
                f"Best distilled {best_teacher['teacher_psnr']:.3f} dB teacher at epoch {best_teacher['epoch']}",
                f"Epoch time     about {duration(per_epoch)}",
                f"ETA            {duration(eta)} ({datetime.fromtimestamp(now + eta):%Y-%m-%d %H:%M:%S})",
            ]
        else:
            lines = ["LiteDenoise training", "Waiting for the first completed epoch..."]
        if not args.once:
            print("\033[2J\033[H", end="")
        print("\n".join(lines), flush=True)
        if args.once:
            return
        time.sleep(args.interval)


if __name__ == "__main__":
    main()
