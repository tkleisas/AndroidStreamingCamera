# Latency Optimization Notes

## What worked

- Reducing VLC/OBS network caching from 400ms to 100ms gave a significant improvement
- VLC command: `vlc rtsp://PHONE_IP:8554/ --network-caching=100`
- OBS: Media Source properties > Network Buffering: 100ms

## Further reduction options

### USB ADB port forwarding (eliminates WiFi jitter)
```
adb forward tcp:8554 tcp:8554
```
Then connect to `rtsp://localhost:8554/` — stream goes over USB instead of WiFi.
Expected: ~50-100ms total latency.

### H.264 encoder tuning (requires RootEncoder fork)
- Force Baseline profile (no B-frames, no reordering latency)
- Lower keyframe interval to 1-2 seconds
- Enable intra-refresh
- RootEncoder 2.7.2 does not expose these MediaCodec settings directly

### Not viable
- **NDI**: Not available on Android
- **SRT**: RootEncoder doesn't support it; FFmpeg bridge adds latency
- **UVC gadget mode**: Requires root + kernel support on most devices
- **Android 14+ DeviceAsWebcam**: OEM-dependent, bypasses the app
