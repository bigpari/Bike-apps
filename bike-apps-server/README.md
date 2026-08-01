# Bike Apps - Server

An optional local Python WebSocket server used during development to log
raw and decoded BLE traffic from the Android app for debugging and
protocol analysis. Not required for the app to work - the phone-to-watch
Connect IQ bridge in `bike-apps-android` operates independently of this
server entirely.

## What it does

Listens on port `8080`, accepts a WebSocket connection from the Android
app, and for each session:

- Saves every message received, as-is, to a timestamped
  `captures/session_<timestamp>.jsonl` file
- When a full `E5`/`E6`/`E7` record triple arrives, decodes it (via
  `decoder.py` - a Python port of the same logic as `BikeDecoder.kt` on
  the Android side) and logs both the raw record and the decoded result

## Running it

```
python server.py
```

Requires the `websockets` package. `decoder.py` must be present alongside
`server.py` - it's imported directly, not a separate package.

Point the Android app at this server's IP via `local.properties`'
`SERVER_URL` (see `bike-apps-android/README.md`) - `192.168.x.x`-style
local network addresses only, since this server isn't designed to be
exposed beyond your local network.

## Output

Captures are written to `captures/`, which is gitignored - this is raw
personal ride data, not something to commit. Every reconnect currently
starts a **new** session file rather than continuing the previous one
(each WebSocket connection gets its own `CaptureSession`) - worth knowing
if you're looking at a set of captures from a ride that had any
connection drops, since the ride's data may be split across multiple
files rather than one continuous log.

## Note on `decoder.py`

This is a separate implementation from `BikeDecoder.kt` in the Android
app - same underlying protocol knowledge, same field offsets, but two
independently maintained files in two different languages. If the bike
protocol understanding changes (a new field identified, a bug found in
the byte offsets), both need updating - there's no shared source of
truth between them currently.
