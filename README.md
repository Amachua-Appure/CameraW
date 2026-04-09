# CameraW

CameraW is an advanced, professional-grade camera application for Android. It is designed to bypass the standard limitations of the Android camera framework, offering users deep manual control, custom computational photography pipelines, RAW video recording, and dynamic HDR10+ encoding.

---
## Background & Inspiration
This project is inspired by open-source RAW video codecs such as [MLV](https://github.com/ilia3101/LibMLV) and draws direct inspiration from [MotionCam Pro](https://play.google.com/store/apps/details?id=com.motioncam.pro) — which might just be the "final boss" of RAW camera apps, pushing phones to their maximum capabilities. CameraW aims to open-source RAW video storage and enable direct video rendering from RAW sensor data, bypassing Android's built‑in ISPs. This approach allows even low‑end devices to harness RAW capture and push their hardware limits.

---
## Key Features

### Professional Video Recording
* **RAW Video Capture:** Records losslessly compressed RAW_SENSOR video formats directly from the camera sensor into a single .mlv file, allowing users to get the absolute color and dynamic range directly as the camera sees.
* **True HDR10+ Video:** Utilizes a custom GLES pipeline for hardware-accelerated HEVC encoding with dynamic metadata injection (calculating minimum, maximum, and average brightness per frame) mapped to the BT.2020/ST2084 (PQ) color space.
* **High-Fidelity Audio Pipeline:** Bypasses standard Android audio muxers to support storing uncompressed WAV and high-bitrate Opus audio streams synchronously alongside video streams in Pro Video mode.
* **Motion Logging for Stabilization:** Logs raw IMU data (gyroscope and accelerometer) synchronously with video frames to a standard `.gcsv` logging format, enabling advanced post-capture software stabilization via Gyroflow.
* **Hardware Encoder Desqueeze:** Calculates custom sample aspect ratios (SAR) to handle capture resolutions that exceed Android MediaCodec hardware limits. (e.g., A 12MP sensor output exceeding the HEVC 3840x2160 limit is hardware-squished for encoding, allowing standard media players to desqueeze it accurately during playback).

### Computational Photography Pipeline
* **High-Resolution YUV Frame Stacking:** When utilizing unbinned, maximum-resolution sensor outputs (e.g., 50MP modes), the application captures a burst of frames and routes them through a custom C++ Image Signal Processor (ISP) for YUV accumulation and noise reduction. Standard binned resolutions automatically bypass this custom pipeline to utilize optimized native Android hardware processing.
* **RAW Burst Processing:** Captures rapid bursts of RAW sensor data and merges them using the custom ISP to significantly reduce noise and improve fine detail in challenging lighting.
* **High Bit-Depth Output:** Generates incredibly high-dynamic-range still images, outputting as 16-bit PNGs in BT.2020/PQ space or 16-bit DNGs utilizing hardware color correction matrices and lens shading maps.
* **Efficient Formats:** Supports 10-bit AVIF encoding for high-efficiency, wide-color-gamut HLG photography.

### Interface and Manual Controls
* **Hardware-Level Manual Override:** Provides smooth, continuous control over ISO, precise Shutter Speed (dynamically calculated to avoid lighting flicker at given frame rates), White Balance (Kelvin), and physical Focus Distance.
* **Live Histogram:** Displays a real-time, hardware-buffer-derived YUV luma histogram with active clipping indicators for shadow and highlight protection.
* **Declarative UI:** Built entirely with Jetpack Compose, featuring fluid animations, hidden carousels, and a pure-black edge-to-edge layout designed for OLED displays.
* **Integrated Media Viewer:** Includes a built-in gallery utilizing ExoPlayer for HDR video playback and Coil for high-resolution image formats. (Needs more work!)

---

## Design & UI

CameraW follows **Material Design 3** guidelines but does **not** implement Material You (dynamic color). The interface uses a fixed, high-contrast dark theme with pure-black backgrounds. All interactive elements are built with Material 3 composables, ensuring consistent spacing, elevation, and motion patterns across devices.

---

## License

CameraW is free software released under the **GNU General Public License version 3 (GPLv3)**.  
This applies to all original source code as well as any modifications or expansions of the third‑party components listed below.

### Third‑party components and their licenses

- **libmlv** – RAW video capture and MLV container format.  
  The core library files (`MLVDataSource.c`, `MLVDataSource.h`, `MLVFrameUtils.c`, `MLVFrameUtils.h`, `MLVReader.c`, `MLVReader.h`, `MLVWriter.c`, `MLVWriter.h`, `LibMLV.h`) are licensed under the **MIT License** (Copyright 2019 Ilia Sibiryakov).  
  The file `mlv_structs.h` (Magic Lantern Team, 2016) is licensed under the **GNU General Public License version 3 or later**.  
  CameraW expands the original libmlv codec with additional hardware support and extended pixel formats. The resulting combination is distributed under GPLv3.

- **FFmpeg** – Provided by the Bytedeco JavaCV distribution. Used for audio processing, format muxing, and certain transcoding operations.  
  *License: GNU General Public License version 3 (GPLv3)*

- **Google Sans Font** – Used for the application’s typography. The font is licensed under the **SIL Open Font License (OFL)**, and the full `OFL.txt` is included in the root directory of this repository.

---

## Contributing

Contributions, bug reports, and feature requests are highly encouraged. Due to the highly fragmented nature of Android camera hardware implementations across different OEMs, please include your specific device model, chipset, and Android version when opening an issue.
