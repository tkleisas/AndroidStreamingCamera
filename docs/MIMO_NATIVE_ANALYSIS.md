# DJI Mimo 2.12.1 — Native Library Analysis (OM7 / HG305 BLE protocol)

Analysis of `libdjisdk_jni.so` (41MB), `libdjibase.so` (22MB), `libDJICSDKCommon.so` (from
`split_dynamic_pack_csdk.apk`) and `libml_vot.so` (9.5MB, from `split_config.arm64_v8a.apk`).
All four are **unstripped at the dynsym level** (~42k/~59k/~18k demangleable symbols), which makes
targeted extraction straightforward. Working dir: `/home/tkleisas/Projects/mimo-apk/extracted/`
(tools written there: `ann.py` annotated disassembler, `pltmap.py` PLT→symbol resolver,
`scan.py`, `disasm_fn.py`; symbol dumps `*.syms.txt`, string dumps `*.strings.txt`,
full command table `cmd_base_req_table.txt`).

Product codenames found: **HG305 = Osmo Mobile 7** (`dji::sdk::HG305GimbalAbstraction`,
asset dir `HG305/`, also `HG305SE`, `HG306`, `OM307` asset dirs). HG211/HG212 = earlier Osmo
Mobile / Pocket generations.

---

## Q1 — Command tables (name → cmd_set/cmd_id)

### Method

The CSDK layer instantiates a template per command:

```
dji::core::dji_cmd_base_req<(unsigned char)1, (unsigned char)CMD_SET, (unsigned char)CMD_ID,
                            <req_struct>, <rsp_struct>>
```

The 1st template arg is always 1 (internal sender host-id for the mobile app), 2nd = DUML
cmd_set, 3rd = DUML cmd_id. 319 unique instantiations were extracted from symbol/typeinfo
strings in `libdjisdk_jni.so` (full list in `extracted/cmd_base_req_table.txt`).

Cross-check that the params are really (cmd_set, cmd_id): the encoder
`dji::core::DjiProtocolEncoder::Encode` (0x260c944) copies a u16 from `req+1` straight into
frame bytes 9–10 (`ldurh w8, [x20, #1]; strh w8, [x21, #9]!`), and e.g.
`HG305GimbalAbstraction::ActionRotateSpeed` sets `req+2 = 0x0C` for the speed command. ✔

### cmd_set 0x04 (Gimbal) — complete table

| cmd_id | struct name | verdict vs known list |
|--------|-------------|-----------------------|
| 0x01 | `dji_gimbal_set_motion_control_req` | NEW — another motion command |
| 0x05 | *(absent — no such req in 2.12.1)* | — |
| 0x07 | `dji_gimbal_set_roll_trimming_adjust_req` | **name contradicts** our "calibration step indicator" guess; roll-trim adjust. The 0x0015 trigger we saw is presumably a trim-adjust step value |
| 0x08 | `dji_gimbal_auto_calibration_req` | NEW — this is the real auto-calibration action |
| 0x0A | `dji_gimbal_set_control_gimbal_angle_req` (+ `_new` variant) | NEW — absolute angle (non-EX variant of 0x14) |
| 0x0C | `dji_gimbal_set_cmd_custom_ctrl_speed_req` | **CONFIRMED** = speed/joystick velocity cmd |
| 0x0D | `dji_gimbal_set_turn_on_off_control_req` | CONFIRMED (community "motor on/off") |
| 0x0F | `dji_gimbal_set_user_params_req` | **renames** our "ActiveTrack start/stop": 0x0F is a TLV user-param container; `82 01 00` is TLV `tag=0x82 len=1 val=0` (see Q3) |
| 0x10 | `dji_gimbal_read_params_req` | CONFIRMED (our "generic status query") |
| 0x12 | `dji_gimbal_get_message_subscription_req` | **renames** our "config push": the 34-byte init bursts are push-message subscription lists |
| 0x13 | `dji_gimbal_reset_default_params_req` | NEW |
| 0x14 | `dji_gimbal_set_control_gimbal_angle_ex_req` | CONFIRMED (recenter / absolute move) |
| 0x1E | `dji_gimbal_set_subscribe_req` | CONFIRMED-ish (we had `71FF0400` as "mode/limit" — it's a subscribe request) |
| 0x1F | `dji_gimbal_gimbal_rw_sn_code_req` | NEW |
| 0x25 | `dji_gimbal_set_gimbal_timelapse_control_req` | NEW |
| 0x31/0x32 | `dji_gimbal_set_ronin_params_req` / `get_rollin_params_req` | NEW |
| 0x3A | `dji_gimbal_set_coordinate_system_rotate_req` | NEW |
| 0x40 | `dji_gimbal_action_gimbal_adjust_req` | NEW |
| 0x44 | `dji_gimbal_set_gimbal_work_mode_req` | NEW — plain work-mode switch |
| 0x4C | `dji_gimbal_set_work_mode_and_return_center_req` | **name nuance**: our ActiveTrack enable/disable (`01 00`/`02 00`) is a *work-mode + return-center* combo; value 1 = enter the tracking-capable mode, 2 = leave it (cf. Q3: GimbalTKTargetSet encodes on=1/off=2 the same way) |
| 0x50 | `dji_gimbal_gimbal_system_param_req` | CONFIRMED (our "status push, varying") — system param get/set, see sub-key map below |
| 0x52 | `dji_gimbal_camera_atti_req` | CONFIRMED — phone IMU stream is "camera attitude" (the phone *is* the camera) |
| 0x54 | `dji_gimbal_gimbal_feature_control_req` | NEW — feature control |
| 0x58 | `dji_gimbal_set_stick_control_enable_req` | NEW — handheld stick enable |
| 0x61 | `dji_gimbal_Receive_GPS_NAV_data_push` | NEW |
| 0x65 | `dji_gimbal_action_handle_log_req` | **renames** our "mode set" (`010100`) — it's the gimbal-log enable (`EnableGimbalLogSet` exists in symbols) |
| 0x67 | `dji_gimbal_set_gimbal_esc_extern_command_req` | NEW |
| 0x68 | `dji_gimbal_cali_data_exist_req` / `dji_gimbal_hw_test_param` | **renames** our "periodic config" |
| 0x6A | `dji_gimbal_dji_gimbal_control_para_calibration_req` | NEW |
| 0x6B | `dji_gimbal_extern_req_status_push` | NEW |
| 0x6D | `dji_gimbal_gimbal_path_control_req` | NEW |
| 0x72 | `dji_gimbal_get_get_extern_device_status_req` | NEW |
| 0x57 / 0x6F / 0x77 | *(no req instantiation — these are **push-only** telemetry ids, registered via `ObserverPushPack<gimbal_attitude_push>` etc.)* | consistent with capture |

### Gimbal system-param sub-keys (used inside 0x04/0x50)

From `HandheldGimbalAbstraction::GetMapSystemParamKeyCmdid` (0x20dcff4) — a
`map<string, DJI_GIMBAL_CMDID>` built with string + immediate pairs:

| sub-id | key |
|--------|-----|
| 1 | `JoystickControlMode` |
| 2 | `JoystickPitchInverted` |
| 3 | `JoystickYawInverted` |
| 4 | `IsPitchLocked` |
| 5 | `GimbalFollowSpeed` |
| 6 | `WaterProofModeEnable` |

### User-param TLV tags (payload of 0x04/0x0F)

Extracted from the key-layer setters (each builds `dji_gimbal_set_user_params_req` with
`tag(1) len(1) value(len)` TLVs):

| tag | meaning (function) |
|-----|--------------------|
| 0x82 | tracking start/stop — observed in captures as `82 01 00` / `82 01 FF`; not built by any native key setter (built by the packed Kotlin tracking module) |
| 0x85 | TripodModeSet |
| 0x89 | TKTarget on/off: **1 = on, 2 = off** (`GimbalTKTargetSet`, 0x215a270; `cmp w23,#0 / cinc` at 0x215a6b4) |
| 0x8A | TKTarget x (float32) |
| 0x8B | TKTarget y (float32) |
| 0x8D–0x90 | TKDeadBand params (`GimbalTKDeadBandSet`, 0x2159724) |
| 0x9D | NFCLightEnableSet |
| 0x9E | TKLightEnableSet |

### Other cmd_sets of interest

| cmd_set | meaning | members |
|---------|---------|---------|
| 0x00 | general | 0x00 ping (heartbeat), 0x01 get_version, 0x0E `dji_general_heartbeat_req`, 0x34 `buried_messages` (our init-seq "param set" is analytics), 0x4F `get_version_config`, 0x0B reboot, 0x32 activate, … (45 entries) |
| 0x01 | special | 0x01 special_ctrl_push, 0x02 **`dji_action_virtual_rc_joystick_req`** |
| 0x02 | camera | 129 entries (incl. 0xA5/A6 get/set_tracking_params, 0x40/0x41 face detection) |
| 0x03 | fc | hash-param read/write etc. |
| 0x05 | centerboard | battery history/static-info/barcode |
| 0x07 | wifi | ssid/password/country-code… |
| 0x0A | vision | 0x60/0x62 visual-stabilize enable/app-state, **0xE1 `dji_vision_push_tracking_box_push`** (37-byte TLV tracking-box push used by camera products — *not* the OM7 path) |
| 0x12 | **BT/BLE** | 0x07 get_ble_name, 0x08 set_ble_name, 0x0C get_bt_mac, 0x10 broadcast_control, 0x21 get_ibeacon_uuid, 0x8B switch_ble_mode, 0x8F NFC read/write — matches init-seq frames `recv=0x27 set=0x12 id=0x07/0x0C` (name/MAC queries) |
| 0x23 | **machine learning** | **0x09 `dji_machine_learning_ml_vot_ind_req`** (ActiveTrack box stream ✔), 0xA5 `ml_vsai_hlp_msg_rsp` |
| 0xEE | app→device | **0x02 `dji_app_phone_camera_info_push`** (our "0xEE/0x02 float message" = phone camera info ✔), 0x04 get_ibeacon_uuid, 0x1C phone_camera_info_push_to_rc, 0x1B set_special_state, 0x2D ios_screen_mirroring, 0x2F app_to_dev_option/gray_strategy |

HG305 (OM7) key-layer action names (`HG305GimbalAbstraction::CreateCharacteristics`, 0x21334a0):
`RotateByMLVotInfo`, `SmartTrackingSpeed`, `StepFrequency`, `TiltAngle`, `GimbalEndlessYaw`,
`GimbalLoadDirection`, `GimbalSideButtonExecutionEvent`, `IsAiModuleExist`,
`IsTrackingBgIndependentModeSupport`, `TrackingMlSupportVer`, `ReSubscribeGimbalPush`, `Range`, `Record`.

---

## Q2 — Speed/joystick command (0x04/0x0C) construction

**Definitive, from `dji::sdk::HG305GimbalAbstraction::ActionRotateSpeed` (0x2136500)** — the OM7's
own handler for the `RotateBySpeed` key:

```
ldp  d2, d1, [val+8]      ; d2 = pitch, d1 = yaw     (GimbalSpeedRotation field map below)
ldr  d3,     [val+0x18]   ; d3 = roll
fmul each by 10.0; fcvtzs → s16
strh yaw*10   → payload[0..1]
strh roll*10  → payload[2..3]
strh pitch*10 → payload[4..5]
payload[6] = 0x00                          (from init constant)
payload[7] = ctrlInfo << 7                 ; ctrlInfo is a bool → 0x80 or 0x00
```

Field map of `dji::sdk::GimbalSpeedRotation(double,double,double,CtrlInfo)`
(`libdjibase.so`: ctor 0xb58a28, `to_json` 0xb58b20):
`+8 = pitch, +0x10 = yaw, +0x18 = roll, +0x20/+0x28 = ctrlInfo` (JSON keys in order
`pitch, yaw, roll, ctrlInfo`).

**Wire layout (8 bytes): `yaw s16, roll s16, pitch s16` (LE, 0.1 °/s), `byte6 = 0x00`, `byte7 = 0x80`.**
This **confirms** the community `yaw,roll,pitch` order and units, but places the mode byte
(0x80) at **offset 7** and the zero ("time") byte at **offset 6** — the opposite order from the
paichanut/om-research comments ("mode 0x80 + time byte" as bytes 6,7). If their hardware works
with `.. 80 00` and Mimo sends `.. 00 80`, the gimbal may accept both or only offset 7 is
mode and the trailing byte is a don't-care — treat as **unresolved byte order between
offset 6/7; Mimo-native = `00 80`**.

A second builder, `HandHeldTrackingMissionAbstraction::SetAppAssistedTrackingMissionVirtualStickControl`
(0x134a030, the app-assisted-tracking virtual stick), emits the same `yaw,roll,pitch ×10`
triplet but with **byte6 = 0x00, byte7 = 0xC1** hardcoded (`mov x10, #0xc1000000000000`).
0xC1 = 0x80 | 0x41 — bits 6 and 0 additionally set; likely "valid from virtual stick" flags.
Same cmd: `dji_cmd_base_req<1,4,12>`.

Other call sites building the same 0x04/0x0C req (xrefs to the ctor PLT 0x2629260):
`GimbalAbstraction::ActionRotateSpeed` (generic, 0x210789c), `MultiGimbalAbstraction`,
`PM320DualLightGimbalAbstraction`, `DroneGimbalAbstraction`, key-layer `RotateBySpeedAction`
(0x21a5c10).

Note: our app's current `speedCommand()` (pitch,roll,yaw + trailing 0x01) is wrong on **all three
counts** (order, missing mode byte position, and 7-byte length vs 8).

Related but distinct: cmd 0x04/0x01 `dji_gimbal_set_motion_control_req` and cmd 0x01/0x02
`dji_action_virtual_rc_joystick_req` exist but were not observed on OM7 BLE.

---

## Q3 — ActiveTrack (cmd_set 0x23 / cmd 0x09)

- The command is **`dji_machine_learning_ml_vot_ind_req`** — "machine-learning visual object
  tracking indication". ctor: `dji_cmd_base_req<1,35,9>` at 0x2139178, used exactly once, by
  **`dji::sdk::HG305GimbalAbstraction::ActionRotateByMLVotInfo`** (0x21368a8) — the handler for
  the HG305 key `RotateByMLVotInfo`.
- That handler `dynamic_cast`s the value to **`dji::sdk::BufferMsg`** (typeinfo ref
  `_ZTIN3dji3sdk9BufferMsgE` at GOT 0x2762e20) and **copies the buffer verbatim** into the
  request body (`Dji::Common::Buffer::operator=`). So the 68-byte payload — including the
  constant 12-byte metadata header `D0 02 00 05 02 02 01 05 34 00 00 00` — is **assembled in the
  packed Kotlin layer, not in native code**. The byte sequence does not occur anywhere in the
  four analyzed .so files (verified by direct search).
- The tracking pipeline itself is native: **`libml_vot.so`** (`appSendVotDetResult` →
  `mlBuildVotDetResult` 0x448218, `vpfSendVotResultWithStatus`, `msg_ml_vot_ind_t`). The
  detection-result item built at 0x448340 contains **5 floats: x, y, w, h, confidence**
  plus u32/u8 fields — matching the cx/cy/w/h we see on the wire (confidence is computed but
  apparently not sent in the standard OM7 stream; possibly one of the 32 trailing pad bytes in
  other modes).
- Hypothesis on the header (from structure only, no native constant): `34 00 00 00` at payload
  offset 16 = **u32 52, the self-inclusive length of the record from offset 16 to the end
  (52 = 4 + 16 float bytes + 32 pad)** — i.e. a `[8-byte type/ver prefix][u32 len][data]` block.
  `D0 02` = u16 0x02D0 (720) may be a message/format id. Unproven — replay verbatim.
- Track start/stop (`82 01 00` / `82 01 FF` inside 0x04/0x0F user-params TLV, tag 0x82) and
  ActiveTrack enable/disable (`0x04/0x4C` = `set_work_mode_and_return_center`, 01/02) are both
  sent from the packed Kotlin layer as well; no native builder for tag 0x82 was found among the
  16 `set_user_params_req` call sites.
- The other tracking-box path, `dji_vision_push_tracking_box_push` (cmd_set **0x0A**, cmd **0xE1**),
  builds a **37-byte** TLV payload (timestamp u64 + sender-seq u16 + frame-counter u8 + 4 TLV'd
  floats + 2 ×10-scaled ints + status bytes) in
  `HandHeldTrackingMissionAbstraction::PushAppAssistedTrackingMissionVisionBox` (0x1347cd0),
  reachable from `JNI_PushTrackingVisionBox`. This is the Pocket/camera-product path, **not**
  what OM7 uses (our captures show 0x23/0x09 with 68 bytes).

---

## Q4 — CRC constants

Both CRCs live in `libdjibase.so` and are the **only** CRC8/CRC16 implementations in the whole
native set (table patterns searched across all libs — no 0xA001 table exists anywhere):

- `calc_crc8` (0xaf623c): table at 0x80c3c8, seed hardcoded `mov w8, #0x77`.
  Brute-forcing the table → **reflected poly 0x8C** ✔ CONFIRMED. (The raw table bytes start
  `00 5E BC E2 …` — 0x5E at index 1 is a *table entry*, not the poly; this is exactly why the
  "MSB poly 0x5E" folklore is wrong.) Verified: crc8(0x77, 0x8C, `55 15 04`) = 0xA9.
- `calc_crc16` (0xaf6280): table at 0x80c4c8, seed = global `g_def_crc16` (0x1550458) =
  **0x3692**. Brute-forcing the table → **reflected poly 0x8408 = CRC-16/KERMIT**.
  `calc_crc16_ex` (0xaf62c8) is the same loop with a caller-supplied seed.
- **Range**: `DjiProtocolEncoder::Encode` (0x260c944) at 0x260cd20 does
  `calc_crc16(frame_base, total_len - 2)` — i.e. **KERMIT over the entire frame starting at the
  0x55 SOF (header + crc8 byte + body), excluding the 2 CRC bytes**, stored LE.
- **The `0xDF0C` mystery solved**: KERMIT state after processing the 4-byte header `55 15 04 A9`
  from seed 0x3692 is exactly **0xDF0C** (verified numerically). So "KERMIT/0x3692 over whole
  frame" ≡ "KERMIT/0xDF0C over body". The community repo's self-test asserts precisely this
  (with the KERMIT table on both sides).

**Verdict: CONTRADICTED (important).** Our `DumlProtocol.kt` uses poly **0xA001** (ARC) with seed
0xDF0C over the body — a different polynomial than the binary's KERMIT/0x8408. The two do **not**
produce equal values (heartbeat example: correct = 0x4760, ours = 0x2D42). Since our app has been
controlling the OM7 successfully for months, the practical conclusion is that **the OM7 does not
reject frames with a wrong CRC16 on the BLE link** (BLE has its own link-layer CRC; the gimbal
apparently validates only CRC8/structure). Still, we should switch to KERMIT/0x8408, seed 0x3692,
over `frame[0..len-2]` to be byte-exact with Mimo — it removes a variable when probing new
commands against the watchdog. No other seeds (0x3692 is the single default; `calc_crc16_ex`
allows others per-call but the DUML encoder uses the default).

Also seen in `Encode`: an optional obfuscation path (XOR keystream table at 0x282a610, gated by
`flags & 0xF == 3` and a global flag) — **not** used on the OM7 BLE link (frames are plaintext).

---

## Q5 — Heartbeat / ACK / watchdog

- Heartbeat: `dji::sdk::HeartbeatLogic::SendHeartbeat` (0x144ff34) builds
  `dji_cmd_base_req<1,0,0,dji_general_ping_req>` — i.e. **cmd_set 0x00 cmd_id 0x00 ping**,
  CONFIRMED as the heartbeat. Timer: `HeartbeatLogic::PostStart` →
  `Worker::StartTimer(fn, 0x1F4)` = **500 ms** period (2 Hz — faster than our 1 Hz).
  `SetReceiverIndex` picks per-link receiver (0x1A = 26 seen for one datalink type); the SDK
  routes it to the right subsystem (0x27 on OM7 BLE). A separate `dji_general_heartbeat_req`
  (0x00/0x0E) exists but ping is what the heartbeat logic sends.
- Default command timeout: the `dji_cmd_req` base ctor stores **0x1F4 (500 ms)** at req+0x14.
- ACK/response bit: `Encode` sets frame flags byte bit7 (0x80) from req+9
  (`orr w8, w9, w8, lsl #7`), and bits 5–6 from req+3 (`bfi …, #5, #2`; flags 0x40 ⇒ that field
  = 2). So the response echo with flags=0x80 is just "same cmd, response bit set" — matches our
  ACK loop. ACKs to incoming ack-required pushes are generated in the dispatcher layer
  (`dji::crossplatform::PackProviderImpl::SendRspPack`, 0x233d404).
- Address packing detail from `Encode` (0x260cb3c–0x260cb84): each DUML address byte =
  `id(5 bits) | type(3 bits) << 5`. So 0x04 = gimbal id 4 type 0, 0x27 = id 7 type 1
  (subsystem), 0x02 = app id 2 type 0. ✔ consistent with captures.
- The motor-lock watchdog itself is firmware-side; nothing in the app enforces it. Relevant
  host-side strings: `send_packet_timeout, cmd_set = ` (0xb0fd16), `[SessionMgr]
  SendDataFailed, cmd_set = ` (0xae20f2).

---

## Q6 — BLE UUIDs / MTU

- **fff0/fff4/fff5 do not appear in any native library** — neither as strings nor as the 16-byte
  binary UUID (searched all four .so files for both encodings). The BLE service/characteristic
  UUIDs, MTU request, and connection-parameter logic live in the packed Kotlin layer
  (`osmo-gimbal-service_mimoRelease` module, inside the packed DEX in `libdatajar.so` —
  not analyzable without unpacking).
- Native BLE surface is limited to DUML cmd_set **0x12** (BT management: BLE name, MAC,
  broadcast, iBeacon, mode switch, NFC) and `ble_*` pack types (`ble_switch_mode_pack`,
  `ble_get_ibeacon_uuids_pack`, …).
- `base.apk` assets (712+ entries) contain nothing protocol-level: only UI JSON/lottie and
  encrypted ML configs. `DJI-Assets.zip` (662MB, 5583 entries): per-product dirs
  (`HG211 HG212 HG302 HG305 HG305SE HG306 OM307 OM507`) with ML models, tutorial videos, and
  **encrypted** tracker configs (`*.json.eng.enc`) — no plaintext command tables or BLE configs.

---

## Verdict summary vs OM7_BLE_PROTOCOL.md

| Known-protocol claim | Verdict |
|---|---|
| Frame format, offsets, version=1 | CONFIRMED byte-for-byte in `DjiProtocolEncoder::Encode` |
| CRC8 poly 0x8C seed 0x77 over bytes[0..2] | CONFIRMED (`calc_crc8`, table 0x80c3c8) |
| CRC16 ARC poly 0xA001 seed 0xDF0C over body | **CONTRADICTED** — binary uses KERMIT poly 0x8408 seed 0x3692 over the *whole frame*; 0xDF0C = KERMIT state after the 4-byte header. Our ARC values are numerically different; OM7 evidently tolerates wrong CRC16 on BLE |
| Heartbeat 0x00/0x00 | CONFIRMED — ping req, **500 ms** period |
| 0x04/0x0C = speed, yaw/roll/pitch 0.1 °/s | CONFIRMED order & scale from HG305 code; **mode byte 0x80 is at offset 7, zero at offset 6** (community order swapped); virtual-stick variant uses 0xC1 |
| 0x04/0x14 recenter | CONFIRMED = `set_control_gimbal_angle_ex` |
| 0x04/0x0F = ActiveTrack start/stop | CONFIRMED cmd, renamed: it's `set_user_params` TLV; tag 0x82 |
| 0x04/0x4C = ActiveTrack enable/disable | CONFIRMED cmd id; native name `set_work_mode_and_return_center` |
| 0x23/0x09 = ActiveTrack box stream | CONFIRMED = `ml_vot_ind` (machine-learning visual object tracking); payload built in packed Kotlin, forwarded as opaque BufferMsg |
| 0x04/0x52 = phone IMU | CONFIRMED = `camera_atti` |
| 0x12 = BT management, 0xEE/0x02 = phone camera info | NEW confirmations for init-sequence frames |
| 0x04/0x07 calibration trigger | RENAMED — `roll_trimming_adjust`; real auto-cal is 0x04/0x08 |
| 0x04/0x12 "config push" | RENAMED — `get_message_subscription` |
| 0x04/0x65 "mode set" | RENAMED — `action_handle_log` (gimbal log enable) |
| BLE UUIDs in binary | ABSENT — packed Kotlin only |

## Notable loose ends

- The 12-byte `0x23/0x09` metadata header is assembled in packed Kotlin; only structural
  hypothesis available (offset-16 u32 = 52 = self-inclusive record length).
- Whether OM7 accepts `.. 80 00` vs Mimo's `.. 00 80` tail on 0x04/0x0C needs a hardware test
  (with CRC16 fixed to KERMIT first).
- Push telemetry cmd ids (0x57/0x6F/0x77) are registered via `ObserverPushPack<T>` with the id
  inside pack-type statics; not extracted (capture-derived values stand).
