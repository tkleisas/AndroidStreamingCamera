# DJI Osmo Mobile 7 - Gimbal Control Research

## Official SDK

DJI's Mobile SDK (MSDK) does **not** support the Osmo Mobile consumer gimbal series. It only covers drones (Mavic, Phantom, etc.) and professional stabilizers (Ronin-MX). There is no official API or SDK for programmatic control of the Osmo Mobile 7.

- Repo: https://github.com/dji-sdk/Mobile-SDK-Android
- Docs: https://developer.dji.com/mobile-sdk/documentation/introduction/mobile_sdk_introduction.html

## Communication Protocol

- The Osmo Mobile 7 uses **Bluetooth Low Energy (BLE 5.0)** for communication
- It speaks a proprietary **DUML message format** with CRC checksums
- Gimbal commands are sent via the BLE characteristic `fff5`
- The protocol supports: pan/tilt/roll control, mode switching, follow behavior, and telemetry

## Open Source / Reverse-Engineered Libraries

### om-research (alkersan)
- **URL**: https://github.com/alkersan/om-research
- **Language**: JavaScript (Web Bluetooth API)
- **What it does**: Documents the Osmo Mobile BLE protocol with a proof-of-concept web UI
- **Best for**: Understanding the protocol structure and command format

### lib-osmo-ble (yigitkonur)
- **URL**: https://github.com/yigitkonur/lib-osmo-ble
- **Language**: Node.js
- **What it does**: Full reverse-engineered BLE protocol implementation for Osmo devices with DUML message encoding/decoding, gimbal control, and live telemetry
- **Best for**: Complete protocol reference when porting to Android/Kotlin

### node-osmo (datagutt)
- **URL**: https://github.com/datagutt/node-osmo
- **Language**: TypeScript
- **What it does**: Controls Osmo Action 3/4/5 and Pocket 3 via BLE
- **Best for**: Clean TypeScript implementation to reference

## Integration Path for Android

1. Study **om-research** to understand the BLE protocol and DUML message structure
2. Use **lib-osmo-ble** as a reference for the full command set (connect, control axes, read telemetry)
3. Implement on Android using native BLE APIs:
   - `BluetoothLeScanner` to discover the gimbal
   - `BluetoothGatt` / `BluetoothGattCharacteristic` to send DUML commands on `fff5`
4. Wrap in a Kotlin class that exposes high-level methods (e.g. `panTo()`, `setMode()`, `recenter()`)

## Notes

- No native Android library exists yet — porting the protocol from JS/TS to Kotlin is required
- The DUML protocol includes sequence numbers and CRC16 checksums; all messages must be correctly framed
- The Osmo Mobile 7 may have firmware-specific differences from older models; testing against the actual device is essential
