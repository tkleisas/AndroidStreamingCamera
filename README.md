# StreamCam

Android app that streams camera video and audio over RTSP. Point OBS Studio or VLC at your phone and use it as a wireless camera. Includes recording, video trimming, and full DJI Osmo Mobile 7 gimbal control — including from inside OBS via a dock panel.

## Features

- **RTSP server** on port 8554 — no external server needed
- **H.264 video + AAC audio** at 1080p 30fps
- **Recording** to MP4 with in-recording marker stamps
- **Video trimmer** — trim any video with A/B points; reverse trimming when B < A
- **Pinch-to-zoom** and zoom slider
- **Multi-lens** back camera switching
- **Front/back camera** toggle
- **Portrait/landscape** orientation toggle
- **DJI Osmo Mobile 7 gimbal control over BLE** — pan/tilt joystick and recenter, reverse-engineered DUML protocol (see `OM7_BLE_PROTOCOL.md`)
- **OBS Studio dock panel** — phone-hosted web page (port 8556) for gimbal control inside OBS
- **Live client count** display
- **Tap-to-copy** stream URL
- **Keep screen on** while the app is running
- **Exit confirmation** dialog to prevent accidental stream interruption
- **YOLO v8 object detection + ActiveTrack** *(currently disabled — the ONNX model is not bundled; see below)*

## Setup

1. Open the project in Android Studio
2. Let Gradle sync (requires JDK 21 — Android Studio's bundled JBR works)
3. Build and deploy to your Android device

### Build from command line

```powershell
.\build_and_deploy_apk.ps1
```

Or on Linux/macOS:

```bash
./gradlew assembleDebug
```

## Usage

### Streaming

1. Launch the app and grant camera + microphone permissions
2. Tap **Start Stream**
3. The RTSP URL is displayed on screen (e.g. `rtsp://192.168.1.5:8554/`) — tap to copy

#### Connect from VLC

```
Media > Open Network Stream > rtsp://PHONE_IP:8554/
```

For lower latency:

```bash
vlc rtsp://PHONE_IP:8554/ --network-caching=100 --clock-jitter=0
```

#### Connect from OBS

1. Add a **Media Source**
2. Uncheck "Local File"
3. Paste `rtsp://PHONE_IP:8554/`
4. Set Network Buffering to 100ms or lower

#### USB streaming (lowest latency)

Connect the phone via USB and forward the RTSP port:

```bash
adb forward tcp:8554 tcp:8554
```

Then connect to `rtsp://localhost:8554/` instead.

### Recording and Trimming

- Tap **Rec** to start/stop recording — saved to `DCIM/StreamCam/`
- Tap **Mark** during recording to stamp a timestamp marker
- Tap **Trim** to open the recording picker — choose any video to trim:
  - Set **A** and **B** points on the seekbar
  - Tap recorded marker timestamps to jump to them
  - **Trim**: cuts from A→B (stream copy, fast)
  - **Reverse**: cuts from B→A in reverse (re-encodes)
  - **Browse all files**: pick any video from the device
- Markers are saved as `<video>.markers.json` and loaded in the trimmer

### Gimbal Control (DJI Osmo Mobile 7)

- Grant Bluetooth permissions, power on the gimbal, tap **Gimbal** to scan/connect — the button turns green ("Center") when connected
- **Hold the D-pad buttons** (Left/Right/Up/Down) to pan/tilt — velocity commands are streamed at 20 Hz while held
- Tap **Center** to recenter the gimbal
- The **V1** button is a leftover protocol-test variant cycler — leave it on V1 (the verified working speed-command layout)

Protocol details and the full reverse-engineering write-up: `OM7_BLE_PROTOCOL.md`,
`docs/MIMO_NATIVE_ANALYSIS.md`, `docs/dji_cmd_base_req_table.txt`.

### Object Detection and Gimbal Tracking *(currently disabled)*

YOLO v8 detection + ActiveTrack are implemented but **disabled in current builds**: the ONNX
model is not bundled in the repo. Without it the Detect button is hidden and click-to-track in
the dock is unavailable. Manual gimbal control (joystick, recenter) works regardless.

To re-enable later: run `export_yolo_model.py`, place the resulting `.onnx` in
`app/src/main/assets/`, then tap **Detect** and tap a bounding box to track it. (Note: the
gimbal's own ActiveTrack path — `0x23/0x09` box streaming — is implemented and was
hardware-verified; only the model is missing.)

### OBS integration

**Video**: add a Media Source pointing at `rtsp://PHONE_IP:8554/` (see above).

**Gimbal control dock**: the phone hosts a small HTTP server (port **8556**) with a web page
for gimbal control, designed to be docked inside OBS Studio:

1. In OBS: **View → Docks → Custom Browser Docks…**
2. Name it `Gimbal`, URL `http://PHONE_IP:8556/` (the URL is shown in the app's status bar while streaming)

The dock page offers:

- **Live preview** (~4 fps JPEG) with detection boxes overlaid — click a box to start ActiveTrack, click again to stop *(requires the YOLO model — currently disabled)*
- **D-pad** pan/tilt (press-and-hold) plus arrow/WASD keys
- **Speed slider** (10–120 °/s), **Center** and **Stop Track** buttons

Notes:

- Full-motion video still comes from the RTSP Media Source (`rtsp://PHONE_IP:8554/`); the dock is control + a low-fps preview only
- The preview requires the StreamCam app to be in the foreground (frames are captured from the camera preview view); otherwise the dock shows "preview unavailable"
- The dock server is **LAN-only and unauthenticated** — same trust model as the RTSP server. Don't expose it beyond your local network

## Tech Stack

- Kotlin + Jetpack Compose
- [RootEncoder](https://github.com/pedroSG94/RootEncoder) 2.7.2 — camera capture and H.264/AAC encoding
- [RTSP-Server](https://github.com/pedroSG94/RTSP-Server) 1.4.1 — built-in RTSP server
- [Media3 ExoPlayer](https://developer.android.com/media/media3/exoplayer) — video playback in trimmer
- **YOLO v8 object detection + ActiveTrack** — [ONNX Runtime](https://onnxruntime.ai/) *(model not bundled; detection currently disabled)*
- Hand-rolled HTTP server (`ServerSocket`) for the OBS dock — no extra dependencies
- Android MediaExtractor / MediaMuxer / MediaCodec — native video trimming
- Min SDK 24, Target SDK 35

## License

MIT
