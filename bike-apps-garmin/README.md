# Bike Apps - Garmin (Connect IQ)

A Connect IQ watch app that receives live bike telemetry from the phone
and records it as a real Indoor Cycling activity - so it syncs to Garmin
Connect and Strava like any other ride, with the watch's own heart rate
and calories included alongside the bike's speed/distance/cadence.

## Why a watch app, not a standard sensor pairing

The original plan was simpler: make the phone impersonate a standard
Bluetooth Cycling Speed and Cadence sensor, and pair it directly through
Garmin's built-in indoor cycling sensor search - no custom watch code at
all. That never worked (see `bike-apps-android/README.md` for the full
investigation) despite the phone-side implementation checking out
correctly in every test. This watch app exists because that route was
abandoned, not because it's the simpler option.

## Project structure

| File | Purpose |
|---|---|
| `manifest.xml` | App type, target device, permissions - see below for what's required |
| `source/BikeCyclingApp.mc` | App entry point; receives phone messages, owns the `ActivityRecording.Session`, pushes data into `FitContributor` fields |
| `source/BikeCyclingView.mc` | Display - large elapsed time, a 2x2 grid of distance/speed/cadence/resistance |
| `source/BikeCyclingDelegate.mc` | SELECT button toggles recording start/stop |
| `resources/fit-contributions/fit.xml` | Declares the FIT field IDs used in code - **required**, or recorded data silently won't display, per Garmin's own docs |
| `resources/strings/strings.xml` | App name plus labels/units for the FIT fields |

This project was originally scaffolded from Garmin's **Watch Face**
template before being repurposed - if you're starting fresh, generate a
**Watch App** project instead (VS Code's Monkey C extension: New Project
-> Watch App, not Watch Face). All Watch Face boilerplate
(`Background.mc`, the watch-face `layout.xml`, `properties.xml`,
`settings.xml`) has been removed from this project - none of it was ever
used by the bike app code.

## Setup

- Target device: `venusqm` (Venu Sq **Music** Edition specifically - the
  plain `venusq` product ID is a different, incompatible device entry)
- `minApiLevel`: `3.3.0` (the Venu Sq Music's max supported Connect IQ
  API level - `SPORT_CYCLING`/`SUB_SPORT_INDOOR_CYCLING`/`FitContributor`
  all only require 3.2.0 or earlier, so this is covered)
- Permissions required in `manifest.xml`: `Communications` (receiving
  phone messages), `Fit` and `FitContributor` (recording the activity) -
  confirmed required by real build errors, not just assumed from docs
- App ID: generated automatically when you create the project in VS
  Code - copy the real value from this project's own `manifest.xml` into
  the Android app's `local.properties` as `GARMIN_APP_ID`

Use "Monkey C: Edit Permissions" from VS Code's command palette to
change permissions rather than hand-editing `manifest.xml` directly -
it's a generated file.

## Open question: does FitContributor feed the *real* activity data?

This is the biggest unresolved question in this project. Garmin's own
docs describe `FitContributor` field IDs as "used to refer to your
field" within your own app, with no duplicates allowed *within an app* -
phrasing that reads like these are custom supplementary chart fields
layered onto an activity, not a way to feed the activity's native,
primary distance/speed/cadence (the numbers that drive the main ride
summary and map).

If that's correct, a recorded ride might show custom "Bike Distance" /
"Bike Speed" / "Bike Cadence" charts in Garmin Connect, while the
activity's actual headline distance/speed stays at zero (no GPS, no
natively-recognized sensor) - which would partly undercut the reason for
building this at all. This has **not** been confirmed either way against
Garmin's actual `ActivityRecording.Session` class reference - only
tested empirically by checking how a real recorded ride displays in
Garmin Connect afterward.

## API accuracy notes

Class names, method signatures, and permission requirements in this
project have gone through several rounds of correction against **real
compiler errors** from actual builds - trust those corrections over any
comment describing something as "per the docs," since the publicly
extracted documentation used earlier in this project turned out to
describe a different SDK version than what's actually resolved via
Gradle (`com.garmin.connectiq:ciq-companion-app-sdk:2.2.0`). Specific
corrections made this way:
- `ConnectIQ.getInstance(context, type)` - two arguments, not one
- `onInitializeError` - not `onInitializeationError`
- `Communications.PhoneAppMessage` - the exact type
  `registerForPhoneAppMessages`'s callback must accept
- `getStatus()`/`IQDeviceStatus` do not exist in the resolved library -
  removed, fell back to a simpler known-devices check
