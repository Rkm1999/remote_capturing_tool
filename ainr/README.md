# SCUNet Denoiser

Standalone Android and iOS apps for running the fixed-shape SCUNet real-image
denoiser over a full-resolution image. This directory is an engineering test
harness for the AI NR pipeline planned for Remote Capture, not a separate
production application. Prerelease packaging notes are in
[`RELEASE.md`](RELEASE.md).

## Workflow

1. Choose one or more images with the system image picker, or share/open an image
   with SCUNet Denoiser.
2. Select the distilled performance or full SCUNet quality model, then choose an
   available accelerator.
3. Run denoising and monitor model setup plus tile progress.
4. Save the completed JPEGs to `Pictures/SCUNet Denoiser` or the iOS photo library.

Batch selections are processed sequentially while the prepared accelerator
session remains warm. Progress identifies the current image and tile. Canceling
stops the active image and the remaining queue, and Save exports every result.

Images are decoded into oriented sRGB pixels. Processing uses 192 x 192 tiles,
8 pixels of overlap on each side, reflected image borders, and feathered seams.
The effective tile core is 176 x 176. Output is a full-resolution sRGB JPEG at
quality 96.

## Accelerators

- **GPU:** LiteRT OpenCL with FP16 arithmetic and a persistent program cache.
- **NPU:** the generic LiteRT model is compiled on the phone through the
  detected Qualcomm, MediaTek, or Samsung compiler plugin. No workstation AOT
  model is included. The app exposes only the detected vendor's compiler and
  dispatch pair to LiteRT so multiple bundled backends cannot conflict.
- **GPU + NPU:** NPU is compiled first, then independent GPU and NPU sessions
  claim individual tiles from opposite ends of the image. The dynamic scheduler
  adapts to device and thermal speed instead of relying on a fixed band ratio.
  Results are restored to raster order and use the normal feathering at every
  tile edge, including the accelerator transition.

Android uses LiteRT on-device AOT for supported NPUs. The first NPU run compiles
the model on the phone and stores the compiled context in private app storage.
Later app processes load that context instead of compiling it again. The cache
key includes the LiteRT version and model hash, so replacing either creates a
new cache automatically. Clearing app data also clears the compiled context.

Android includes two model modes. **Performance · Distilled** uses a compact
1,963,411-parameter LiteDenoise U-Net distilled from the official SCUNet
teacher with correction-weighted sampling over SIDD, NIND, and MIDD.
**Quality · SCUNet
16-bit** uses the full
official SCUNet color real PSNR network with FP16 weights. Both modes support
GPU, NPU, and GPU + NPU; Quality is the default. Each model has an independent
GPU program or NPU AOT cache key.

iOS already uses the platform equivalent. Core ML compiles the bundled model
once, and the app retains the `.mlmodelc` artifact in Application Support for
reuse on later launches with the selected Core ML compute units.

The iOS build includes the same two quality modes: High Performance uses the
FP16 LiteDenoise student, while High Quality uses the original FP16 SCUNet.
Both retain FP32 image tensors and have independent persistent compilation
caches. High Quality is the default.

## Compatibility

- Android 10 or later
- 64-bit Arm device
- GPU mode requires a compatible Android OpenCL driver
- NPU modes require Android 12 or later
- Qualcomm: Snapdragon 8 Gen 1 (SM8450 / HTP V69), 8+ Gen 1
  (SM8475 / HTP V69), 8 Gen 2 (SM8550 / HTP V73), 8 Gen 3
  (SM8650 / HTP V75), 8 Elite (SM8750 / HTP V79), and 8 Elite Gen 5
  (SM8850 / HTP V81)
- MediaTek: SoCs accepted by LiteRT 2.1.6's MediaTek compatibility checker,
  plus the documented Dimensity 7300, 8300, 9000, 9200, 9300, 9400, and 9500
- Samsung: Exynos 2500 (E9955) and Exynos 2600 (E9965)

Other devices can use GPU mode. NPU and GPU + NPU are disabled when the SoC is
not supported; older Exynos devices currently use GPU. The MediaTek and
Samsung plugins are built from the exact
LiteRT 2.1.6 source used by the app and depend on the vendor NPU libraries
installed by the device manufacturer.

## Verified device run

Galaxy S25 SM-S931W, Android 16:

- Orientation-aware JPEG import: verified
- GPU processing and gallery export: verified
- Qualcomm on-device JIT plus HTP inference: verified
- GPU + NPU concurrent processing: verified
- Corrected warm 512 x 768 run: NPU 1.77 seconds, dual 1.00 second
- Performance distilled 4000 x 6000 end-to-end: GPU 8.303 seconds, warm NPU
  2.882 seconds, and GPU + NPU 2.514 seconds
- Quality SCUNet 4000 x 6000 end-to-end: GPU 98.058 seconds and warm NPU
  100.124 seconds at thermal level 0; GPU + NPU measured 56.207 seconds in a
  supplemental run that began at thermal level 1
- Dynamic-scheduler 4000 x 6000 inference immediately after NPU JIT: 55.40
  seconds at thermal level 3, with 442 GPU tiles and 363 NPU tiles
- Saved output: upright 4000 x 6000 JPEG
- INT8 GPU representation: fully delegated and verified
- INT8 NPU representation: compiled as one HTP graph and verified
- First INT8 HTP compilation: 159.05 seconds with persistent AOT output
- Warm 512 x 768 INT8 runs: GPU 2.11 seconds, NPU 1.72 seconds,
  GPU + NPU 0.92 seconds end to end
- Process-restart INT8 NPU run: 0.64-second AOT load and 2.58 seconds total

The first corrected NPU run took about 3 minutes 9 seconds on the tested phone,
including 3 minutes 7 seconds of JIT setup and a 512 x 768 test image. Later
NPU and dual runs reuse the session. The earlier 25.9/11.9-second v0.2 figures
were invalid: a missing Qualcomm DSP runtime file caused LiteRT to fall back to
XNNPACK CPU execution while the UI still identified the requested NPU mode.
See `verification/24mp-comparison/README.md` for the controlled full-resolution
comparison.

Full-frame output comparisons between the distilled and SCUNet models across
ISO 1600 through 51200 are in
[`verification/distilled-vs-scunet-iso`](verification/distilled-vs-scunet-iso).
The later denoise-focused continuation and its untouched final ISO evaluation
are in
[`verification/distilled-denoise-final-iso`](verification/distilled-denoise-final-iso).
The subsequent clean-detail refinement and final ISO progression are in
[`verification/distilled-detail-final-iso`](verification/distilled-detail-final-iso).
The final validation-plateau checkpoint and external ISO confirmation are in
[`verification/distilled-plateau-final-iso`](verification/distilled-plateau-final-iso).

The Qualcomm SM8750 path is verified. Other Qualcomm generations plus the
MediaTek and Samsung paths are integrated but require testing on each target
SoC. Successful plugin compilation does not prove that every SCUNet operation
is supported by every vendor driver.

The dynamic dual scheduler was validated with the same 24 MP ISO 51200 image.
Its two workers overlapped for 1.81x and adapted to the concurrent hot-device
speeds, where GPU averaged 116.98 ms per tile and NPU averaged 133.93 ms. The
generated 4000 x 6000 JPEG measured 50.93 dB PSNR against the GPU-only reference
and 49.93 dB against the NPU-only reference, with no edge spike at the L-shaped
accelerator handoff. A thermal-level-1 warm end-to-end result is still required
before replacing the earlier controlled 72.18-second figure.

## Build

### Android

```bash
./gradlew assembleDebug
```

APK output:

```text
app/build/outputs/apk/debug/app-debug.apk
```

The APK is arm64-only and intentionally large because it bundles the 101 MiB
full FP16 SCUNet model, the compact distilled model, five Qualcomm HTP runtime
generations, and Qualcomm, MediaTek, and Samsung LiteRT plugin bridges.

### iOS

The iOS port is in [`ios`](ios). It imports from Photos or Files, runs the
full-resolution image as overlapping 192 x 192 tiles, shows tile progress,
and saves or shares the completed JPEG. It loads one native FP16 Core ML
program directly and exposes GPU, Neural Engine, GPU + Neural Engine, and CPU
compute modes. The combined option runs independent GPU and Neural Engine
sessions concurrently with dynamic tile scheduling and ordered, feathered
composition. Neural Engine remains the default based on its sustained speed,
lower memory use, and lower thermal load. The app does not identify a failed
delegate request as accelerated execution.

Android and iOS also provide an optional **High overlap** mode for photos that
show a checkerboard tile pattern. It advances 192 x 192 tiles by 96 pixels and
combines their complete area with cosine weights. It is off by default because
it processes roughly three times as many tiles; normal mode retains the faster
8-pixel edge blending. High overlap uses one accelerator consistently because
mixing GPU and NPU/Neural Engine output across adjacent tiles can introduce a
new periodic pattern.

Build the signed IPA from Linux with `xtool`:

```bash
cd ios
xtool dev build --configuration release --ipa
```

Install and run on a connected, trusted iPhone:

```bash
xtool install --usb xtool/SCUNetDenoiser.ipa
```

Requirements and current validation status are documented in
[`ios/README.md`](ios/README.md).

Android and iOS copy standard source EXIF metadata into each corresponding
batch output. Orientation is normalized because both apps rotate the encoded
pixels before inference; camera, lens, exposure, ISO, capture time, and GPS
metadata are retained when present.
