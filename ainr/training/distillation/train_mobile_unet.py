#!/usr/bin/env python3
"""Train the multi-scale mobile student on cached teacher and clean targets."""

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

from student import MobileUNetStudent
from train import edge_loss


def laplacian_loss(prediction: torch.Tensor, target: torch.Tensor) -> torch.Tensor:
    kernel = prediction.new_tensor(
        [[0.0, 1.0, 0.0], [1.0, -4.0, 1.0], [0.0, 1.0, 0.0]]
    ).view(1, 1, 3, 3).repeat(prediction.shape[1], 1, 1, 1)
    predicted_detail = functional.conv2d(prediction, kernel, padding=1, groups=prediction.shape[1])
    target_detail = functional.conv2d(target, kernel, padding=1, groups=target.shape[1])
    return functional.l1_loss(predicted_detail, target_detail)


class ExtendedPatchDataset(Dataset):
    def __init__(self, cache: Path, split: str, augment: bool) -> None:
        records = json.loads((cache / "manifest.json").read_text(encoding="utf-8"))
        self.records = [record for record in records if record["split"] == split]
        self.cache = cache
        self.augment = augment

    def __len__(self) -> int:
        return len(self.records)

    def __getitem__(self, index: int) -> tuple[torch.Tensor, torch.Tensor, torch.Tensor]:
        record = self.records[index]
        values = [
            torch.from_numpy(np.load(self.cache / record[key]).astype(np.float32)).permute(2, 0, 1)
            for key in ("input", "teacher", "clean")
        ]
        if self.augment:
            turns = random.randrange(4)
            values = [torch.rot90(value, turns, (1, 2)) for value in values]
            if random.random() < 0.5:
                values = [value.flip(2) for value in values]
        return values[0], values[1], values[2]


def objective(
    prediction: torch.Tensor,
    source: torch.Tensor,
    teacher: torch.Tensor,
    clean: torch.Tensor,
    settings: dict,
) -> torch.Tensor:
    teacher_difference = (prediction - teacher).abs()
    change_weight = float(settings.get("teacher_change_weight", 0.0))
    if change_weight:
        teacher_change = (source - teacher).abs().detach()
        scale = teacher_change.mean(dim=(1, 2, 3), keepdim=True).clamp_min(1e-4)
        teacher_loss = (teacher_difference * (1.0 + change_weight * teacher_change / scale)).mean()
    else:
        teacher_loss = teacher_difference.mean()
    loss = (
        settings["teacher_weight"] * teacher_loss
        + settings["clean_weight"] * functional.l1_loss(prediction, clean)
        + settings["edge_weight"] * edge_loss(prediction, teacher)
        + settings.get("clean_edge_weight", 0.0) * edge_loss(prediction, clean)
    )
    return (
        loss
        + settings.get("laplacian_weight", 0.0) * laplacian_loss(prediction, teacher)
        + settings.get("clean_laplacian_weight", 0.0) * laplacian_loss(prediction, clean)
    )


def evaluate(model: nn.Module, loader: DataLoader, device: torch.device, amp: bool, settings: dict) -> dict:
    model.eval()
    totals = {"objective": 0.0, "teacher_l1": 0.0, "clean_l1": 0.0}
    with torch.inference_mode():
        for source, teacher, clean in loader:
            source, teacher, clean = source.to(device), teacher.to(device), clean.to(device)
            with torch.autocast(device.type, enabled=amp):
                prediction = model(source)
                values = {
                    "objective": objective(prediction, source, teacher, clean, settings),
                    "teacher_l1": functional.l1_loss(prediction, teacher),
                    "clean_l1": functional.l1_loss(prediction, clean),
                }
            for key, value in values.items():
                totals[key] += value.item() * source.shape[0]
    return {key: value / len(loader.dataset) for key, value in totals.items()}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--config", type=Path, default=Path(__file__).with_name("config_mobile_unet.yaml"))
    parser.add_argument("--epochs", type=int)
    parser.add_argument("--resume", type=Path)
    parser.add_argument("--initial-checkpoint", type=Path)
    parser.add_argument("--learning-rate", type=float)
    parser.add_argument("--run-name", default="mobile_unet_w24")
    args = parser.parse_args()
    config_path = args.config.resolve()
    config = yaml.safe_load(config_path.read_text(encoding="utf-8"))
    base = config_path.parent
    settings = config["training"]
    epochs = args.epochs or int(settings["epochs"])
    if args.resume and args.initial_checkpoint:
        parser.error("--resume and --initial-checkpoint are mutually exclusive")
    run = base / "runs" / args.run_name
    run.mkdir(parents=True, exist_ok=True)
    torch.manual_seed(config["seed"])
    np.random.seed(config["seed"])
    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    amp = bool(settings["amp"] and device.type == "cuda")
    cache = (base / config["data"]["extended_cache_root"]).resolve()
    train_data = ExtendedPatchDataset(cache, "train", augment=True)
    validation_data = ExtendedPatchDataset(cache, "validation", augment=False)
    train_loader = DataLoader(train_data, batch_size=settings["batch_size"], shuffle=True,
                              num_workers=settings["num_workers"], pin_memory=device.type == "cuda")
    validation_loader = DataLoader(validation_data, batch_size=settings["batch_size"], shuffle=False,
                                   num_workers=settings["num_workers"], pin_memory=device.type == "cuda")
    student_config = dict(config["student"])
    student_config["encoder_blocks"] = tuple(student_config["encoder_blocks"])
    model = MobileUNetStudent(**student_config).to(device)
    if args.initial_checkpoint:
        checkpoint = torch.load(args.initial_checkpoint, map_location=device, weights_only=False)
        incompatible = model.load_state_dict(checkpoint["model"], strict=False)
        unexpected = list(incompatible.unexpected_keys)
        missing = [key for key in incompatible.missing_keys if not key.startswith("detail.")]
        if unexpected or missing:
            raise RuntimeError(f"Incompatible initial checkpoint: missing={missing}, unexpected={unexpected}")
    learning_rate = args.learning_rate or settings["learning_rate"]
    optimizer = torch.optim.AdamW(model.parameters(), lr=learning_rate,
                                  weight_decay=settings["weight_decay"])
    scheduler = torch.optim.lr_scheduler.CosineAnnealingLR(
        optimizer, T_max=epochs, eta_min=settings["minimum_learning_rate"]
    )
    scaler = torch.amp.GradScaler("cuda", enabled=amp)
    accumulation = int(settings["gradient_accumulation"])
    best = float("inf")
    early_stopping_best = float("inf")
    stale_epochs = 0
    history = []
    start_epoch = 0
    if args.resume:
        checkpoint = torch.load(args.resume, map_location=device, weights_only=False)
        model.load_state_dict(checkpoint["model"])
        if "optimizer" in checkpoint:
            optimizer.load_state_dict(checkpoint["optimizer"])
        if "scheduler" in checkpoint:
            scheduler.load_state_dict(checkpoint["scheduler"])
        history = checkpoint.get("history", [])
        start_epoch = int(checkpoint.get("epoch", 0))
        if history:
            best = min(item["objective"] for item in history)
            early_stopping_best = best
    for epoch in range(start_epoch + 1, start_epoch + epochs + 1):
        model.train()
        optimizer.zero_grad(set_to_none=True)
        progress = tqdm(train_loader, desc=f"Mobile U-Net {epoch}/{start_epoch + epochs}")
        for step, (source, teacher, clean) in enumerate(progress, 1):
            source, teacher, clean = source.to(device), teacher.to(device), clean.to(device)
            with torch.autocast(device.type, enabled=amp):
                prediction = model(source)
                loss = objective(prediction, source, teacher, clean, settings) / accumulation
            scaler.scale(loss).backward()
            if step % accumulation == 0 or step == len(train_loader):
                scaler.step(optimizer)
                scaler.update()
                optimizer.zero_grad(set_to_none=True)
            progress.set_postfix(loss=f"{loss.item() * accumulation:.5f}")
        validation = evaluate(model, validation_loader, device, amp, settings)
        scheduler.step()
        history.append({"epoch": epoch, **validation, "learning_rate": scheduler.get_last_lr()[0]})
        checkpoint = {"model": model.state_dict(), "optimizer": optimizer.state_dict(),
                      "scheduler": scheduler.state_dict(), "config": config, "epoch": epoch,
                      "validation": validation, "history": history}
        torch.save(checkpoint, run / "latest.pt")
        if validation["objective"] < best:
            best = validation["objective"]
            torch.save(checkpoint, run / "best.pt")
        (run / "history.json").write_text(json.dumps(history, indent=2), encoding="utf-8")
        print(f"validation={validation} best_objective={best:.6f}")
        patience = int(settings.get("early_stopping_patience", 0))
        minimum_delta = float(settings.get("early_stopping_min_delta", 0.0))
        if validation["objective"] < early_stopping_best - minimum_delta:
            early_stopping_best = validation["objective"]
            stale_epochs = 0
        else:
            stale_epochs += 1
        if patience and stale_epochs >= patience:
            print(
                f"early_stopping epoch={epoch} patience={patience} "
                f"minimum_delta={minimum_delta} best={early_stopping_best:.6f}"
            )
            break


if __name__ == "__main__":
    main()
