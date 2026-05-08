# StreamCam

Android app that streams camera video and audio over RTSP. Point OBS Studio or VLC at your phone and use it as a wireless camera.

## Features

- **RTSP server** on port 8554 — no external server needed
- **H.264 video + AAC audio** at 1080p 30fps
- **Pinch-to-zoom** and zoom slider
- **Front/back camera** toggle
- **Portrait/landscape** orientation toggle
- **Live client count** display
- **Tap-to-copy** stream URL
- **Keep screen on** while the app is running
- **Exit confirmation** dialog to prevent accidental stream interruption

## Setup

1. Open the project in Android Studio
2. Let Gradle sync (requires JDK 21 — Android Studio's bundled JBR works)
3. Build and deploy to your Android device

### Build from command line

```powershell
.\build_and_deploy_apk.ps1
```

The script uses Android Studio's bundled JDK 21 and deploys to a connected device.

## Usage

1. Launch the app and grant camera + microphone permissions
2. Tap **Start Stream**
3. The RTSP URL is displayed on screen (e.g. `rtsp://192.168.1.5:8554/`) — tap to copy

### Connect from VLC

```
Media > Open Network Stream > rtsp://PHONE_IP:8554/
```

For lower latency:

```bash
vlc rtsp://PHONE_IP:8554/ --network-caching=100 --clock-jitter=0
```

### Connect from OBS

1. Add a **Media Source**
2. Uncheck "Local File"
3. Paste `rtsp://PHONE_IP:8554/`
4. Set Network Buffering to 100ms or lower

### USB streaming (lowest latency)

Connect the phone via USB and forward the RTSP port:

```bash
adb forward tcp:8554 tcp:8554
```

Then connect to `rtsp://localhost:8554/` instead.

## Tech Stack

- Kotlin + Jetpack Compose
- [RootEncoder](https://github.com/pedroSG94/RootEncoder) 2.7.2 — camera capture and H.264/AAC encoding
- [RTSP-Server](https://github.com/pedroSG94/RTSP-Server) 1.4.1 — built-in RTSP server
- Min SDK 24, Target SDK 35

## License

MIT
