# SCUNet PSNR mobile student: from-scratch design

## Decision

Build a new approximately 1.96M-parameter, four-level convolutional U-Net based
on LiteDenoiseNet's mobile-proven structure. Train it from random initialization
with the frozen official SCUNet color-real-PSNR model as the high-weight teacher.
Export a separate fixed 192 x 192 deployment graph and retain only its central
160 x 160 region during tiled composition.

Do not continue scaling the current 386k Mobile U-Net. A directly relevant
mobile-denoising ablation found that a 345k model failed to recover fine image
structure, while a 1.96M model provided the needed quality/capacity step. This
matches the failure mode observed in our clean-reference comparisons.

## Why the current approach saturates

1. The current model has 386,166 parameters. It is close to the 345,395-
   parameter low-capacity baseline reported to oversmooth high-frequency
   texture.
2. Depthwise inverted residual blocks reduce arithmetic, but low operation
   count does not guarantee high NPU utilization or enough cross-channel
   capacity for restoration.
3. The training objective mixes L1, edge, Laplacian, clean, and teacher terms.
   That is useful for tuning appearance but is not the direct objective for
   distilling a PSNR model. MSE must dominate when SCUNet PSNR fidelity is the
   goal.
4. Existing cached SCUNet targets were inferred independently on 192 px
   patches. Teacher targets should instead be generated with larger/full-image
   context and cropped afterward, avoiding teacher boundary artifacts.
5. The app retains pixels only 8 px from a tile boundary. That is too little
   reliable context for a four-level restoration U-Net and makes border quality
   disproportionately important.

## Proposed student

Input and output are RGB float tensors in `[0, 1]`. The network predicts a
residual and does not clamp inside the deployment graph.

```text
192x192 RGB
  -> 3x3 conv, 16 channels
  -> LiteBlock(16)
  -> stride-2 3x3 conv, 32 -> LiteBlock(32)
  -> stride-2 3x3 conv, 64 -> LiteBlock(64)
  -> stride-2 3x3 conv, 128 -> LiteBlock(128)
  -> stride-2 3x3 conv, 256 -> LiteBlock(256)
  -> nearest 2x + concatenate encoder skip + 3x3 conv -> LiteBlock(128)
  -> nearest 2x + concatenate encoder skip + 3x3 conv -> LiteBlock(64)
  -> nearest 2x + concatenate encoder skip + 3x3 conv -> LiteBlock(32)
  -> nearest 2x + concatenate encoder skip + 3x3 conv -> LiteBlock(16)
  -> 3x3 conv, RGB residual
  -> input + residual
```

Each LiteBlock is:

```text
identity
  + 3x3 conv: f -> f/2
  + ReLU
  + 3x3 conv: f/2 -> f
  + residual add
  + ReLU
```

Expected properties:

- approximately 1.96M parameters
- approximately 3.9 MB of FP16 weights
- standard convolution, ReLU, nearest resize, concatenation, and addition only
- dimensions remain integral at all four reductions: 192 -> 96 -> 48 -> 24 -> 12
- no attention, normalization, GELU, transposed convolution, pixel shuffle, or
  depthwise convolution

The architecture is MIT-licensed in the released NN Dataset repository. Reuse
must retain its license notice.

## Teacher target preparation

Use the frozen official `scunet_color_real_psnr.pth` model. Do not run SCUNet
separately on each final 192 px training crop.

1. Split SIDD, NIND, MIDD, and any separately permitted PolyU data by scene and
   camera before target generation.
2. Run SCUNet on complete images when memory permits. Otherwise use large
   overlapping regions of at least 512 px with weighted composition.
3. Cache the composed teacher image once, then draw aligned student crops from
   noisy, clean, and teacher images.
4. Balance datasets, cameras, and noise levels explicitly. Do not let the
   dataset with the most cached crops dominate an epoch.
5. Keep `/home/ryu/projects/iso_test_image` outside every training and
   validation manifest. It remains a final qualitative Sony JPEG test.

Add SCUNet's practical synthetic degradation pipeline to clean sRGB images to
expand coverage: Gaussian, Poisson, speckle, JPEG and processed sensor noise,
resizing, shuffled degradation order, and double degradation. Real paired data
must still dominate final fine-tuning because the application consumes
processed camera JPEGs.

## Loss

Start with the published high-alpha restoration distillation objective:

```text
L = 100 * MSE(student, clean)
  + 900 * MSE(student, SCUNet)
  +  50 * L1(student, clean)
```

Compute training loss over the central 160 x 160 output region of each 192 px
input. This matches the proposed 16 px deployment context and prevents discarded
border pixels from steering optimization.

Do not initially add perceptual, GAN, Laplacian, or edge losses. They change the
optimization target and make it unclear whether architecture or loss caused a
result. If the new baseline still lacks detail, run controlled ablations with a
small clean-gradient term, accepting a variant only when full-image clean PSNR
and edge fidelity both improve.

## Training curriculum

1. **Operator gate before training:** export the randomly initialized network
   to LiteRT and Core ML. Verify complete GPU/NPU/Neural Engine execution and
   benchmark one 192 px tile on the S25 and iPhone.
2. **Clean warm-up:** 20-40 epochs against clean targets with MSE plus L1. This
   establishes restoration behavior without inheriting teacher artifacts.
3. **High-alpha distillation:** 200 epochs with Adam, cosine decay, mixed
   precision, batch size chosen for the GPU, and gradient norm clipped to 0.1.
4. **Context curriculum:** pretrain on 192/256 px crops, then expose the fully
   convolutional training model to 512 px crops where memory permits. Because
   deployment remains fixed at 192 px, finish with 30-50 epochs on 192 px inputs
   and the central-160 loss mask.
5. **Plateau fine-tune:** lower the learning rate and stop after 20 validation
   epochs without at least 0.01 dB improvement in clean RGB PSNR.

Larger-crop training is an ablation, not an assumption: retain it only if the
fixed-192 tiled validation result improves. The published model benefited from
contexts up to 1024 px, but its deployment evaluated whole Full HD frames,
whereas this app deliberately limits inference to 192 px tiles.

## Validation and checkpoint selection

Validation must include complete images reconstructed through the exact Android
tiler, not only isolated patches.

Primary checkpoint criterion:

- clean-reference RGB PSNR after full tiled composition

Required guardrails:

- SSIM must not regress materially
- clean-reference gradient/edge error must not regress
- teacher PSNR gap is reported but does not override clean-reference quality
- flat-region noise residual and textured-region detail are reported separately
- tile-boundary error is compared with interior error

Use a 1-pixel outer image crop for PSNR/SSIM comparability with the mobile
denoising benchmark. Keep an untouched test split separate from validation.

## Tiling

Keep the model input fixed at 192 x 192, but change normal composition from an
8 px context/176 px core to a 16 px context/160 px core. A 4000 x 6000 image then
requires about 950 rather than 805 tiles, an approximately 18% increase. The
larger 1.96M network should still be evaluated on device before this becomes the
default.

Retain weighted-overlap composition as a user-selectable artifact fallback.
Never mix GPU and NPU outputs within overlapping contributions unless backend
parity has been measured for the new model.

## Mobile deployment gates

### Android

- Export NCHW PyTorch through a channel-last I/O wrapper using LiteRT Torch.
- Produce a fixed `[1, 192, 192, 3]` FP16 model first.
- Remove graph-internal clipping when it prevents full delegation; clamp during
  byte conversion/composition.
- Require one delegated graph with no CPU fallback on Snapdragon.
- Test Qualcomm, MediaTek, and Samsung compilers before claiming support.
- Measure first compilation, warm single-backend, thermal sustained, and
  GPU+NPU performance separately.

### iOS

- Convert the same checkpoint to an FP16 Core ML `mlprogram` with fixed shape.
- Compile once and retain the compiled artifact.
- Test `CPU_AND_NE`, `CPU_AND_GPU`, and `ALL` on the physical iPhone.
- Consider W8A8 or palettization only after the FP16 quality baseline passes;
  compression must be calibrated or fine-tuned with representative data.

## Experiments to run

| ID | Architecture | Distillation | Purpose |
| --- | --- | --- | --- |
| A | base width 12, ~0.35M | high-alpha | reproduce the capacity floor |
| B | base width 16, ~1.96M | none | isolate capacity improvement |
| C | base width 16, ~1.96M | high-alpha | proposed production candidate |
| D | model C | larger-crop pretraining | test context curriculum for fixed tiles |
| E | model C | central-160 loss | verify train/deploy tiling alignment |

Do not train all variants to completion immediately. Train A-C for a short,
fixed budget, confirm the expected ranking, then fully train C and run D/E as
controlled ablations.

## Sources

- [SCUNet paper: Practical Blind Image Denoising via Swin-Conv-UNet and Data Synthesis](https://arxiv.org/abs/2203.13278)
- [Official SCUNet implementation](https://github.com/cszn/SCUNet)
- [Real Image Denoising with Knowledge Distillation for High-Performance Mobile NPUs](https://openaccess.thecvf.com/content/CVPR2026W/MAI/papers/Kayani_Real_Image_Denoising_with_Knowledge_Distillation_for_High-Performance_Mobile_NPUs_CVPRW_2026_paper.pdf)
- [Released LiteDenoiseNet architecture and training statistics](https://github.com/ABrain-One/NN-Dataset)
- [NAFNet: Simple Baselines for Image Restoration](https://arxiv.org/abs/2204.04676)
- [LiteRT Torch converter](https://github.com/google-ai-edge/ai-edge-torch)
- [Core ML optimization overview](https://apple.github.io/coremltools/docs-guides/source/opt-overview.html)
- [Core ML fixed and enumerated shape guidance](https://apple.github.io/coremltools/docs-guides/source/faqs.html)
