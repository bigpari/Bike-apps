# Bike Apps

Gets real telemetry off a proprietary BLE indoor exercise bike and onto a
Garmin watch as a proper Indoor Cycling activity - with the watch's own
heart rate and calories included - so it syncs to Garmin Connect and then
Strava automatically, the same as any other ride.

The bike has no public protocol documentation or SDK. Everything about
how it talks over Bluetooth was reverse-engineered from a packet capture
of the manufacturer's own app, then verified against real ride data.

## How it fits together

```
 Bike  --BLE-->  Phone (bike-apps-android)  --Connect IQ-->  Garmin Watch (bike-apps-garmin)
                        |                                          |
                        | optional websocket, for debugging        | records ActivityRecording
                        v                                          v
                 bike-apps-server                          Garmin Connect --> Strava
                 (local dev logging)
```

The phone app connects to the bike as a BLE client, decodes its
proprietary telemetry, and forwards it to a companion app running on the
watch via Garmin's Connect IQ Mobile SDK (which itself routes through
Garmin Connect Mobile - not a direct link). The watch app records a real
activity session, tagged as indoor cycling, using that data alongside its
own hardware heart rate sensor.

An earlier approach tried making the phone impersonate a standard
Bluetooth "Cycling Speed and Cadence" sensor so Garmin's built-in sensor
search could find it directly. That never worked reliably (Garmin's
sensor search consistently reported nothing found despite the phone
advertising correctly) - see `bike-apps-android/README.md` for the full
investigation. Connect IQ replaced that approach entirely.

## Projects

| Directory | What it is |
|---|---|
| [`bike-apps-android/`](./bike-apps-android/README.md) | The phone app - connects to the bike, decodes its protocol, bridges data to the watch |
| [`bike-apps-garmin/`](./bike-apps-garmin/README.md) | Connect IQ watch app - receives bike data, records the activity |
| [`bike-apps-server/`](./bike-apps-server/README.md) | Optional local Python server for logging/debugging raw and decoded BLE traffic during development |

Each has its own README with real setup steps - start there for anything
project-specific.

## Status

- Bike protocol: reverse-engineered and decoding correctly (distance,
  speed, workout time, resistance all independently verified against
  real ride data; cadence's exact scale - pedal vs. flywheel rpm - is
  still unconfirmed).
- Phone -> watch bridge: built, using a reconstruction of Garmin's
  Connect IQ Mobile SDK API that's been corrected against real compiler
  errors along the way, but hasn't yet been confirmed end-to-end on a
  real ride.
- Whether the watch-side FitContributor fields feed the activity's
  native/primary distance-speed-cadence (matching what a real sensor
  pairing would produce) or only show up as supplementary custom charts
  is still an open question - see `bike-apps-garmin/README.md`.
