# Bike Apps - Android

Connects to the bike over Bluetooth LE, decodes its proprietary telemetry
protocol, and forwards live data to a Garmin watch app via Connect IQ.

## What it does

1. **Connects to the bike as a BLE client** and drives its custom command
   protocol (ping -> device info -> quick start -> sync -> a polled record
   loop) to receive live workout telemetry.
2. **Decodes the raw byte stream** into speed, distance, workout time,
   cadence (rpm), resistance, and a few running average/max fields.
3. **Forwards decoded data to a Garmin watch app** over Connect IQ, so it
   can record a real activity using the bike's telemetry plus the watch's
   own heart rate sensor.
4. Runs as a foreground service so this keeps working with the screen
   locked, and optionally logs raw/decoded data to a local dev server for
   debugging.

## Project structure

| File | Purpose |
|---|---|
| `BLEManager.kt` | BLE central role - scans for, connects to, and drives the bike's command protocol |
| `BikeDecoder.kt` | Pure decoding logic - raw `E5`/`E6`/`E7` byte arrays -> structured `BikeData` |
| `GarminBridge.kt` | Sends decoded `BikeData` to the watch app via the Connect IQ Mobile SDK |
| `TrainingService.kt` | Foreground service owning BLE, the Garmin bridge, and websocket logging - runs independently of the UI being visible |
| `WebSocketManager.kt` | Optional logging connection to `bike-apps-server`, with auto-reconnect |
| `MainActivity.kt` | Thin UI layer - binds to `TrainingService`, displays live values, has no BLE/network logic of its own |

## The bike protocol (reverse-engineered)

The bike has no public documentation or SDK. Everything about its
protocol was reverse-engineered from a Bluetooth HCI capture (`btsnoop`)
of the manufacturer's own app, cross-checked against real ride data, and
independently corroborated against
[codaris.github.io/UnderDeskBike](https://codaris.github.io/UnderDeskBike/),
which documents what is clearly the same underlying device family.

**Transport:** a custom command/response protocol layered on top of the
Nordic UART Service (`6e400001-b5a3-f393-e0a9-e50e24dcca9e`).

**Frame format** (both directions):
```
byte 0        SOF        = 0xF9
byte 1        CMD        command/response opcode
byte 2        LEN        payload length
byte 3..      PAYLOAD    LEN bytes
byte 3+LEN    CHECKSUM   = sum(bytes[0 .. 3+LEN-1]) & 0xFF
```
Host->device writes are zero-padded to 20 bytes; device->host
notifications are exactly sized.

**Command flow:**
```
PING (0xD0 -> 0xE0)
DEVICE_INFO (0xD1 -> 0xE1)
SET_CONFIG / quick start (0xD3 -> 0xE3)
START_SYNC (0xD4 -> 0xE4)
READ_NEXT, looped (0xD5 -> 0xE5/0xE6/0xE7 triple, ~1 request/second)
```
The very first `D5` after sync starts carries a one-shot "start reading"
payload (`01` in its first byte) different from every subsequent request
(`00`) - a genuine one-time trigger, confirmed from the original capture.

**Decoded fields** (see `BikeDecoder.kt` for full detail and confidence
notes):

| Field | Confidence | Notes |
|---|---|---|
| `distance`, `speed`, `workoutTimeSeconds` | High | Verified monotonic/freeze-on-stop behavior across multiple real sessions |
| `second` / `minute` | High | Confirmed to wrap at 60s; device-persistent clock, not reset per BLE connection |
| `avgSpeed`, `maxSpeed` | High | Running average/max, verified never-decreasing across full sessions |
| `resistance` | Medium | Constant per-session at low levels; shows +-1 jitter at higher resistance settings that doesn't correlate with effort - likely a real measured value near a boundary, not transmission noise |
| `rpm` | Medium | Live cadence-like value, but unconfirmed whether it's true pedal cadence or flywheel RPM - values run higher than a hand-counted cadence would suggest |
| `powerLive/Avg/Max` (E7) | Low | A genuine live/average/max triple, but a resistance-5-vs-10 comparison ruled out the simple "power" hypothesis (higher resistance produced *lower* values at matching speed/rpm) - real meaning still unknown |
| Calories | N/A | Not transmitted by this protocol at all |

Almost every multi-byte field is a 16-bit **big-endian** value, not a
single byte - this only became apparent once real testing pushed values
past 255 and exposed silent truncation in an earlier version of the
decoder.

## The abandoned CSC approach

An earlier version of this app made the phone impersonate a standard
Bluetooth SIG "Cycling Speed and Cadence" sensor (`0x1816`), so Garmin's
built-in indoor cycling sensor search could pair to it directly - no
custom watch app needed. That code (`CscPeripheral.kt`) has been removed.

It never worked: across every test, Garmin's sensor search reported
"none found," despite the phone's advertising and GATT server being
independently verified correct (checked via a hardware-support check, a
stale-connection diagnostic, and a companion-link-disconnect test that
ruled out interference from the watch's existing Garmin Connect Mobile
pairing). The most likely explanation is that Android apps hosting a BLE
peripheral can't set the GAP Appearance code, which Garmin's sensor
picker may specifically filter on - but this was never confirmed with
certainty. Connect IQ replaced this approach entirely rather than
continuing to debug it.

## Setup

Requires:
- `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT` (Android 12+ runtime permissions)
- `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE` (manifest permissions for the background training service)
- `POST_NOTIFICATIONS` (Android 13+ runtime permission, for the required foreground-service notification)
- `WAKE_LOCK` (manifest permission, no runtime prompt)

Two values are kept out of source entirely, via `local.properties`
(gitignored) and exposed through `BuildConfig`:
```properties
SERVER_URL=ws://YOUR_DEV_MACHINE_IP:8080
GARMIN_APP_ID=<the App ID from bike-apps-garmin's manifest.xml>
```

`network_security_config.xml` (needed for cleartext websocket traffic to
a local dev server) is also gitignored - copy
`network_security_config.xml.example` to the real filename and fill in
your dev machine's IP.

The websocket connection to `bike-apps-server` is optional and only used
for debug logging - the phone-to-watch bridge via Connect IQ works
independently of it.

## Known limitations / open questions

- Pedal vs. flywheel cadence is unconfirmed - a hand-count of pedal
  revolutions compared against the live `rpm` value during a ride would
  settle this.
- The E7 "power" field's real meaning is unknown after ruling out simple
  power.
- One `E6` byte (`unknownE6`) varies but hasn't matched any tested
  hypothesis.
- `GarminBridge.kt` is built against a reconstruction of the Connect IQ
  Mobile SDK's API, corrected multiple times against real compiler
  errors as the project progressed - treat any part of it not recently
  confirmed by an actual build as still provisional.
