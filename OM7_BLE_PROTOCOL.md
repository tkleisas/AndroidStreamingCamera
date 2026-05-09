# DJI Osmo Mobile 7 — BLE Protocol Notes

Reverse-engineered from BLE HCI snoop captures of the official DJI Mimo app talking to an OM7. Captures done on Android via Developer Options "Bluetooth HCI snoop log" + `adb bugreport`.

## Transport

- **Service**: `0000fff0-0000-1000-8000-00805f9b34fb`
- **Write characteristic** (FFF5): `0000fff5-0000-1000-8000-00805f9b34fb` — `WRITE_NO_RESPONSE`. On the test device this is GATT handle `0x002b`.
- **Notify characteristic** (FFF4): `0000fff4-0000-1000-8000-00805f9b34fb`. GATT handle `0x0028`. CCCD at `0x0029`.
- The OM7 advertises as `OM7-G<serial>` (e.g. `OM7-G0235F`). Scan filter on name prefix `"OM"` works.
- Mimo communicates with the OM7 **via BLE only** — no WiFi link. All ActiveTrack control is on FFF5.

## Frame format (DUML over BLE)

All frames written to FFF5 follow the standard DJI DUML v1 layout:

```
offset  size  field
 0      1     SOF = 0x55
 1      1     length_lo
 2      1     (version << 2) | (length_hi & 0x03)   ; version=1 for OM7
 3      1     CRC8(bytes[0..2])
 4      1     sender         ; app sends with sender=0x02
 5      1     receiver       ; 0x04 = gimbal, 0x27 = gimbal subsystem (depends on cmd)
 6      2     seq (LE) — app increments per frame
 8      1     flags          ; 0x40 = request, no-ack
 9      1     cmd_set
10      1     cmd_id
11..N-3       payload
N-2     2     CRC16(bytes[4..N-3]) (LE)
```

`length` = total frame size in bytes.

### CRC8 (header)

CRC-8/MAXIM variant with custom init. Reflected (LSB-first) implementation:

```
init = 0x77
poly = 0x8C   (= 0x31 reflected)
```

The DJI tooling community sometimes documents this as MSB-first poly 0x5E — that is **wrong** for OM7. Verified: `crc8(0x55, 0x15, 0x04) = 0xA9` matches Mimo's frames byte-for-byte.

### CRC16 (body)

CRC-16/ARC (reflected). Init is non-standard:

```
init = 0xDF0C
poly = 0xA001  (= 0x8005 reflected)
```

## Address space

- `0x02` — App / mobile device (us)
- `0x03` — Camera/Vision subsystem (sees `cmd_set=0x03` traffic)
- `0x04` — Gimbal (general)
- `0x27` — Gimbal motor / stabilizer subsystem (handles heartbeats, sees `cmd_set=0x00` traffic)
- `0xF0` — Some "Sky" / pairing-mode subsystem (sees `cmd_set=0x00 cmd_id=0x2B`)

Many commands are sent **twice**, once to `0x04` and once to `0x27` (different subsystems on the same device).

## Known commands

### What we use today

| cmd_set | cmd_id | Receiver | Payload | Meaning |
|---------|--------|----------|---------|---------|
| `0x00`  | `0x00` | `0x27`   | `02 00` | Heartbeat (sent ~1Hz to keep gimbal awake) |
| `0x04`  | `0x4C` | `0x04`   | `01 00` | **ActiveTrack: enable** |
| `0x04`  | `0x4C` | `0x04`   | `02 00` | **ActiveTrack: disable** |
| `0x04`  | `0x0F` | `0x04`   | `82 01 00` | **ActiveTrack: start tracking** (sent once before stream begins) |
| `0x04`  | `0x0F` | `0x04`   | `82 01 FF` | **ActiveTrack: stop tracking** |
| `0x23`  | `0x09` | `0x04`   | 68 bytes (see below) | **ActiveTrack: target bounding-box stream** (sent at ~10Hz) |

### ActiveTrack target frame (`0x23 / 0x09`)

68-byte payload:

```
offset  size  field
 0      4     timestamp counter (uint32 LE, microseconds-ish)
 4      4     frame counter (uint32 LE)
 8     12     metadata header (constant): D0 02 00 05 02 02 01 05 34 00 00 00
20      4     box_cx (float32 LE)   ; normalized [0..1], CENTER x of box
24      4     box_cy (float32 LE)   ; normalized [0..1], CENTER y of box
28      4     box_w  (float32 LE)   ; normalized width
32      4     box_h  (float32 LE)   ; normalized height
36     32     zero padding
```

The bounding box is the location of the tracked object in the image plane, normalized to the camera frame, expressed as **center x, center y, width, height** (NOT top-left + size — verified empirically: Mimo's first frame was (0.28, 0.45, 0.57, 0.83), which only fits inside [0,1] when interpreted as cx/cy/w/h). The gimbal's onboard tracker reads these and runs its own pan/tilt closed loop to keep the box centered. **No PID or rate control is needed on the host** — just feed the gimbal box coordinates from your detector at ~10Hz.

The metadata bytes 8–19 are constant in every frame Mimo sends. Likely a sub-protocol identifier; we replay them verbatim. Bytes 36–67 are always zero in Mimo's traffic; possibly room for additional tracker hints (orientation, depth, target ID) that Mimo doesn't populate in standard ActiveTrack.

### Phone IMU stream (`0x04 / 0x52`)

40-byte payload sent at ~10Hz, all values are little-endian floats:

```
offset  size  field
 0     16     quaternion (qx, qy, qz, qw)   ; norm = 1.0
16     12     accelerometer (ax, ay, az) m/s²  ; norm ≈ 9.81 at rest
28     12     gyroscope (gx, gy, gz) rad/s
```

Mimo streams the phone's IMU here so the gimbal can stabilize relative to the phone's orientation. We can ignore this stream for ActiveTrack — the gimbal works correctly without it (it falls back to its own IMU). If we ever needed "follow phone tilt" mode, this is the channel.

### Other commands seen but not used

| cmd_set | cmd_id | Notes |
|---------|--------|-------|
| `0x04`  | `0x07` | Calibration step indicator. Payloads `0x0001`, `0x0015`, `0x0000`, `0xFF00`. `0x0015` (`pl=1500`) appears to be the **calibration trigger** — sent ~37s before the gimbal begins its full-range sweep (Mimo presumably shows instructions during the gap). |
| `0x04`  | `0x10` | Generic status query (1-byte payloads). Sent at ~5Hz. |
| `0x04`  | `0x12` | Configuration push (34-byte payloads). Sent in bursts during init. |
| `0x04`  | `0x50` | Status push, varying short payloads. |
| `0x04`  | `0x57` | Position telemetry (8-byte). Bytes 0–1 = pitch×10 (s16 LE), bytes 2–3 = yaw×10. Useful for closed-loop verification. |
| `0x04`  | `0x68` | Periodic config (`32 08 00 00 80 00 00 00 00 00`). |
| `0x04`  | `0x6F` | Telemetry. |
| `0x04`  | `0x77` | Telemetry. |
| `0x05`  | `0x06` | Long-form gimbal state (53-byte frame). |
| `0xEE`  | `0x02` | Periodic 7-byte float-bearing message (contains float 1.0). Possibly a clock/sync signal. |

### Recenter command (CONFIRMED working)

`cmd_set=0x04 cmd_id=0x14` is the **absolute-angle move** command and it DOES work on OM7. Mimo uses it for recenter:

```
recv=0x04  set=0x04  id=0x14  payload = 8 bytes:
  bytes 0-1: pitch (s16 LE, ×10 deg)
  bytes 2-3: roll  (s16 LE, ×10 deg)
  bytes 4-5: yaw   (s16 LE, ×10 deg)
  byte 6:    control (0x0F = absolute + all axes enabled)
  byte 7:    duration in tenths of a second (0x0A = 1.0s)
```

Mimo's recenter is `pl=0000000000000F0A` (all angles zero, all axes, 1s).

**Why earlier attempts failed**: we tried this command before implementing notification ACKs. The OM7 had already entered its watchdog-triggered "comms is dead" state and was ignoring all motor commands. After fixing the ACK loop (responding to the gimbal's flags=`0x40` notifications with flags=`0x80` echoes), this command works.

### Commands likely supported but unverified

- `cmd_set=0x04 cmd_id=0x0C` (speed control on legacy Osmo Mobile) — never seen in Mimo's traffic on OM7. Untested. May or may not work; the safe path is to use `0x14` (absolute) instead, which is what Mimo uses.

## Authentication / handshake

We saw encrypted-looking writes (`83 00 1C DD D1 E0 6E 9F C1 32 E0 E0 F8 DD 3C 6F AD CF`) on a *separate* BLE connection at GATT handle `0x0063` near connection time. Initial concern: maybe the OM7 needs a session-key exchange before it accepts commands.

**It does not.** That separate connection is to a different device entirely (the test phone has a smartwatch paired that uses the same handle range). On the OM7's own connection (handle `0x0203` in our captures), no encrypted handshake happens — Mimo just opens FFF5/FFF4 and starts sending DUML.

## Init sequence (what Mimo does after connect)

In the first ~3 seconds after the BLE connection is established, Mimo sends a flurry of init commands. From our capture, in order:

```
recv=0x04  set=0x04 id=0x12  pl=77FE...D6...   (config)
recv=0x04  set=0x04 id=0x10  pl=0A             (status query)
recv=0x04  set=0x04 id=0x12  pl=66FE...10...   (config)
recv=0x04  set=0x04 id=0x12  pl=66FE...18...   (config)
recv=0x27  set=0x12 id=0x0C  pl=               (subsystem command, empty)
recv=0x04  set=0x04 id=0x65  pl=010100         (mode set)
recv=0x04  set=0x04 id=0x12  pl=66FE...8000...18  (config sweep)
recv=0x27  set=0x12 id=0x07  pl=               (subsystem command, empty)
recv=0x27  set=0x00 id=0x34  pl=0101000000000000   (common param set)
recv=0x27  set=0x00 id=0x4F  pl=040000000000000000 (config)
recv=0x04  set=0x04 id=0x1E  pl=71FF0400       (mode/limit)
```

The gimbal accepts ActiveTrack commands without all of this — heartbeat + the ActiveTrack enable/start/stream alone is enough on a freshly-connected OM7 in our testing. Replaying the full init sequence is the safer option if behavior is flaky.

## Implementation notes for our app

- **`DumlProtocol.kt`** — frame builder with CRC8/CRC16, plus helpers `activeTrackEnable / activeTrackDisable / activeTrackStart / activeTrackStop / activeTrackBox(x, y, w, h)` and `heartbeat()`.
- **`GimbalController.kt`** — BLE scan/connect/MTU/services discovery on FFF0, with a 2s heartbeat coroutine and a 10Hz ActiveTrack streaming coroutine. `startActiveTrack(x, y, w, h)` enables tracking and starts the stream; `updateTrackTarget` updates the box atomically; `stopActiveTrack` tears it all down.
- **`MainActivity.kt`** — when the user taps a YOLO detection, we call `startActiveTrack` with that detection's normalized box. Subsequent detections of the same class+area update the box via `updateTrackTarget`. Tap the same detection again to stop. The on-device PID was removed — the gimbal's own controller is the loop.

## External references

Other public DJI BLE reverse-engineering work that informs (but does not solve) this problem:

- [github.com/xaionaro/reverse-engineering-dji](https://github.com/xaionaro/reverse-engineering-dji) — Wireshark dissector and message-type notes for the **Osmo Pocket 3** (HG212). Confirms the frame layout (magic / length / proto_ver / crc8 / subsystem (sender+receiver) / seq / flags / cmd_set / cmd_id / payload / crc16) is the same as OM7. Does **not** cover OM7 gimbal control or ActiveTrack.
- [github.com/xaionaro-go/djictl](https://github.com/xaionaro-go/djictl) — Go implementation of an open-source replacement for DJI Mimo. Targets FPV drones and goggles. Confirms the meaning of various address constants (`0x02`=App, `0x04`=FlightController, `0x88`=Pairer) and that flag `0x40` = "Request, ACK required". Comment-labels `cmd_set=0x04` as "Gimbal" with `cmd_id=0x27` = keep-alive — consistent with what we observed. Does **not** implement ActiveTrack or anything for the Osmo Mobile line.

Neither repo documents `cmd_set=0x23` or the ActiveTrack target stream. The OM7's ActiveTrack BLE protocol appears to be undocumented publicly.

## Tools used during RE

- `parse_snoop.py` — extract ATT writes/notifications from `btsnoop_hci.log`.
- `analyze_duml4.py` — group DUML frames by (cmd_set, cmd_id) and surface unique payloads.
- `inspect_track.py` — decode 68-byte ActiveTrack payloads.
- `track_start.py` — find the command that precedes the first ActiveTrack frame.
- `find_calibration.py`, `correlate.py`, etc. — earlier dead-ends correlating motion telemetry to TX commands. Kept in the repo as a record but no longer needed.

## Open questions

- The `0x23 / 0x09` metadata bytes (offsets 8–19) are constant in Mimo. We don't know what they actually mean or whether other values would change tracker behavior (e.g. confidence, target class).
- The 32 bytes of zero padding at the end of the ActiveTrack frame may carry optional tracker hints. Worth experimenting if we ever want depth or per-keypoint guidance.
- Re-engagement behavior when the target leaves the frame is untested — Mimo might send a special "lost target" frame or just stop streaming.

## Watchdog / motor lockout

**The OM7 disables its motors when communications appear broken.** Recovery requires pressing the physical M button on the gimbal. There are two distinct failure modes that look identical from the outside:

1. **Invalid commands** — sending a malformed or unrecognised command can immediately lock the motors.
2. **Missing notification ACKs** — the gimbal sends ~10 push notifications/sec (e.g. `set=0x04 id=0x57`, `set=0x05 id=0x06`) with `flags=0x40` (request, ack-required). If the host doesn't echo ACKs back, the gimbal's watchdog concludes the host is dead and locks motors after 60-120 seconds. The legacy `cmd=0x04/0x14` recenter command is *one of the things that gets ignored once the watchdog has triggered* — which is why early attempts at recenter looked like the command itself was unsupported.

**Mitigation**: parse every incoming notification, and for any with `flags & 0xE0 == 0x40` send back a response frame with `flags=0x80`, the same `(seq, cmd_set, cmd_id)`, `sender=0x02`, and `receiver=<their_sender>`, with an empty payload. With this in place, motors stay alive for 4+ minutes and the recenter command works.
 This makes byte-for-byte protocol verification critical — speculative commands cost the user a manual reset every time. Rules of thumb:

- Only send commands whose exact `(receiver, cmd_set, cmd_id, payload)` tuple matches something we observed Mimo send.
- Don't send "init sequence" replays based on guesses about which commands matter — even one wrong receiver byte can lock the motors.
- Don't send the IMU stream (`0x04/0x52`) with synthetic values (e.g. identity quaternion + gravity) without first verifying the gimbal accepts them. Mimo's IMU values reflect real phone orientation; a constant identity quaternion is not what the gimbal expects.

## Failure modes observed

- **Stale box stream after target loss**: if the host keeps streaming the *same* bounding box after the target has left the frame, the gimbal eventually disengages its motors and goes into a soft-locked state that requires the user to press the physical M button on the gimbal to recover. **Mitigation**: stop the ActiveTrack stream (`activeTrackStop` + `activeTrackDisable`) once the target has been missing for ~2 seconds. Don't keep replaying the last box indefinitely.
