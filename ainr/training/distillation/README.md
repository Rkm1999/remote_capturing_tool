# SCUNet Student Distillation

This experiment trains a compact, fully convolutional RGB student to reproduce
the official SCUNet real-PSNR teacher on fixed 192 x 192 patches. Dataset files,
cached teacher targets, and checkpoints are local-only and excluded from Git.

## Data policy

- PolyU `*Real.JPG` files are training inputs. Sources are split by scene before
  patches are generated, preventing crops of one scene from entering both sets.
- SCUNet test images can provide unpaired inputs and teacher-generated targets.
- ISO originals are discovered under the AINR verification and iOS sample paths.
  Comparison montages and backend outputs are deliberately excluded.
- Keep a second untouched benchmark copy outside this cache for final reporting.

PolyU is non-commercial by default. Do not distribute a derived checkpoint
unless the separate permission obtained from the dataset owner covers the
intended distribution and use.

## Setup and run

Use the existing CUDA PyTorch environment or create one from `requirements.txt`:

```bash
cd ainr/training/distillation
./download_data.sh
uv pip install --python /home/ryu/.cache/scunet-int8-venv/bin/python -r requirements.txt
/home/ryu/.cache/scunet-int8-venv/bin/python prepare.py
/home/ryu/.cache/scunet-int8-venv/bin/python train.py
```

Monitor a run from another terminal:

```bash
watch -n 5 /home/ryu/.cache/scunet-int8-venv/bin/python status.py
```

For an end-to-end smoke test, restrict preparation and training:

```bash
python prepare.py --limit 8
python train.py --epochs 1
```

The initial student uses eight 32-channel residual blocks. Selection is not
based only on validation loss: export and benchmark it on the S25 before choosing
whether to make it wider, narrower, or quantization-aware.

## Mobile U-Net student

The selected follow-up architecture is a two-level mobile U-Net with 24 base
channels, additive skip connections, and expansion-2 inverted residual blocks.
It has 384,195 parameters, an approximately 89-pixel receptive field, and an
estimated 2.07 GMAC cost for one 192 x 192 tile. Train and evaluate it with:

```bash
python train_mobile_unet.py
python train_mobile_unet.py \
  --resume runs/mobile_unet_w24/latest.pt \
  --epochs 40 --learning-rate 0.0001
python evaluate_student.py \
  --checkpoint runs/mobile_unet_w24/best.pt \
  --output runs/mobile_unet_w24/evaluation
```

The completed 60-epoch run used an effective batch size of 16 and a mixed
teacher, clean-reference, and edge objective. Its held-out clean-reference PSNR
was 32.93 dB on SIDD, 29.25 dB on NIND, and 37.09 dB on MIDD. The original
full-resolution student scored 33.07, 28.73, and 36.54 dB respectively. Generated
checkpoints and evaluation artifacts remain local-only under `runs/`.

### Sharpness fine-tune

`config_mobile_unet_sharp.yaml` fine-tunes the 60-epoch checkpoint with stronger
edge supervision and a Laplacian high-frequency loss. It uses a separate run so
the base result is retained:

```bash
python train_mobile_unet.py \
  --config config_mobile_unet_sharp.yaml \
  --initial-checkpoint runs/mobile_unet_w24/best.pt \
  --run-name mobile_unet_w24_sharp
```

The best checkpoint was epoch 28. Compared with the base Mobile U-Net, its
clean-reference PSNR changed from 32.93 to 33.50 dB on SIDD, 29.25 to 29.35 dB
on NIND, and 37.09 to 37.11 dB on MIDD. Clean-reference edge L1 also improved on
all three datasets. Use `runs/mobile_unet_w24_sharp/best.pt` for subsequent
export and device testing.

### Denoise-focused continuation

The denoise continuation uses only the scene-separated SIDD, NIND, and MIDD
training cache. It increases clean-reference supervision and gives additional
weight to pixels changed by the SCUNet teacher. The external Sony ISO benchmark
set is excluded from both training and validation and is run only after the
checkpoint has been selected from held-out dataset validation.

```bash
python train_mobile_unet.py \
  --config config_mobile_unet_denoise.yaml \
  --initial-checkpoint runs/mobile_unet_w24_sharp/best.pt \
  --run-name mobile_unet_w24_denoise
```

### Full-resolution detail refinement

The selected denoise checkpoint is extended with a parallel 12-channel
full-resolution convolution branch. Its output layer starts at zero, while all
existing and new parameters remain trainable. Clean-reference edge and
Laplacian losses teach the branch to retain fine texture without removing the
denoising learned by the multi-scale path:

```bash
python train_mobile_unet.py \
  --config config_mobile_unet_detail.yaml \
  --initial-checkpoint runs/mobile_unet_w24_denoise/best.pt \
  --run-name mobile_unet_w24_detail
```

Continue the selected detail model at a lower learning rate until held-out
validation plateaus:

```bash
python train_mobile_unet.py \
  --config config_mobile_unet_detail_continue.yaml \
  --initial-checkpoint runs/mobile_unet_w24_detail/best.pt \
  --run-name mobile_unet_w24_detail_plateau
```

The continuation stops after 20 epochs without a validation-objective
improvement of at least `1e-5`, or after 120 epochs at most. The final external
Sony ISO set remains excluded from training and checkpoint selection.

## Extended datasets

SIDD Medium sRGB and NIND can be downloaded independently. MIDD is 356 GB in
total, so its downloader accepts one sensor archive at a time:

```bash
./download_extended_data.sh sidd
./download_extended_data.sh nind
./download_extended_data.sh midd ISOCELL_3P9
python build_extended_manifest.py
python prepare_extended.py
```

The manifest preserves noisy/clean pairs and scene identities. Future training
must split by `scene` before cropping and sample datasets with explicit weights;
otherwise the much larger MIDD dataset will dominate every epoch.

`prepare_extended.py` caches aligned noisy, clean-reference, and frozen SCUNet
teacher patches. The default per-pair sampling is 8 for SIDD, 8 for NIND, and 2
for MIDD to limit sensor-count imbalance. The cache is independent of the final
student architecture and can be reused after the structure decision.
