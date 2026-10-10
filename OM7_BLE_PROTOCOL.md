# DJI Osmo Mobile 7 — BLE Protocol Notes

Reverse-engineered from BLE HCI snoop captures of the official DJI Mimo app talking to an OM7
(captures: Android Developer Options "Bluetooth HCI snoop log" + `adb bugreport`), and
subsequently **verified/corrected against Mimo 2.12.1's native libraries** (static analysis of
`libdjisdk_jni.so` / `libdjibase.so` / `libDJICSDKCommon.so` / `libml_vot.so` — see
`docs/MIMO_NATIVE_ANALYSIS.md`; the OM7's product codename is **HG305**).

## Transport

- **Service**: `0000fff0-0000-1000-8000-00805f9b34fb`
- **Write characteristic** (FFF5): `0000fff5-0000-1000-8000-00805f9b34fb` — `WRITE_NO_RESPONSE`. On the test device this is GATT handle `0x002b`.
- **Notify characteristic** (FFF4): `0000fff4-0000-1000-8000-00805f9b34fb`. GATT handle `0x0028`. CCCD at `0x0029`.
- The OM7 advertises as `OM7-G<serial>` (e.g. `OM7-G0235F`). Scan filter on name prefix `"OM"` works.
- Mimo communicates with the OM7 **via BLE only** — no WiFi link. All ActiveTrack control is on FFF5.
- The gimbal stops advertising while any host holds the connection (community finding, OM8P; same on OM7).

## Frame format (DUML over BLE)

All frames written to FFF5 follow the standard DJI DUML v1 layout — **confirmed byte-for-byte**
in `dji::core::DjiProtocolEncoder::Encode` (libdjisdk_jni 0x260c944):

```
offset  size  field
 0      1     SOF = 0x55
 1      1     length_lo
 2      1     (version << 2) | (length_hi & 0x03)   ; version=1 for OM7
 3      1     CRC8(bytes[0..2])
 4      1     sender         ; app sends with sender=0x02
 5      1     receiver       ; 0x04 = gimbal, 0x27 = gimbal subsystem (depends on cmd)
 6      2     seq (LE) — app increments per frame
 8      1     flags          ; 0x40 = request, no-ack; 0x80 = response bit
 9      1     cmd_set
10      1     cmd_id
11..N-3       payload
N-2     2     CRC16(bytes[0..N-3]) (LE)
```

`length` = total frame size in bytes.

Address bytes pack as `id(5 bits) | type(3 bits) << 5`: 0x04 = gimbal (id 4, type 0),
0x27 = gimbal subsystem (id 7, type 1), 0x02 = app (id 2, type 0).

An optional XOR-obfuscation path exists in the encoder (gated by `flags & 0xF == 3`) — **not**
used on the OM7 BLE link; frames are plaintext.

### CRC8 (header)

CRC-8/MAXIM variant with custom init. Reflected (LSB-first) implementation — **confirmed** in
`libdjibase.so calc_crc8` (0xaf623c), table at 0x80c3c8, seed hardcoded `0x77`:

```
init = 0x77
poly = 0x8C   (= 0x31 reflected)
```

The DJI tooling community sometimes documents this as MSB-first poly 0x5E — that is **wrong**
(0x5E is merely the table entry at index 1, not the polynomial). Verified:
`crc8(0x55, 0x15, 0x04) = 0xA9` matches Mimo's frames byte-for-byte.

### CRC16 (body)

**CORRECTED (2026-10, from binary analysis).** The only CRC16 in Mimo's native code is
CRC-16/**KERMIT** (libdjibase `calc_crc16` 0xaf6280, table at 0x80c4c8):

```
init = 0x3692   (global g_def_crc16)
poly = 0x8408   (= 0x1021 reflected, KERMIT)
range = the WHOLE frame, from the 0x55 SOF up to (excluding) the 2 CRC bytes
```

The `0xDF0C` seed seen in community docs is exactly the KERMIT state after processing the
4-byte header `55 15 04 A9` from seed 0x3692 — i.e. "KERMIT/0x3692 over whole frame" ≡
"KERMIT/0xDF0C over body" for that header.

**Our `DumlProtocol.kt` historically used poly 0xA001 (ARC) with seed 0xDF0C over the body —
numerically different from KERMIT** (heartbeat example: correct = 0x4760, ours = 0x2D42).
The OM7 has accepted our frames for months, so it evidently **does not validate CRC16 on the
BLE link** (BLE has its own link-layer CRC). Still, use KERMIT/0x3692 over `frame[0..len-2]`
to be byte-exact with Mimo — one less variable when probing new commands against the watchdog.

## Address space

- `0x02` — App / mobile device (us)
- `0x03` — Camera/Vision subsystem (sees `cmd_set=0x03` traffic)
- `0x04` — Gimbal (general)
- `0x27` — Gimbal motor / stabilizer subsystem (handles heartbeats, sees `cmd_set=0x00` traffic)
- `0xF0` — Some "Sky" / pairing-mode subsystem (sees `cmd_set=0x00 cmd_id=0x2B`)

Many commands are sent **twice**, once to `0x04` and once to `0x27` (different subsystems on the same device).

## Known commands

### What we use today (names confirmed from the binary where noted)

| cmd_set | cmd_id | Receiver | Payload | Meaning |
|---------|--------|----------|---------|---------|
| `0x00`  | `0x00` | `0x27`   | `02 00` | Heartbeat — `dji_general_ping_req`. Mimo's HeartbeatLogic sends it every **500 ms** (2 Hz), not 1 Hz |
| `0x04`  | `0x4C` | `0x04`   | `01 00` | **ActiveTrack: enable** — native name `set_work_mode_and_return_center`; 1 = enter tracking-capable mode |
| `0x04`  | `0x4C` | `0x04`   | `02 00` | **ActiveTrack: disable** — same command, 2 = leave it |
| `0x04`  | `0x0F` | `0x04`   | `82 01 00` | **ActiveTrack: start tracking** — native name `set_user_params`, a TLV container; this is TLV `tag=0x82 len=1 val=0` (track start) |
| `0x04`  | `0x0F` | `0x04`   | `82 01 FF` | **ActiveTrack: stop tracking** (tag 0x82, val 0xFF) |
| `0x23`  | `0x09` | `0x04`   | 68 bytes (see below) | **ActiveTrack: target bounding-box stream** — native name `dji_machine_learning_ml_vot_ind_req` (machine-learning visual object tracking), sent at ~10 Hz |

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

Binary-analysis addenda (see docs/MIMO_NATIVE_ANALYSIS.md Q3):

- The native handler (`HG305GimbalAbstraction::ActionRotateByMLVotInfo`) just forwards an
  opaque `BufferMsg` — the 68-byte payload incl. the metadata header is **assembled in Mimo's
  packed Kotlin layer**; the header constant appears in no native lib.
- The detection pipeline (`libml_vot.so`, `mlBuildVotDetResult`) produces **5 floats:
  x, y, w, h, confidence** — confidence is computed but not sent in the standard stream; it may
  occupy one of the 32 pad bytes in other modes.
- Structural hypothesis for the header: `34 00 00 00` at offset 16 = u32 **52 = self-inclusive
  record length** from offset 16 to end (4 + 16 float bytes + 32 pad); `D0 02` = u16 0x02D0 (720)
  may be a message/format id. Unproven — replay verbatim.
- A different tracking-box path exists for camera products (`cmd_set 0x0A, cmd 0xE1`, 37-byte
  TLV) — **not** what OM7 uses.

### Alternative target channel: TKTarget TLVs (`0x04 / 0x0F`)

The same `set_user_params` container carries direct target setters (from native key-layer code):

| tag | meaning |
|-----|---------|
| `0x82` | tracking start/stop (what we send today: `00` start / `FF` stop) |
| `0x89` | TKTarget on/off: **1 = on, 2 = off** |
| `0x8A` | TKTarget x (float32) |
| `0x8B` | TKTarget y (float32) |
| `0x8D`–`0x90` | TKDeadBand params |
| `0x85` | TripodModeSet; `0x9D` NFCLightEnableSet; `0x9E` TKLightEnableSet |

Potentially a lighter-weight "track this point" channel than the 68-byte box stream —
**untested on OM7**.

### Phone IMU stream (`0x04 / 0x52`)

Native name: `camera_atti` (the phone *is* the camera). 40-byte payload sent at ~10Hz, all
values little-endian floats:

```
offset  size  field
 0     16     quaternion (qx, qy, qz, qw)   ; norm = 1.0
16     12     accelerometer (ax, ay, az) m/s²  ; norm ≈ 9.81 at rest
28     12     gyroscope (gx, gy, gz) rad/s
```

Mimo streams the phone's IMU here so the gimbal can stabilize relative to the phone's orientation. We can ignore this stream for ActiveTrack — the gimbal works correctly without it (it falls back to its own IMU). If we ever needed "follow phone tilt" mode, this is the channel.

### Speed control `0x04 / 0x0C` — CONFIRMED layout from OM7 native code

Native name: `dji_gimbal_set_cmd_custom_ctrl_speed_req`, built by
`HG305GimbalAbstraction::ActionRotateSpeed` (libdjisdk_jni 0x2136500). **Never seen in Mimo's
OM7 BLE captures** (Mimo uses `0x14` absolute moves and ActiveTrack instead), but the code path
exists and the same command is hardware-verified on OM2–OM6 and OM8P by the community.

```
payload: yaw(s16 LE)  roll(s16 LE)  pitch(s16 LE)  byte6 = 0x00  byte7 = 0x80
```

- Field order **yaw, roll, pitch**; units **0.1 deg/s** (community-calibrated: 500 → 50.0 °/s,
  3000 → 299.7 °/s). Ceiling 3500 (350 °/s); ≥3600 silently ignored (OM8P).
- **Tail bytes — RESOLVED on OM7 hardware (2026-10-09)**: the working layout is the
  **community order `byte6=0x80, byte7=0x00`**. The Mimo-native order (`00 80`, as Mimo's own
  HG305 code builds it) is ACKed with success (`ret=0x0000`) but produces **no motion** on our
  OM7 — presumably dead code in Mimo, since Mimo never sends `0x0C` to the OM7. The app-assisted
  virtual-stick variant (`byte7=0xC1`) also does not move the OM7.
- Behavior (OM8P measurements, consistent with OM7 test): dead-man stop ~0.5 s after the last
  frame — stream at 10–20 Hz while moving, send explicit zero frames to stop; start latency
  <80 ms; timed-move repeatability only ±15% (closed loops need feedback).

### Other commands seen but not used (names from the binary)

| cmd_set | cmd_id | Native name / meaning |
|---------|--------|-----------------------|
| `0x04`  | `0x07` | `roll_trimming_adjust`. We observed payloads `0x0001`, `0x0015`, `0x0000`, `0xFF00` around calibration; we also use it empirically as a tilt joystick (`joystickPitch`) — the name suggests trim, so our joystick usage of this id is an OM7 quirk worth revisiting. The **real** auto-calibration is `0x04/0x08` (`auto_calibration_req`). |
| `0x04`  | `0x0A` | `set_control_gimbal_angle` (non-EX variant of 0x14) |
| `0x04`  | `0x0D` | `turn_on_off_control` — motor on/off (community payloads: s16 `0x7EF2` on / `0x2AB5` off, unverified) |
| `0x04`  | `0x10` | `read_params` — generic status query (1-byte payloads, ~5 Hz) |
| `0x04`  | `0x12` | `get_message_subscription` — the 34-byte init bursts are **push-message subscription lists**, not config |
| `0x04`  | `0x14` | `set_control_gimbal_angle_ex` — recenter / absolute move (see below) |
| `0x04`  | `0x1E` | `set_subscribe` — our init-seq `71FF0400` is a subscribe request |
| `0x04`  | `0x25` | `set_gimbal_timelapse_control` |
| `0x04`  | `0x44` | `set_gimbal_work_mode` — plain work-mode switch |
| `0x04`  | `0x50` | `gimbal_system_param` get/set. Sub-keys: 1=`JoystickControlMode`, 2=`JoystickPitchInverted`, 3=`JoystickYawInverted`, 4=`IsPitchLocked`, 5=`GimbalFollowSpeed`, 6=`WaterProofModeEnable` |
| `0x04`  | `0x52` | `camera_atti` — phone IMU stream (see above) |
| `0x04`  | `0x54` | `gimbal_feature_control` |
| `0x04`  | `0x58` | `set_stick_control_enable` — handheld stick enable |
| `0x04`  | `0x57` | Stick + button state **push** at ~20 Hz (8-byte; constant `0000000001000000` when idle). NOT position telemetry as previously thought — that decode came from a dead-end script. |
| `0x04`  | `0x05` | **Attitude push** at 1 Hz (36 bytes, all unique): `payload[0:2]`=pitch, `[2:4]`=roll, `[4:6]`=yaw (s16 LE, 0.1°), `[12:16]`=device ms clock (u32). Verified by correlation with joystick moves. Cannot be sped up (OM8P measurement). |
| `0x04`  | `0x71` | 1 Hz push, 30 bytes, float-bearing — likely quaternion/extended attitude; pushed alongside `0x05`. |
| `0x04`  | `0x65` | `action_handle_log` — our init-seq `010100` "mode set" is actually the **gimbal log enable** |
| `0x04`  | `0x68` | `cali_data_exist` / `hw_test_param` — our "periodic config" |
| `0x04`  | `0x6F` / `0x77` | Telemetry pushes |
| `0x05`  | `0x06` | Long-form gimbal state / battery (53-byte frame) |
| `0x12`  | `0x07`/`0x0C` | **BT/BLE management**: `get_ble_name` / `get_bt_mac` (that's what the init-seq empty `0x27`-targeted `0x12` frames are) |
| `0x00`  | `0x34` | `buried_messages` — the init-seq "param set" is **analytics** |
| `0x00`  | `0x4F` | `get_version_config` |
| `0xEE`  | `0x02` | `app_phone_camera_info_push` — our "periodic 7-byte float message" is the phone camera info push |
| `0x23`  | `0x09` | `ml_vot_ind` — ActiveTrack box stream (see above) |

The full extracted table has **319 command instantiations** — see
`docs/dji_cmd_base_req_table.txt` and `docs/MIMO_NATIVE_ANALYSIS.md`.

### Recenter command (CONFIRMED working 2026-10-10)

**Mimo's recenter on the OM7 is `cmd_set=0x04 cmd_id=0x4C` (`set_work_mode_and_return_center`)
with payload `FE 08`** — NOT the legacy `0x14` absolute-angle command:

```
recv=0x04  set=0x04  id=0x4C  payload = 2 bytes:
  byte 0: work_mode          ; 0xFE = keep current mode (01/02 = ActiveTrack enable/disable)
  byte 1: return_center_cmd  ; 0x08 = return to center, 0x00 = none
```

Verified on hardware: gimbal ACKs with `ret=00` and swings back to center. The semantics were
recovered from Mimo 2.12.1 native code: `GimbalAbstraction::ActionResetGimbal` (bound to the SDK
key "ResetGimbal") builds exactly `FE <return_center_cmd>`; HG305 inherits this path
(see docs/MIMO_NATIVE_ANALYSIS.md, "Follow-up: recenter path on HG305").

**The legacy `0x14` (`set_control_gimbal_angle_ex`) is dead on the OM7**: frames are silently
dropped — no ACK, no motion. The binary shows why: `0x14` is only emitted by key actions
(`RotateByAngleAction` etc.) that HG212/HG214/HG225/... register, but **HG305 never registers
them**. (On the OM8P it's ACKed but ignored — same command-family deprecation.) Our earlier
"recenter works via 0x14" note was a misattribution — it was never observed working from the app.

`DumlProtocol.absoluteAngleCommand()` is kept for reference but unused; `recenterCommand()`
builds the `0x4C FE 08` frame.

## Authentication / handshake

We saw encrypted-looking writes (`83 00 1C DD D1 E0 6E 9F C1 32 E0 E0 F8 DD 3C 6F AD CF`) on a *separate* BLE connection at GATT handle `0x0063` near connection time. Initial concern: maybe the OM7 needs a session-key exchange before it accepts commands.

**It does not.** That separate connection is to a different device entirely (the test phone has a smartwatch paired that uses the same handle range). On the OM7's own connection (handle `0x0203` in our captures), no encrypted handshake happens — Mimo just opens FFF5/FFF4 and starts sending DUML. Confirmed independently by the OM8P community repo: no pairing, bonding, handshake or auth of any kind.

## Init sequence (what Mimo does after connect)

In the first ~3 seconds after the BLE connection is established, Mimo sends a flurry of init commands. From our capture, in order (names from the binary):

```
recv=0x04  set=0x04 id=0x12  pl=77FE...D6...   (message subscription list)
recv=0x04  set=0x04 id=0x10  pl=0A             (read params)
recv=0x04  set=0x04 id=0x12  pl=66FE...10...   (message subscription)
recv=0x04  set=0x04 id=0x12  pl=66FE...18...   (message subscription)
recv=0x27  set=0x12 id=0x0C  pl=               (get BT MAC)
recv=0x04  set=0x04 id=0x65  pl=010100         (gimbal log enable)
recv=0x04  set=0x04 id=0x12  pl=66FE...8000...18  (subscription sweep)
recv=0x27  set=0x12 id=0x07  pl=               (get BLE name)
recv=0x27  set=0x00 id=0x34  pl=0101000000000000   (analytics / buried messages)
recv=0x27  set=0x00 id=0x4F  pl=040000000000000000 (get version config)
recv=0x04  set=0x04 id=0x1E  pl=71FF0400       (subscribe request)
```

The gimbal accepts ActiveTrack commands without all of this — heartbeat + the ActiveTrack enable/start/stream alone is enough on a freshly-connected OM7 in our testing. Replaying the full init sequence is the safer option if behavior is flaky.

## Implementation notes for our app

- **`DumlProtocol.kt`** — frame builder with CRC8/CRC16, plus helpers `activeTrackEnable / activeTrackDisable / activeTrackStart / activeTrackStop / activeTrackBox(x, y, w, h)` and `heartbeat()`.
- **`GimbalController.kt`** — BLE scan/connect/MTU/services discovery on FFF0, with a heartbeat coroutine and a 10Hz ActiveTrack streaming coroutine. `startActiveTrack(x, y, w, h)` enables tracking and starts the stream; `updateTrackTarget` updates the box atomically; `stopActiveTrack` tears it all down.
- **`MainActivity.kt`** — when the user taps a YOLO detection, we call `startActiveTrack` with that detection's normalized box. Subsequent detections of the same class+area update the box via `updateTrackTarget`. Tap the same detection again to stop. The on-device PID was removed — the gimbal's own controller is the loop.

### Android BLE write gating + TX queue (community finding, OM8P — confirmed the hard way on OM7)

**Android permits one outstanding GATT write per connection.** On API 33+, an over-eager write
fails with `writeCharacteristic` returning **201 = `ERROR_GATT_WRITE_REQUEST_BUSY`** and the
frame is silently dropped. Our logs showed ~13% of all writes dropped (9,858 / 76,598) —
stream frames (20 Hz joystick) tolerate this, but one-shot commands like recenter were eaten,
which is why "Center" appeared dead even before the protocol fix.

**Implemented fix** (GimbalController): `send()` is a non-blocking enqueue into a
`LinkedBlockingQueue` (512; drops oldest on overflow — stream frames first); a single daemon TX
thread serializes writes, gating each on `onCharacteristicWrite` (500 ms timeout fallback) and
**retrying the same frame** (10 ms backoff, up to 50 attempts) on any non-success result. Zero
drops observed after the fix (8,001/8,001 transmitted). Also: quick reconnects yield GATT error
133 for ~10 s while the gimbal releases the old link — retry, don't treat as fatal.

## External references

Other public DJI BLE reverse-engineering work that informs (but does not solve) this problem:

- [github.com/paichanut/osmo-mobile-ble](https://github.com/paichanut/osmo-mobile-ble) (MIT, cloned at `~/Projects/osmo-mobile-ble`) — **OM8P** measured on hardware: velocity `0x04/0x0C` with calibrated deg/s, dead-man timing, Android write gating, KERMIT CRC16 (whole-frame seed 0x3692). Finds `0x14` absolute angle ACKed-but-ignored on 8P — OM7 accepts it, so OM7 sits between OM6 and 8P.
- [github.com/alkersan/om-research](https://github.com/alkersan/om-research) (MIT) — OM2/OM3/OM4 Python/Web-Bluetooth PoC; `0x04/0x0C` speed + `0x04/0x14` position with `yaw,roll,pitch,mode,time` payload. Unmerged PRs add an SDK-symbol command table and waypoint timelapse docs.
- [github.com/xaionaro/reverse-engineering-dji](https://github.com/xaionaro/reverse-engineering-dji) — Wireshark dissector and message-type notes for the **Osmo Pocket 3** (HG212). Confirms the frame layout is the same family as OM7. Does **not** cover OM7 gimbal control or ActiveTrack.
- [github.com/xaionaro-go/djictl](https://github.com/xaionaro-go/djictl) — Go implementation of an open-source replacement for DJI Mimo. Targets FPV drones and goggles. Confirms the meaning of various address constants and flag bits. Does **not** implement ActiveTrack or anything for the Osmo Mobile line.
- `docs/MIMO_NATIVE_ANALYSIS.md` — our own static analysis of Mimo 2.12.1's native libs (command tables, CRC constants, ActiveTrack pipeline).

## Open questions

- **TKTarget channel** (`0x04/0x0F` tags 0x89/8A/8B): does the OM7 track a plain x/y point
  without the 68-byte ML box stream? Untested.
- The `0x23 / 0x09` metadata bytes (offsets 8–19) are constant in Mimo and appear in no native
  lib (built in packed Kotlin). Best hypothesis: `34 00 00 00` = u32 52 self-inclusive record
  length. We replay them verbatim either way.
- The 32 bytes of zero padding at the end of the ActiveTrack frame may carry optional tracker
  hints (libml_vot computes a confidence float that isn't in the standard stream).
- Re-engagement behavior when the target leaves the frame is untested — Mimo might send a
  special "lost target" frame or just stop streaming.

## Watchdog / motor lockout

**The OM7 disables its motors when communications appear broken.** Recovery requires pressing the physical M button on the gimbal. There are two distinct failure modes that look identical from the outside:

1. **Invalid commands** — sending a malformed or unrecognised command can immediately lock the motors.
2. **Missing notification ACKs** — the gimbal sends ~10 push notifications/sec (e.g. `set=0x04 id=0x57`, `set=0x05 id=0x06`) with `flags=0x40` (request, ack-required). If the host doesn't echo ACKs back, the gimbal's watchdog concludes the host is dead and locks motors after 60-120 seconds.

**Response/error codes**: command ACKs carry a device ret code in the first payload byte — `00` = success; `0xFF` = generic refusal (maps to SDK error −511 via the native table at libdjisdk_jni 0xbe4170). We occasionally see `0xFF` (ACK payload `ff 08`) on a few speed frames during mode transitions — transient, not a lockout signature.

**Mitigation**: parse every incoming notification, and for any with `flags & 0xE0 == 0x40` send back a response frame with `flags=0x80`, the same `(seq, cmd_set, cmd_id)`, `sender=0x02`, and `receiver=<their_sender>`, with an empty payload. With this in place, motors stay alive for 4+ minutes and the recenter command works.
 This makes byte-for-byte protocol verification critical — speculative commands cost the user a manual reset every time. Rules of thumb:

- Only send commands whose exact `(receiver, cmd_set, cmd_id, payload)` tuple matches something we observed Mimo send.
- Don't send "init sequence" replays based on guesses about which commands matter — even one wrong receiver byte can lock the motors.
- Don't send the IMU stream (`0x04/0x52`) with synthetic values (e.g. identity quaternion + gravity) without first verifying the gimbal accepts them. Mimo's IMU values reflect real phone orientation; a constant identity quaternion is not what the gimbal expects.

## Failure modes observed

- **Stale box stream after target loss**: if the host keeps streaming the *same* bounding box after the target has left the frame, the gimbal eventually disengages its motors and goes into a soft-locked state that requires the user to press the physical M button on the gimbal to recover. **Mitigation**: stop the ActiveTrack stream (`activeTrackStop` + `activeTrackDisable`) once the target has been missing for ~2 seconds. Don't keep replaying the last box indefinitely.

## Tools used during RE

- `parse_snoop.py` — extract ATT writes/notifications from `btsnoop_hci.log`.
- `analyze_duml4.py` — group DUML frames by (cmd_set, cmd_id) and surface unique payloads.
- `inspect_track.py` — decode 68-byte ActiveTrack payloads.
- `track_start.py` — find the command that precedes the first ActiveTrack frame.
- `find_calibration.py`, `correlate.py`, etc. — earlier dead-ends correlating motion telemetry to TX commands. Kept in the repo as a record but no longer needed.
- `~/Projects/mimo-apk/` — pulled Mimo 2.12.1 APK set + static analysis tooling (`extracted/ann.py`, `pltmap.py`, symbol/string dumps, `cmd_base_req_table.txt`).
