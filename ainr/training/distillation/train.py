#!/usr/bin/env python3
"""Train a compact denoiser against cached SCUNet teacher targets."""

from __future__ import annotations

import argparse
import json
import random
from pathlib import Path

import numpy as np
import torch
import torch.nn.functional as functional
import yaml
from torch import nn
from torch.utils.data import DataLoader, Dataset
from tqdm import tqdm

from student import DistilledDenoiser


class PatchDataset(Dataset):
    def __init__(self, cache: Path, split: str, augment: bool) -> None:
        with (cache / "manifest.json").open("r", encoding="utf-8") as handle:
            self.records = [item for item in json.load(handle) if item["split"] == split]
        self.cache = cache
        self.augment = augment

    def __len__(self) -> int:
        return len(self.records)

    def __getitem__(self, index: int) -> tuple[torch.Tensor, torch.Tensor]:
        record = self.records[index]
        source = torch.from_numpy(np.load(self.cache / record["input"]).astype(np.float32)).permute(2, 0, 1)
        target = torch.from_numpy(np.load(self.cache / record["target"]).astype(np.float32)).permute(2, 0, 1)
        if self.augment:
            turns = random.randrange(4)
            source = torch.rot90(source, turns, (1, 2))
            target = torch.rot90(target, turns, (1, 2))
            if random.random() < 0.5:
                source, target = source.flip(2), target.flip(2)
        return source, target


def edge_loss(prediction: torch.Tensor, target: torch.Tensor) -> torch.Tensor:
    pred_x = prediction[:, :, :, 1:] - prediction[:, :, :, :-1]
    pred_y = prediction[:, :, 1:, :] - prediction[:, :, :-1, :]
    target_x = target[:, :, :, 1:] - target[:, :, :, :-1]
    target_y = target[:, :, 1:, :] - target[:, :, :-1, :]
    return functional.l1_loss(pred_x, target_x) + functional.l1_loss(pred_y, target_y)


def evaluate(model: nn.Module, loader: DataLoader, device: torch.device, amp: bool) -> float:
    model.eval()
    total = 0.0
    with torch.inference_mode():
        for source, target in loader:
            source, target = source.to(device), target.to(device)
            with torch.autocast(device.type, enabled=amp):
                total += functional.l1_loss(model(source), target).item() * source.shape[0]
    return total / max(1, len(loader.dataset))


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--config", type=Path, default=Path(__file__).with_name("config.yaml"))
    parser.add_argument("--epochs", type=int)
    args = parser.parse_args()
    config_path = args.config.resolve()
    with config_path.open("r", encoding="utf-8") as handle:
        config = yaml.safe_load(handle)
    base = config_path.parent
    cache = (base / config["data"]["cache_root"]).resolve()
    run = base / "runs" / "student_w32_b8"
    run.mkdir(parents=True, exist_ok=True)
    torch.manual_seed(config["seed"])
    np.random.seed(config["seed"])
    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    settings = config["training"]
    amp = bool(settings["amp"] and device.type == "cuda")
    model = DistilledDenoiser(**config["student"]).to(device)
    train_data = PatchDataset(cache, "train", augment=True)
    validation_data = PatchDataset(cache, "validation", augment=False)
    if not train_data or not validation_data:
        raise SystemExit("Both training and validation patches are required. Run prepare.py first.")
    train_loader = DataLoader(train_data, batch_size=settings["batch_size"], shuffle=True,
                              num_workers=settings["num_workers"], pin_memory=device.type == "cuda")
    validation_loader = DataLoader(validation_data, batch_size=settings["batch_size"], shuffle=False,
                                   num_workers=settings["num_workers"], pin_memory=device.type == "cuda")
    optimizer = torch.optim.AdamW(model.parameters(), lr=settings["learning_rate"], weight_decay=settings["weight_decay"])
    scaler = torch.amp.GradScaler("cuda", enabled=amp)
    epochs = args.epochs or settings["epochs"]
    accumulation = int(settings["gradient_accumulation"])
    best = float("inf")
    for epoch in range(1, epochs + 1):
        model.train()
        optimizer.zero_grad(set_to_none=True)
        progress = tqdm(train_loader, desc=f"Epoch {epoch}/{epochs}")
        for step, (source, target) in enumerate(progress, 1):
            source, target = source.to(device), target.to(device)
            with torch.autocast(device.type, enabled=amp):
                prediction = model(source)
                loss = (settings["l1_weight"] * functional.l1_loss(prediction, target)
                        + settings["edge_weight"] * edge_loss(prediction, target)) / accumulation
            scaler.scale(loss).backward()
            if step % accumulation == 0 or step == len(train_loader):
                scaler.step(optimizer)
                scaler.update()
                optimizer.zero_grad(set_to_none=True)
            progress.set_postfix(loss=f"{loss.item() * accumulation:.5f}")
        validation = evaluate(model, validation_loader, device, amp)
        checkpoint = {"model": model.state_dict(), "config": config, "epoch": epoch, "validation_l1": validation}
        torch.save(checkpoint, run / "latest.pt")
        if validation < best:
            best = validation
            torch.save(checkpoint, run / "best.pt")
        print(f"validation_l1={validation:.6f} best={best:.6f}")


if __name__ == "__main__":
    main()
