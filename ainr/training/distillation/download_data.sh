#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SOURCES="$ROOT/data/sources"
mkdir -p "$SOURCES"

if [[ ! -d "$SOURCES/scunet/.git" ]]; then
  git clone --depth 1 https://github.com/cszn/SCUNet.git "$SOURCES/scunet"
fi
if [[ ! -d "$SOURCES/polyu/.git" ]]; then
  git clone --depth 1 https://github.com/csjunxu/PolyU-Real-World-Noisy-Images-Dataset.git "$SOURCES/polyu"
fi

MODEL_DIR="$SOURCES/scunet/model_zoo"
MODEL="$MODEL_DIR/scunet_color_real_psnr.pth"
mkdir -p "$MODEL_DIR"
if [[ ! -f "$MODEL" ]]; then
  curl --fail --location --output "$MODEL" \
    https://github.com/cszn/KAIR/releases/download/v1.0/scunet_color_real_psnr.pth
fi
