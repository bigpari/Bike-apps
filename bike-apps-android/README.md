# Bike Apps

An Android app that connects to a proprietary BLE indoor exercise bike, decodes its (reverse-engineered) telemetry protocol, and rebroadcasts speed and cadence as a standard Bluetooth Cycling Speed and Cadence (CSC) sensor — so devices like a Garmin watch can pair to the bike for live speed/distance tracking during an indoor cycling activity.

## What it does

1. **Connects to the bike as a BLE client** and drives its custom command protocol (ping → device info → quick start → sync → a polled record loop) to receive live workout telemetry.
2. **Decodes the raw byte stream** into speed, distance, workout time, cadence (rpm), resistance, and a few running average/max fields.
3. **Broadcasts a standard BLE CSC GATT service** (`0x1816`) so any CSC-compatible watch or head unit — Garmin included — can pair to the phone as if it were a real speed/cadence sensor.
4. Optionally logs raw and decoded data to a local WebSocket server for debugging/analysis.

## Project structure

| File | Purpose |
|---|---|
| `BLEManager.kt` | BLE central role - scans for, connects to, and drives the bike's command protocol |
| `BikeDecoder.kt` | Pure decoding logic - raw `E5`/`E6`/`E7` byte arrays → structured `BikeData` |
| `CscPeripheral.kt` | BLE peripheral role - advertises the standard CSC service so Garmin can pair to the phone |
| `WebSocketManager.kt` | Optional logging connection to a local dev server, with auto-reconnect |
| `MainActivity.kt` | Wires the above together and drives the UI |

## The bike protocol (reverse-engineered)

The bike has no public documentation or SDK. Everything about its protocol was reverse-engineered from a Bluetooth HCI capture (`btsnoop`) of the manufacturer's own app, cross-checked against real ride data, and independently corroborated against [codaris.github.io/UnderDeskBike](https://codaris.github.io/UnderDeskBike/), which documents what is clearly the same underlying device family.

**Transport:** a custom command/response protocol layered on top of the Nordic UART Service (`6e400001-b5a3-f393-e0a9-e50e24dcca9e`).

**Frame format** (both directions):
```
byte 0        SOF        = 0xF9
byte 1        CMD        command/response opcode
byte 2        LEN        payload length
byte 3..      PAYLOAD    LEN bytes
byte 3+LEN    CHECKSUM   = sum(bytes[0 .. 3+LEN-1]) & 0xFF
```
Host→device writes are zero-padded to 20 bytes; device→host notifications are exactly sized.

**Command flow:**
```
PING (0xD0 → 0xE0)
DEVICE_INFO (0xD1 → 0xE1)
SET_CONFIG / quick start (0xD3 → 0xE3)
START_SYNC (0xD4 → 0xE4)
READ_NEXT, looped (0xD5 → 0xE5/0xE6/0xE7 triple, ~1 request/second)
```

**Decoded fields** (see `BikeDecoder.kt` for full detail and confidence notes):

| Field | Confidence | Notes |
|---|---|---|
| `distance`, `speed`, `workoutTimeSeconds` | High | Verified monotonic/freeze-on-stop behavior across multiple real sessions |
| `second` / `minute` | High | Confirmed to wrap at 60s; device-persistent clock, not reset per BLE connection |
| `avgSpeed`, `maxSpeed` | High | Running average/max, verified never-decreasing across full sessions |
| `resistance` | Medium | Constant per-session at low levels; shows ±1 jitter at higher resistance settings that doesn't correlate with effort - likely a real measured value near a boundary, not transmission noise |
| `rpm` | Medium | Live cadence-like value, but unconfirmed whether it's true pedal cadence or flywheel RPM - values run higher than a hand-counted cadence would suggest |
| `powerLive/Avg/Max` (E7) | Low | A genuine live/average/max triple, but a resistance-5-vs-10 comparison ruled out the simple "power" hypothesis (higher resistance produced *lower* values at matching speed/rpm) - real meaning still unknown |
| Calories | N/A | Not transmitted by this protocol at all |

Almost every multi-byte field is a 16-bit **big-endian** value, not a single byte - this only became apparent once real testing pushed values past 255 and exposed silent truncation in an earlier version of the decoder.

## CSC broadcast to Garmin (or any CSC client)

Unlike the bike's protocol, this part follows the public Bluetooth SIG spec exactly - no reverse-engineering involved. The phone runs a BLE **peripheral** (GATT server) advertising service `0x1816` with `CSC Measurement` (`0x2A5B`), `CSC Feature` (`0x2A5C`), and `Sensor Location` (`0x2A5D`) characteristics, alongside its existing BLE **central** connection to the bike.

Since the bike has no real wheel, wheel revolutions are back-computed from its reported speed using a configurable assumed circumference (`WHEEL_CIRCUMFERENCE_MM` in `CscPeripheral.kt`, default 2105mm). If a paired device's displayed speed doesn't match this app's, that constant is the first thing to adjust.

## Setup

Requires:
- `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`, `BLUETOOTH_ADVERTISE` (Android 12+ runtime permissions)
- `WAKE_LOCK` (manifest permission, no runtime prompt)

The WebSocket logging server URL is currently a placeholder (`ws://YOUR_LOCAL_IP:8080`) in `MainActivity.kt` - point it at a local dev server if you want raw/decoded data logged during a session, or ignore it entirely (it's optional and unrelated to the CSC broadcast, which works independently).

## Known limitations / open questions

- Pedal vs. flywheel cadence is unconfirmed - a hand-count of pedal revolutions compared against the live `rpm` value during a ride would settle this.
- The E7 "power" field's real meaning is unknown after ruling out simple power.
- One byte in the `E6` record (`unknownE6`) varies but hasn't matched any tested hypothesis.
- Backgrounding behavior (screen lock, Doze) can interrupt BLE/WebSocket work on long rides depending on device/manufacturer - a `PARTIAL_WAKE_LOCK` is used as a stopgap; a proper foreground service would be the long-term fix.
- Android's BLE address rotation may cause a paired CSC client to see the phone as a "new" sensor across sessions.

## Credits

Protocol structure independently corroborated against [codaris.github.io/UnderDeskBike](https://codaris.github.io/UnderDeskBike/), a public reverse-engineering write-up of what appears to be the same device family.aw