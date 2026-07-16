#!/usr/bin/env bash
set -euo pipefail

printf 'xtool: '
xtool --version
printf 'swift: '
swift --version | head -n 1

if swift sdk list 2>/dev/null | grep -qx darwin; then
    echo 'Darwin SDK: installed'
else
    echo 'Darwin SDK: missing'
    echo 'Run: xtool sdk install /path/to/Xcode.xip'
    exit 1
fi

if command -v usbmuxd >/dev/null; then
    echo 'usbmuxd: installed'
else
    echo 'usbmuxd: missing (required for USB installation)'
    exit 1
fi
