# AI NR Engine Test v0.1 Beta

This prerelease is a standalone engineering harness for validating the SCUNet
denoising pipeline before it is integrated into Remote Capture. It is not a
separate production product.

## Android artifact

`AINR-Android-v0.1.0-beta.1-debug.apk` is an arm64-only, debug-key-signed APK
for direct device testing. It supports GPU execution and supported Qualcomm,
MediaTek, and Samsung NPU backends. Unsupported devices can use GPU mode.

The APK is intentionally large because it includes the generic SCUNet model and
the runtime libraries needed for on-device accelerator compilation. Verify the
download against the attached `SHA256SUMS.txt` before installing it.

## Scope

- Import or share a JPEG image.
- Run full-resolution SCUNet denoising with 192 x 192 tiles.
- Choose GPU, NPU, or concurrent GPU + NPU execution when supported.
- Monitor setup and tile progress.
- Save the completed JPEG.

Detailed compatibility, validation results, limitations, and build instructions
are in [README.md](README.md).
