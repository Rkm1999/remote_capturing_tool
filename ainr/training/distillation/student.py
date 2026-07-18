"""Mobile-friendly residual student used for SCUNet distillation."""

from __future__ import annotations

import torch
from torch import nn


class ResidualBlock(nn.Module):
    def __init__(self, channels: int) -> None:
        super().__init__()
        self.body = nn.Sequential(
            nn.Conv2d(channels, channels, 3, padding=1),
            nn.ReLU(inplace=True),
            nn.Conv2d(channels, channels, 3, padding=1),
        )
        self.activation = nn.ReLU(inplace=True)

    def forward(self, value: torch.Tensor) -> torch.Tensor:
        return self.activation(value + self.body(value))


class DistilledDenoiser(nn.Module):
    """Fully convolutional RGB residual denoiser with mobile-safe operators."""

    def __init__(self, width: int = 32, blocks: int = 8) -> None:
        super().__init__()
        self.head = nn.Conv2d(3, width, 3, padding=1)
        self.body = nn.Sequential(*(ResidualBlock(width) for _ in range(blocks)))
        self.tail = nn.Conv2d(width, 3, 3, padding=1)

    def forward(self, noisy: torch.Tensor) -> torch.Tensor:
        features = torch.relu(self.head(noisy))
        residual = self.tail(self.body(features))
        return torch.clamp(noisy + residual, 0.0, 1.0)


class InvertedResidualBlock(nn.Module):
    def __init__(self, channels: int, expansion: int = 2) -> None:
        super().__init__()
        hidden = channels * expansion
        self.body = nn.Sequential(
            nn.Conv2d(channels, hidden, 1),
            nn.ReLU(inplace=True),
            nn.Conv2d(hidden, hidden, 3, padding=1, groups=hidden),
            nn.ReLU(inplace=True),
            nn.Conv2d(hidden, channels, 1),
        )
        self.activation = nn.ReLU(inplace=True)

    def forward(self, value: torch.Tensor) -> torch.Tensor:
        return self.activation(value + self.body(value))


def inverted_stage(channels: int, blocks: int, expansion: int) -> nn.Sequential:
    return nn.Sequential(*(InvertedResidualBlock(channels, expansion) for _ in range(blocks)))


class MobileUNetStudent(nn.Module):
    """Two-level mobile U-Net with additive skips and NPU-friendly operators."""

    def __init__(
        self,
        base_width: int = 24,
        encoder_blocks: tuple[int, int] = (2, 3),
        body_blocks: int = 6,
        expansion: int = 2,
        detail_width: int = 0,
        clamp_output: bool = True,
    ) -> None:
        super().__init__()
        self.clamp_output = clamp_output
        middle = base_width * 2
        deep = base_width * 4
        self.head = nn.Conv2d(3, base_width, 3, padding=1)
        self.encoder1 = inverted_stage(base_width, encoder_blocks[0], expansion)
        self.down1 = nn.Conv2d(base_width, middle, 3, stride=2, padding=1)
        self.encoder2 = inverted_stage(middle, encoder_blocks[1], expansion)
        self.down2 = nn.Conv2d(middle, deep, 3, stride=2, padding=1)
        self.body = inverted_stage(deep, body_blocks, expansion)
        self.up2 = nn.ConvTranspose2d(deep, middle, 2, stride=2)
        self.decoder2 = inverted_stage(middle, encoder_blocks[1], expansion)
        self.up1 = nn.ConvTranspose2d(middle, base_width, 2, stride=2)
        self.decoder1 = inverted_stage(base_width, encoder_blocks[0], expansion)
        self.tail = nn.Conv2d(base_width, 3, 3, padding=1)
        self.detail = None
        if detail_width > 0:
            self.detail = nn.Sequential(
                nn.Conv2d(3, detail_width, 3, padding=1),
                nn.ReLU(inplace=True),
                nn.Conv2d(detail_width, detail_width, 3, padding=1),
                nn.ReLU(inplace=True),
                nn.Conv2d(detail_width, 3, 3, padding=1),
            )
            nn.init.zeros_(self.detail[-1].weight)
            nn.init.zeros_(self.detail[-1].bias)

    def forward(self, noisy: torch.Tensor) -> torch.Tensor:
        head = torch.relu(self.head(noisy))
        level1 = self.encoder1(head)
        level2 = self.encoder2(self.down1(level1))
        deep = self.body(self.down2(level2))
        decoded2 = self.decoder2(self.up2(deep) + level2)
        decoded1 = self.decoder1(self.up1(decoded2) + level1)
        output = noisy + self.tail(decoded1)
        if self.detail is not None:
            output = output + self.detail(noisy)
        return torch.clamp(output, 0.0, 1.0) if self.clamp_output else output
