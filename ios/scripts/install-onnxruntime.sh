#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
vendor="$root/Vendor"
target="$vendor/onnxruntime.xcframework"
[[ -d "$target" ]] && exit 0
mkdir -p "$vendor"
archive="$(mktemp)"
directory="$(mktemp -d)"
trap 'rm -f "$archive"; rm -rf "$directory"' EXIT
curl -fL https://download.onnxruntime.ai/pod-archive-onnxruntime-c-1.20.0.zip -o "$archive"
unzip -q "$archive" -d "$directory"
cp -R "$directory/onnxruntime.xcframework" "$target"
