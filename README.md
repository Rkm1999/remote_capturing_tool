# Remote Capture

Remote Capture is a native Android still-photography tool for compatible Sony
cameras connected through the camera's Wi-Fi access point. It uses an
independently written implementation of Sony's legacy ScalarWebAPI/Camera Remote
API and exposes only capabilities reported by the connected camera.

## Current Features

- Guided connection through Android Wi-Fi settings
- SSDP discovery and Sony device-description parsing
- Network-bound local HTTP/JSON-RPC transport
- Live-view JPEG stream decoding
- Standard and High live-view quality selection when advertised by the camera
- Photo capture with long-exposure recovery
- Continuous camera-body shutter monitoring with queued, sequential JPEG imports
- Negotiated `getEvent` v1.2 continuous events with v1.0 compatibility fallback
- Physical burst import and capability-gated press-and-hold app bursts
- An `IMPORTING` status while one or more body-shutter JPEGs are pending
- Preview-first imports with an independent, cancellable Original queue and
  capability-gated Contents Transfer recovery
- Latest-capture control plus an accessible capture-history viewer
- Live-view 3D LUT preview and separate full-resolution derivatives with EXIF preservation
- Import, preview, strength adjustment, and application of 3D `.cube` LUT files
- Optional phone-location geotagging in the EXIF of every saved JPEG
- Progressive Live ND averaging with Burst and Single capture strategies
- Progressive Live Composite brighter-than-base accumulation
- Deliberate panorama sessions with app-shutter or camera-body-shutter frames,
  live registration, feathered blending, Finish, and final JPEG saving
- Capability-driven capture type, drive, exposure, aperture, shutter, ISO, and EV controls
- Immediate Wi-Fi-loss handling and exportable local diagnostics
- Preview and capability polling pause while the app is backgrounded

The app never requests general photo-library access. Captured JPEGs are written
to `Pictures/Sony Remote` using MediaStore. Diagnostic logs contain event names,
errors, model/app versions, and byte/API counts; they do not contain images,
Wi-Fi passwords, or analytics identifiers.

## Compatibility

Remote Capture requires Android 10 or newer, Wi-Fi, and a Sony camera application
that exposes the legacy ScalarWebAPI camera service. Compatibility is
capability-driven: unavailable camera APIs result in unavailable or read-only UI
controls rather than assumed support.

**Hardware tested for v0.1 beta:**

- Samsung Galaxy S25 (`SM-S931W`), Android 16
- Sony Alpha A6300 (`ILCE-6300`)
- Sony Smart Remote Embedded / legacy Smart Remote Control camera application
- Still capture, physical shutter import, continuous shooting, exposure controls,
  remote zoom, Standard/High live view, Live ND, Live Composite, Panorama, LUT
  preview/import, and MediaStore saving

Other Sony models using the same legacy service may work but are untested. Sony's
newer Camera Remote SDK cameras are not automatically compatible with this app.
Feature availability also varies with camera firmware, installed camera app,
lens, exposure mode, drive mode, and current camera state.

## Camera Setup

1. On the camera, open its application list or remote-control function.
2. Start `Smart Remote Embedded` or an already-installed `Smart Remote Control`.
3. On Android, join the `DIRECT-...` network and enter the password shown by the camera.
4. Return to Remote Capture and tap **Find camera**.

## Privacy And Files

Captured and processed JPEGs are saved to `Pictures/Sony Remote`. Geotagging is
off by default. Enabling it requests Android precise-location permission and
adds the phone's current latitude, longitude, and available altitude to saved
image EXIF. Location is not sent to a server or written to diagnostic logs.

The app requests no general photo-library permission, includes no analytics, and
communicates only with the camera on its private Wi-Fi network. Imported `.cube`
files are parsed locally.

After the app reports **Connected**, it keeps a camera-event long poll active.
Each completed body-shutter JPEG is placed in an in-memory queue, then downloaded
and saved in order while event polling continues. The live-view corner shows
`IMPORTING` while one or more JPEGs are pending, and capture and setting controls
remain disabled until the pending import completes. No phone-side shutter action
is required for an ordinary photo.

For a single shot with an Original postview URL, the filmstrip first shows a
recent live-view image labeled `Live view`. This is explicitly a placeholder,
not the captured JPEG, and cannot be opened in the LUT editor. The Original URL
is retained and downloaded only when **Import original** is selected. If Sony
offers only a 2M postview, that JPEG is saved immediately and labeled
`Preview only`; Original import remains unavailable unless a usable Contents
Transfer record can be identified.

When Drive is `Continuous` and the camera advertises `startContShooting`, the
Photo shutter becomes a press-and-hold control. Release stops the burst, and a
five-second watchdog stops prolonged or lost touch input. Every reported
postview is imported in camera order.

Burst Live ND uses completed download results rather than reported URL counts.
It calibrates burst duration from monotonic start/stop command timing, measured
stop latency, and unique successfully imported frames. Estimates are kept
separately for camera model, burst speed, and shutter speed. Targets of 2, 4, 8,
16, and 32 accepted frames are supported; extra or rejected source photographs
remain saved. Bounded failures or timeouts switch to sequential Live ND instead
of waiting indefinitely. A burst-speed setting is shown only when the camera
advertises `getAvailableContShootingSpeed`, and is writable only when it also
advertises `setContShootingSpeed`.

Sony ended PlayMemories Camera Apps downloads on August 31, 2025. Cameras with
only the factory `Smart Remote Embedded` app can still provide baseline remote
operation, but may not advertise aperture, shutter, ISO, or original-size JPEG
transfer. The UI queries `getAvailableApiList` and every available-value method;
it does not assume those controls exist.

## Camera-Body Shutter Panorama

1. Select **Panorama** in the Android app.
2. Tap the center **Start** control once. This arms the panorama, switches the
   A6300 Drive setting to `Single` when the camera exposes that control, and
   requests the camera's `Original` postview when that capability is available.
3. Take each source frame with the camera body's shutter. Keep 30-50% overlap
   between adjacent views and wait for `IMPORTING` to clear and the `PANO` frame
   count to advance before taking the next frame.
4. After at least two accepted frames, tap **Finish** (the check control) on the
   phone to render and save the final panorama from the retained source JPEGs.

The stitched preview remains in the main viewer while a live-view picture-in-
picture stays visible at the lower right. Every accepted or rejected source is
saved independently. Finish plans the natural full-resolution output and scales
it only when the device's measured memory/storage budget requires that; Cancel
keeps sources and saves no final derivative.

Selecting Panorama alone does not arm it; body-shutter photos taken before
**Start** are imported as ordinary `Camera` items. A frame that cannot be aligned
is also retained as an ordinary `Camera` photo instead of being silently deleted.

## Build

Requirements:

- JDK 17
- Android SDK platform/build tools 36

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease
ANDROID_SERIAL=<device-serial> ./gradlew connectedDebugAndroidTest
```

The debug APK is produced at `app/build/outputs/apk/debug/app-debug.apk`.

The `v0.1-beta` GitHub prerelease provides a debug-key-signed APK for direct
testing. It is not a production-signed Play Store artifact. Android may require
permission to install apps from the browser or file manager used to open it.

`assembleRelease` verifies the minified production build but intentionally
produces an unsigned APK. Configure an Android signing key outside the
repository before installing or distributing a release artifact; do not commit
keystores or signing passwords.

## Architecture

- `model`: camera-family `CameraBackend` contract for future protocol adapters
- `protocol`: legacy ScalarWebAPI JSON-RPC, SSDP/XML parsing, and live-view framing
- `network`: private-endpoint policy and Android `Network`-bound UDP/HTTP
- `data`: camera session, MediaStore persistence, and diagnostic logging
- `processing`: 3D LUT interpolation and OpenCV-backed alignment, compositing,
  feature matching, homography, and panorama blending
- `ui`: Kotlin/Compose connection and control workflow

Every SSDP-provided URL must use cleartext HTTP on a numeric private or
link-local address. Service, live-view, and postview URLs must remain on the
same camera host. Redirects are rejected.

## Protocol And Distribution

The A6300 is not supported by Sony's current Camera Remote SDK, and that SDK is
currently a desktop-host SDK rather than an Android/iOS SDK. This project uses
the discontinued legacy interface for private testing. Do not publish it to an
app store until Sony confirms the intended distribution is permitted under the
surviving legacy Camera Remote API EULA. Do not bundle Sony SDK binaries,
sample source, documentation, logos, or other Sony assets.

Relevant sources:

- [Sony A6300 mobile connection instructions](https://support.d-imaging.sony.co.jp/www/cscs/pmm/page2.php?abil=31100&area=gb&lang=en&mdl=ILCE-6300)
- [Smart Remote Embedded control limitations](https://www.sony.com/electronics/support/articles/00100204)
- [PlayMemories Camera Apps shutdown](https://www.sony.com/electronics/support/compact-cameras-dsc-hx-series/dsc-hx400v/articles/00289030)
- [Current Camera Remote SDK](https://support.d-imaging.sony.co.jp/app/sdk/en/index.html)
- [Legacy Camera Remote API EULA](https://developer.sony.com/eula/camera-remote-api-end-user-license-agreement)
- [Archived Sony API reference v2.40](https://be-bright.github.io/assets/documents/Sony_CameraRemoteAPIbeta_API-Reference_v2.40.pdf) (third-party archive)
- [Android local-network permission](https://developer.android.com/privacy-and-security/local-network-permission)

## Limits

- A physical A6300 is required to validate camera-app-specific behavior.
- RAW files and movie files remain on the SD card.
- Still-image size/quality cannot be changed through this A6300 API group.
- Movie capture is intentionally out of scope and has no controls in the Android MVP.
- Physical-shutter monitoring remains active while the camera session and Android
  app process are alive. Android may terminate a background process; reopen and
  reconnect the app for guaranteed monitoring after that happens.
- Event polling and JPEG transfer run independently, but imports are processed one
  at a time through a bounded in-memory queue. `IMPORTING` reports pending transfer,
  not the camera's half-press or exposure state.
- Body-shutter notification arrives after capture, so half-press is handled by
  the camera and is not exposed to Android. Some delay is unavoidable while the
  camera finishes the JPEG, reports its URL, and the app transfers and saves it.
- Sequential computational capture has camera-save and transfer latency, so Live
  ND and Live Composite update per exposure rather than at video rate.
- Live ND/Composite temporarily use the required capture settings and Panorama
  requests Original sources when available. Postview size, Drive, burst speed,
  and live-view state are restored after completion, cancellation, or failure.
- App-triggered bursts are duration-controlled rather than count-controlled. The
  A6300 has no arbitrary frame-count parameter, so the exact count varies with
  exposure, autofocus, speed, and buffer state. Release, lifecycle cancellation,
  disconnect, teardown, and a five-second watchdog all request a stop.
- Burst Live ND uses short v1.2 batches until it has the requested number of valid
  frames. Extra or rejected postviews remain saved instead of being deleted.
  Single sequential capture remains available as a fallback.
- Live Composite remains sequential and Panorama forces Single Drive; neither
  mode uses continuous bursts.
- Panorama uses planar homographies. Preview memory and frame count are bounded;
  final rendering reads one Original source at a time and is constrained by
  measured heap, storage, pixel, and edge limits. Large parallax, low-detail
  overlap, or very long sweeps are rejected.
- Camera-body panorama source JPEGs are saved to MediaStore before alignment.
  They remain in `Pictures/Sony Remote` if alignment fails or the panorama is
  discarded; Cancel prevents only the final stitched JPEG from being saved.
- `Original` transfer is requested only when reported. For 2M-only events, the
  action is enabled only when a retained URL or advertised Contents Transfer
  record can supply the Original; otherwise the UI states `Preview only`.
- Android 17 local-network permission changes are outside the current Android
  10-16 target and must be handled before targeting API 37.

Open-source dependency notices are recorded in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
