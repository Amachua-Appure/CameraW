# CameraW

CameraW is an advanced, professional-grade camera application for Android. It is designed to bypass the standard limitations of the Android camera framework, offering users deep manual control, custom computational photography pipelines, RAW video recording, and dynamic HDR10+ encoding.
Features
Professional Video Recording

    RAW Video Capture: Records 14-bit uncompressed or losslessly compressed RAW video formats directly from the camera sensor, maximizing dynamic range for post-production.

    True HDR10+ Video: Utilizes hardware-accelerated HEVC encoding with dynamic metadata injection (calculating minimum, maximum, and average brightness per frame) in the BT.2020/ST2084 color space.

    High-Fidelity Audio: Supports muxing of uncompressed WAV and high-bitrate Opus audio alongside video streams.

    Motion Logging for Stabilization: Logs raw IMU data (gyroscope and accelerometer) synchronously with video frames to a standard logging format, enabling advanced post-capture software stabilization.

    Anamorphic Desqueeze Support: Calculates custom sample aspect ratios to handle anamorphic lenses and bypasses standard hardware encoder resolution limits.

Computational Photography

    Custom Burst Processing: Captures rapid bursts of RAW sensor data and merges them using a custom Image Signal Processor (ISP) to significantly reduce noise and improve detail.

    High Bit-Depth Output: Generates incredibly high-dynamic-range images, outputting as 16-bit PNGs or 14-bit DNGs utilizing hardware color correction matrices and lens shading maps.

    Efficient Formats: Supports 10-bit AVIF encoding for high-efficiency, wide-color-gamut photography.

    Real-Time Accumulation: Capable of real-time frame accumulation and processing for high-resolution (50MP+) sensors.

Interface and Manual Controls

    Complete Manual Override: Provides smooth, continuous control over ISO, precise Shutter Speed (dynamically calculated to avoid lighting flicker), White Balance (Kelvin), and Focus Distance.

    Advanced Focus System: Tracks autofocus states directly from the hardware, featuring an interactive exposure compensation slider and visual focus locking.

    Live Histogram: Displays a real-time, smooth YUV luma histogram with active clipping indicators.

    Modern UI: Built entirely with modern, declarative UI frameworks, featuring fluid animations, gesture controls, and an edge-to-edge layout.

    Integrated Media Viewer: Includes a built-in gallery optimized for HDR video playback and deep zooming of high-resolution image files.
