# Galaxy S25 24 MP accelerator comparison

## Test method

- Device: Galaxy S25 SM-S931W, Snapdragon 8 Elite / SM8750
- App: corrected SCUNet Denoiser v0.2 debug build
- Input: Sony A6300 `DSC00005.JPG`, ISO 51200
- Input SHA-256: `125dc409952301b5b1beb051689f67be3cad54d3a86dedee0ccab84c4bceedb8`
- Decoded dimensions: 4000 x 6000 (24 MP)
- Model: SCUNet color real PSNR, FP16-weight LiteRT conversion
- Tiling: 192 x 192, 8 px padding per side, 176 x 176 core, 805 tiles
- Timing: app end to end, including decode, tile preparation, inference,
  feathering, JPEG encoding at quality 96, and preview decoding
- Setup: accelerator sessions were warm; JIT compilation and GPU session
  creation are excluded
- Thermal control: each run began at Android thermal level 1 after cooling

## Results

| Configuration | End-to-end time | Throughput | Relative to GPU |
| --- | ---: | ---: | ---: |
| GPU | 116.079 s | 0.207 MP/s | baseline |
| NPU | 98.005 s | 0.245 MP/s | 1.184x; 15.6% faster |
| GPU + NPU | 72.176 s | 0.333 MP/s | 1.608x; 37.8% faster |

Dual mode was also 1.358x, or 26.4%, faster than NPU alone. All three sustained
runs reached thermal level 3 by completion, so these numbers represent the
real current app workload rather than short inference-only probes.

## Backend output consistency

The three full-resolution JPEGs are retained in this directory. They are not
byte-identical because GPU and NPU arithmetic differ slightly and dual mode
uses one feathered accelerator boundary. Pairwise full-image PSNR is high:

| Pair | PSNR |
| --- | ---: |
| GPU vs NPU | 47.404 dB |
| GPU vs GPU + NPU | 50.791 dB |
| NPU vs GPU + NPU | 50.060 dB |

`24mp_backend_output_detail.jpg` shows the same ISO 51200 crop from the source
and all three app outputs. Accelerator choice changes speed substantially but
does not produce a meaningful visible quality difference in this test.

## ISO 1600-51200 quality sheets

The three `ISO1600-51200_*` sheets compare matched 100% crops from the original
Sony JPEGs with the official SCUNet color real PSNR output. These use the same
official model weights converted for the Android app. The sheets use lossless
host inference outputs so JPEG re-encoding and backend rounding do not obscure
the model's denoising behavior.

- `ISO1600-51200_dark_background_original-vs-scunet.jpg`: flat dark-region
  chroma and luminance noise
- `ISO1600-51200_fine_object_detail_original-vs-scunet.jpg`: edge and surface
  detail on the cube
- `ISO1600-51200_label_texture_original-vs-scunet.jpg`: fine printed texture
  and aggressive smoothing at high ISO

There is no clean reference exposure for this set, so PSNR or SSIM against a
ground truth image would not be valid. The sheets are a visual quality test,
not an objective restoration score.

## Dynamic dual scheduling follow-up

The fixed horizontal split was replaced with a shared tile scheduler. NPU now
claims raster-order tiles from the start of the image while GPU claims tiles
from the end. Completed outputs are restored to raster order before the same
tile feathering is applied. This permits a boundary inside a row and avoids
leaving an accelerator idle for an entire final row.

The updated debug build was tested on the same Galaxy S25 and input after a
fresh install. On-device Qualcomm JIT took 156.937 seconds, GPU setup took
1.162 seconds, and the following thermally hot inference stage took 55.403
seconds:

| Worker | Tiles | Mean inference | Worker wall time |
| --- | ---: | ---: | ---: |
| GPU | 442 | 116.983 ms | 52.577 s |
| NPU | 363 | 133.927 ms | 55.403 s |

Concurrent inference overlap was 1.811x. The app completed and encoded a valid
4000 x 6000 quality-96 JPEG without running out of memory. This run began its
inference stage immediately after JIT and reached thermal level 3, so it is not
a replacement for the thermal-level-1 table above. It does demonstrate that
the dynamic scheduler adapts when concurrent GPU/NPU speed reverses: GPU was
faster in this run and automatically received more work.

Output consistency remained within the existing backend variation:

| Reference | Dynamic dual PSNR |
| --- | ---: |
| GPU only | 50.934 dB |
| NPU only | 49.934 dB |
| Earlier fixed dual | 47.856 dB |

The lower comparison against fixed dual is expected because the two versions
assign opposite image regions to GPU and NPU. Direct checks at all three
segments of the new L-shaped handoff found no local gradient spike.
