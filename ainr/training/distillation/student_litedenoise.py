"""NPU-native 1.96M-parameter denoising student."""

from __future__ import annotations

import torch
from torch import nn
from torch.nn import functional


class LiteDenoisingBlock(nn.Module):
    def __init__(self, channels: int) -> None:
        super().__init__()
        middle = channels // 2
        self.conv1 = nn.Conv2d(channels, middle, 3, padding=1)
        self.conv2 = nn.Conv2d(middle, channels, 3, padding=1)
        self.activation = nn.ReLU(inplace=False)

    def forward(self, value: torch.Tensor) -> torch.Tensor:
        residual = self.conv2(self.activation(self.conv1(value)))
        return self.activation(value + residual)


class LiteDenoiseStudent(nn.Module):
    """Four-level U-Net restricted to mobile accelerator native operators."""

    def __init__(self, base_width: int = 16, clamp_output: bool = True) -> None:
        super().__init__()
        self.clamp_output = clamp_output
        f0, f1, f2, f3, f4 = (base_width * scale for scale in (1, 2, 4, 8, 16))
        self.input = nn.Conv2d(3, f0, 3, padding=1)
        self.encoder0 = LiteDenoisingBlock(f0)
        self.down0 = self.downsample(f0, f1)
        self.encoder1 = LiteDenoisingBlock(f1)
        self.down1 = self.downsample(f1, f2)
        self.encoder2 = LiteDenoisingBlock(f2)
        self.down2 = self.downsample(f2, f3)
        self.encoder3 = LiteDenoisingBlock(f3)
        self.down3 = self.downsample(f3, f4)
        self.body = LiteDenoisingBlock(f4)
        self.up3 = nn.Conv2d(f4 + f3, f3, 3, padding=1)
        self.decoder3 = LiteDenoisingBlock(f3)
        self.up2 = nn.Conv2d(f3 + f2, f2, 3, padding=1)
        self.decoder2 = LiteDenoisingBlock(f2)
        self.up1 = nn.Conv2d(f2 + f1, f1, 3, padding=1)
        self.decoder1 = LiteDenoisingBlock(f1)
        self.up0 = nn.Conv2d(f1 + f0, f0, 3, padding=1)
        self.decoder0 = LiteDenoisingBlock(f0)
        self.output = nn.Conv2d(f0, 3, 3, padding=1)

    @staticmethod
    def downsample(input_channels: int, output_channels: int) -> nn.Sequential:
        return nn.Sequential(
            nn.Conv2d(input_channels, output_channels, 3, stride=2, padding=1),
            nn.ReLU(inplace=False),
        )

    @staticmethod
    def upsample(value: torch.Tensor) -> torch.Tensor:
        return functional.interpolate(value, scale_factor=2.0, mode="nearest")

    def forward(self, noisy: torch.Tensor) -> torch.Tensor:
        level0 = self.encoder0(self.input(noisy))
        level1 = self.encoder1(self.down0(level0))
        level2 = self.encoder2(self.down1(level1))
        level3 = self.encoder3(self.down2(level2))
        body = self.body(self.down3(level3))
        decoded3 = self.decoder3(self.up3(torch.cat((self.upsample(body), level3), dim=1)))
        decoded2 = self.decoder2(self.up2(torch.cat((self.upsample(decoded3), level2), dim=1)))
        decoded1 = self.decoder1(self.up1(torch.cat((self.upsample(decoded2), level1), dim=1)))
        decoded0 = self.decoder0(self.up0(torch.cat((self.upsample(decoded1), level0), dim=1)))
        restored = noisy + self.output(decoded0)
        return restored.clamp(0, 1) if self.clamp_output else restored
