#!/usr/bin/env python3
"""Train the NPU-native LiteDenoise student from scratch."""

from __future__ import annotations

import argparse
import json
import math
import random
from collections import Counter
from pathlib import Path

import numpy as np
import torch
import torch.nn.functional as F
import yaml
from torch.utils.data import DataLoader, Dataset, WeightedRandomSampler
from tqdm import tqdm

from student_litedenoise import LiteDenoiseStudent


class PatchDataset(Dataset):
    def __init__(self, cache: Path, split: str, augment: bool) -> None:
        records = json.loads((cache / "manifest.json").read_text(encoding="utf-8"))
        self.records = [x for x in records if x["split"] == split]
        self.cache, self.augment = cache, augment

    def __len__(self) -> int:
        return len(self.records)

    def __getitem__(self, index: int) -> tuple[torch.Tensor, torch.Tensor, torch.Tensor]:
        record = self.records[index]
        values = [torch.from_numpy(np.load(self.cache / record[key]).astype(np.float32)).permute(2, 0, 1)
                  for key in ("input", "teacher", "clean")]
        if self.augment:
            turns = random.randrange(4)
            values = [torch.rot90(value, turns, (1, 2)) for value in values]
            if random.random() < 0.5:
                values = [value.flip(2) for value in values]
        return values[0], values[1], values[2]


def sampling_weights(dataset: PatchDataset, settings: dict) -> tuple[torch.Tensor, dict]:
    counts = Counter(record["dataset"] for record in dataset.records)
    power = float(settings.get("correction_sampling_power", 0.0))
    if not power:
        values = [1.0 / counts[record["dataset"]] for record in dataset.records]
        return torch.tensor(values, dtype=torch.double), {"mode": "dataset-balanced"}
    magnitudes = []
    for record in tqdm(dataset.records, desc="Measuring teacher corrections"):
        source = np.load(dataset.cache / record["input"]).astype(np.float32)
        teacher = np.load(dataset.cache / record["teacher"]).astype(np.float32)
        magnitudes.append(float(np.sqrt(np.mean((teacher - source) ** 2))))
    dataset_means = {
        name: float(np.mean([value for value, record in zip(magnitudes, dataset.records)
                            if record["dataset"] == name]))
        for name in counts
    }
    floor = float(settings.get("correction_sampling_floor", 0.25))
    cap = float(settings.get("correction_sampling_cap", 6.0))
    values = []
    for magnitude, record in zip(magnitudes, dataset.records):
        relative = magnitude / max(dataset_means[record["dataset"]], 1e-8)
        correction_weight = min(cap, max(floor, relative) ** power)
        values.append(correction_weight / counts[record["dataset"]])
    return torch.tensor(values, dtype=torch.double), {
        "mode": "dataset-balanced correction-weighted", "power": power,
        "minimum_correction": min(magnitudes), "mean_correction": float(np.mean(magnitudes)),
        "maximum_correction": max(magnitudes), "dataset_means": dataset_means,
    }


def core(value: torch.Tensor, border: int) -> torch.Tensor:
    return value[:, :, border:-border, border:-border] if border else value


def loss_for(prediction: torch.Tensor, teacher: torch.Tensor, clean: torch.Tensor,
             settings: dict, border: int, warmup: bool) -> torch.Tensor:
    prediction, teacher, clean = core(prediction, border), core(teacher, border), core(clean, border)
    teacher_weight = 0.0 if warmup else float(settings["teacher_mse_weight"])
    return (float(settings["clean_mse_weight"]) * F.mse_loss(prediction, clean)
            + teacher_weight * F.mse_loss(prediction, teacher)
            + float(settings["clean_l1_weight"]) * F.l1_loss(prediction, clean))


@torch.inference_mode()
def evaluate(model: torch.nn.Module, loader: DataLoader, device: torch.device,
             amp: bool, settings: dict, border: int) -> dict[str, float]:
    model.eval()
    totals = Counter()
    for source, teacher, clean in loader:
        source, teacher, clean = source.to(device), teacher.to(device), clean.to(device)
        with torch.autocast(device.type, enabled=amp):
            prediction = model(source)
        p, t, c = core(prediction.float(), border), core(teacher, border), core(clean, border)
        clean_mse, teacher_mse = F.mse_loss(p, c), F.mse_loss(p, t)
        count = source.shape[0]
        totals.update(samples=count, clean_mse=clean_mse.item() * count,
                      teacher_mse=teacher_mse.item() * count, clean_l1=F.l1_loss(p, c).item() * count)
    count = totals["samples"]
    clean_mse, teacher_mse = totals["clean_mse"] / count, totals["teacher_mse"] / count
    return {"clean_psnr": -10 * math.log10(max(clean_mse, 1e-12)),
            "teacher_psnr": -10 * math.log10(max(teacher_mse, 1e-12)),
            "clean_l1": totals["clean_l1"] / count}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--config", type=Path, default=Path(__file__).with_name("config_litedenoise.yaml"))
    parser.add_argument("--epochs", type=int)
    parser.add_argument("--resume", type=Path)
    parser.add_argument("--run-name", default="litedenoise_w16")
    args = parser.parse_args()
    config_path = args.config.resolve()
    config = yaml.safe_load(config_path.read_text(encoding="utf-8"))
    base, settings = config_path.parent, config["training"]
    run = base / "runs" / args.run_name
    run.mkdir(parents=True, exist_ok=True)
    torch.manual_seed(config["seed"]); np.random.seed(config["seed"]); random.seed(config["seed"])
    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    amp = bool(settings["amp"] and device.type == "cuda")
    cache = (base / config["data"]["cache_root"]).resolve()
    train_data, validation_data = PatchDataset(cache, "train", True), PatchDataset(cache, "validation", False)
    counts = Counter(x["dataset"] for x in train_data.records)
    weights, sampling_report = sampling_weights(train_data, settings)
    sampler = WeightedRandomSampler(weights, len(train_data), replacement=True,
                                    generator=torch.Generator().manual_seed(config["seed"]))
    train_loader = DataLoader(train_data, batch_size=settings["batch_size"], sampler=sampler,
                              num_workers=settings["num_workers"], pin_memory=device.type == "cuda")
    validation_loader = DataLoader(validation_data, batch_size=settings["batch_size"], shuffle=False,
                                   num_workers=settings["num_workers"], pin_memory=device.type == "cuda")
    model = LiteDenoiseStudent(**config["student"], clamp_output=False).to(device)
    optimizer = torch.optim.AdamW(model.parameters(), lr=settings["learning_rate"], weight_decay=settings["weight_decay"])
    epochs = args.epochs or int(settings["epochs"])
    scheduler = torch.optim.lr_scheduler.CosineAnnealingLR(optimizer, T_max=epochs, eta_min=settings["minimum_learning_rate"])
    scaler = torch.amp.GradScaler("cuda", enabled=amp)
    warmup_epochs = int(settings["warmup_epochs"])
    start_epoch, history, best_phase_metric, stale = 0, [], -math.inf, 0
    if args.resume:
        checkpoint = torch.load(args.resume, map_location=device, weights_only=False)
        model.load_state_dict(checkpoint["model"]); optimizer.load_state_dict(checkpoint["optimizer"])
        scheduler.load_state_dict(checkpoint["scheduler"])
        start_epoch, history = checkpoint["epoch"], checkpoint.get("history", [])
        phase_history = [x for x in history if x["epoch"] > warmup_epochs]
        if phase_history:
            best_phase_metric = max(x["teacher_psnr"] for x in phase_history)
            best_index = max(range(len(phase_history)), key=lambda i: phase_history[i]["teacher_psnr"])
            stale = len(phase_history) - best_index - 1
        elif history:
            best_phase_metric = max(x["clean_psnr"] for x in history)
        if not (run / "best_distilled.pt").exists():
            torch.save(checkpoint, run / "best_distilled.pt")
    accumulation, border = int(settings["gradient_accumulation"]), int(config["loss_border"])
    print(f"device={device} parameters={sum(p.numel() for p in model.parameters())} train={len(train_data)} validation={len(validation_data)} datasets={dict(counts)} sampling={sampling_report}")
    for epoch in range(start_epoch + 1, epochs + 1):
        model.train(); optimizer.zero_grad(set_to_none=True)
        progress = tqdm(train_loader, desc=f"LiteDenoise {epoch}/{epochs}")
        for step, (source, teacher, clean) in enumerate(progress, 1):
            source, teacher, clean = source.to(device), teacher.to(device), clean.to(device)
            with torch.autocast(device.type, enabled=amp):
                loss = loss_for(model(source), teacher, clean, settings, border,
                                epoch <= warmup_epochs) / accumulation
            scaler.scale(loss).backward()
            if step % accumulation == 0 or step == len(train_loader):
                scaler.step(optimizer); scaler.update(); optimizer.zero_grad(set_to_none=True)
            progress.set_postfix(loss=f"{loss.item() * accumulation:.4f}")
        metrics = evaluate(model, validation_loader, device, amp, settings, border)
        scheduler.step()
        history.append({"epoch": epoch, **metrics, "learning_rate": scheduler.get_last_lr()[0]})
        checkpoint = {"model": model.state_dict(), "optimizer": optimizer.state_dict(),
                      "scheduler": scheduler.state_dict(), "epoch": epoch, "history": history, "config": config}
        torch.save(checkpoint, run / "latest.pt")
        if epoch == warmup_epochs + 1:
            best_phase_metric, stale = -math.inf, 0
        phase_metric = metrics["clean_psnr"] if epoch <= warmup_epochs else metrics["teacher_psnr"]
        improvement = phase_metric - best_phase_metric
        if improvement > 0:
            torch.save(checkpoint, run / ("best_warmup.pt" if epoch <= warmup_epochs else "best_distilled.pt"))
        if improvement >= float(settings["early_stopping_min_delta_db"]):
            best_phase_metric, stale = phase_metric, 0
        else:
            best_phase_metric, stale = max(best_phase_metric, phase_metric), stale + 1
        (run / "history.json").write_text(json.dumps(history, indent=2), encoding="utf-8")
        phase_name = "clean_psnr" if epoch <= warmup_epochs else "teacher_psnr"
        print(f"validation={metrics} monitored={phase_name} best={best_phase_metric:.3f} stale={stale}")
        if epoch > warmup_epochs and stale >= int(settings["early_stopping_patience"]):
            print("early stopping: distillation teacher PSNR no longer improving")
            break


if __name__ == "__main__":
    main()
