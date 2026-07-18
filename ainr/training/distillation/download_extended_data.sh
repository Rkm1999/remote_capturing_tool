#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SOURCES="$ROOT/data/sources"
DOWNLOADS="$ROOT/data/downloads"
PYTHON="${PYTHON:-/home/ryu/.cache/scunet-int8-venv/bin/python}"
mkdir -p "$SOURCES" "$DOWNLOADS"

download_sidd() {
  local archive="$DOWNLOADS/SIDD_Medium_Srgb.zip"
  curl --fail --location --continue-at - --retry 20 --retry-all-errors \
    --speed-limit 1024 --speed-time 60 --output "$archive" \
    http://130.63.97.225/share/SIDD_Medium_Srgb.zip
  mkdir -p "$SOURCES/sidd-medium-srgb"
  unzip -q -o "$archive" -d "$SOURCES/sidd-medium-srgb"
}

download_nind() {
  local tool="$SOURCES/nind-tools/src/nind_denoise/tools/dl_ds_1.py"
  if [[ ! -f "$tool" ]]; then
    git clone --depth 1 https://github.com/trougnouf/nind-denoise.git "$SOURCES/nind-tools"
  fi
  "$PYTHON" "$ROOT/download_nind.py" --catalog "$tool" --target "$SOURCES/nind"
}

download_midd() {
  local sensor="${1:-ISOCELL_3P9}"
  local archive="$DOWNLOADS/MIDD_${sensor}.zip"
  curl --fail --location --continue-at - --retry 20 --retry-all-errors \
    --speed-limit 1024 --speed-time 60 \
    --user 'Gq3n2cS7QkH7ZMz:' \
    --output "$archive" \
    "https://download.ai-benchmark.com/public.php/webdav/${sensor}.zip"
  mkdir -p "$SOURCES/midd/$sensor"
  unzip -q -o "$archive" -d "$SOURCES/midd/$sensor"
}

case "${1:-}" in
  sidd) download_sidd ;;
  nind) download_nind ;;
  midd) download_midd "${2:-ISOCELL_3P9}" ;;
  *)
    echo "Usage: $0 {sidd|nind|midd [sensor]}" >&2
    exit 2
    ;;
esac
