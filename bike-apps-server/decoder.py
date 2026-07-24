class BikeDecoder:
    """
    Decodes the F9 D5/E5/E6/E7 "workout" record triple.

    IMPORTANT FIX vs. the previous version: almost every multi-byte field
    in this protocol is a genuine 16-bit big-endian value (high byte at
    the lower address, low byte after it) - not the single byte I'd been
    reading. This only became visible once a session pushed hard enough
    (high resistance, high effort) that a field actually exceeded 255 and
    needed its second byte. At lower effort the high byte just sits at 0
    and everything looks fine with only the low byte - which is exactly
    why this went unnoticed for a while.

    Concretely, this fixes:
    - workout_time_s: previously looked like it wrapped at 256. It
      doesn't - it's a real 2-byte counter. Confirmed zero decreases
      across two full sessions once read correctly. It also turns out to
      be a persistent device-side clock, NOT reset per BLE connection -
      it kept climbing past a session's own wall-clock duration because
      it was still counting from earlier sessions today.
    - speed and rpm (E5): previously silently capped/wrapped around 25.5
      mph / 255 rpm. Confirmed against E6's max_speed field, which is
      itself a genuine 2-byte running max - the two now agree exactly.
    - avg_speed and max_speed (E6): same fix. This also resolved an
      earlier apparent impossibility (avg_speed > max_speed in the same
      record) - that was purely this bug, not a real data anomaly.

    "second" (E5) and its own preceding byte are the one exception to the
    "combine as one 16-bit value" rule: "second" independently verified
    to wrap at exactly 60 (not 256), so the preceding byte is read here as
    whole MINUTES elapsed on the device's persistent clock, not a high
    byte of a raw binary counter. Both are still device-persistent (not
    reset per connection), matching workout_time_s's behavior.

    Field provenance:
    - E5 fields are independently confirmed: monotonicity checks, wrap
      checks, freeze-on-stop behavior, and cross-referenced against
      https://codaris.github.io/UnderDeskBike/ (same protocol family -
      their Connect/Hold/Info1/Info2/Start-Continue-Workout commands
      match our D0-D5/E0-E7 almost byte-for-byte).
    - E6's avg/max speed and avg rpm, and E7's live/avg/max triple, were
      reverse-engineered structurally from real varying-effort sessions,
      not from the article (which never fully identified them either).

    Still open:
    - E7's live/avg/max triple was a strong candidate for power (watts),
      but a resistance-5-vs-resistance-10 comparison at matching speed/
      rpm showed LOWER values at higher resistance - the opposite of what
      real power should do. That rules out the simple "power" hypothesis.
      Kept in the output as power_live/power_avg/power_max for now since
      it's still a coherent live/avg/max triple, just with an unconfirmed
      real-world meaning - treat the "power" name as a placeholder, not a
      confirmed unit.
    - rpm: still unconfirmed whether this is true pedal cadence or
      flywheel rpm - values run noticeably higher than a hand-counted
      cadence would be expected to, consistent with a flywheel:pedal gear
      ratio around 1.3-1.5x, but unverified.
    - One E6 byte (offset 15) varies but hasn't matched any hypothesis
      tested so far (running-max of rpm, of the E7 metric, or of its own
      avg-rpm field). Passed through raw.
    - No calories field exists in this protocol at all, per the article's
      own field list. Compute it yourself if you need it.
    - avg_rpm and the E7 triple haven't yet been observed needing a 2nd
      byte in testing (their high bytes stayed 0 even in the hardest
      session so far) - but given how many *other* fields turned out to
      need one, don't assume they're safe at higher effort than tested.
    """

    def __init__(self):
        self.e5 = None
        self.e6 = None
        self.e7 = None

    def process(self, packet: bytes):
        if len(packet) < 20 or packet[0] != 0xF9:
            return None

        cmd = packet[1]

        if cmd == 0xE5:
            self.e5 = packet
        elif cmd == 0xE6:
            self.e6 = packet
        elif cmd == 0xE7:
            self.e7 = packet

        if self.e5 and self.e6 and self.e7:
            return self.decode()

        return None

    def reset(self):
        """Clears state. Called by the server script AFTER saving."""
        self.e5 = None
        self.e6 = None
        self.e7 = None

    def decode(self):
        e5 = self.e5
        e6 = self.e6
        e7 = self.e7

        data = {
            # --- E5: live instantaneous values ---

            # Device's persistent clock, MM:SS - NOT reset per BLE
            # connection. "second" independently confirmed to wrap at 60.
            "minute": e5[3],
            "second": e5[4],

            # Distance in hundredths of a mile. Confirmed monotonic across
            # full sessions. High byte (e5[5]) hasn't been observed
            # nonzero yet, but included for safety at longer distances.
            "distance": (e5[5] * 256 + e5[6]) / 100.0,

            # Total workout time in seconds - device-persistent, confirmed
            # to never decrease once read as the full 2-byte value.
            "workout_time_s": e5[7] * 256 + e5[8],

            # Speed in tenths of mph. Fluctuates with effort, drops to
            # zero immediately when you stop. Now reads the full 2-byte
            # value - confirmed against E6's max_speed at 45.0 mph.
            "speed": (e5[9] * 256 + e5[10]) / 10.0,

            # Rotations per minute. Also fluctuates with effort, drops to
            # zero immediately when you stop.
            "rpm": e5[11] * 256 + e5[12],

            # Confirmed constant per-session - the resistance level set
            # by the Quick Start / preset command. Has shown +-1 jitter
            # at resistance 10 (not seen at resistance 5) - likely a real
            # measured value sitting near a boundary, not transmission
            # noise, since it doesn't correlate with speed/rpm changes.
            "resistance": e5[13],

            # --- E6: running average/max of speed, running average of rpm ---

            # Running average speed so far this session, same units as
            # "speed" above. Starts at 0 and climbs gradually toward your
            # actual pace rather than tracking it instantly.
            "avg_speed": (e6[3] * 256 + e6[4]) / 10.0,

            # Running max speed so far this session. Never decreases;
            # confirmed to land exactly on the true session peak.
            "max_speed": (e6[5] * 256 + e6[6]) / 10.0,

            # Running average rpm, RAW value - tracks rpm/10 with a short
            # lag. Left un-multiplied because it's not confirmed whether
            # the device genuinely stores avg-rpm/10, or this is some
            # other coarser quantity that happens to correlate.
            "avg_rpm_raw": e6[8],

            # Unidentified - passed through raw.
            "unknown_e6": e6[18],

            # --- E7: live/avg/max triple, meaning still unconfirmed ---
            # See "Still open" above - name is a placeholder, not a
            # confirmed unit. Ruled out as simple power (watts) by a
            # resistance comparison; kept as-is pending a better idea.

            "power_live": e7[9],
            "power_avg": e7[10],
            "power_max": e7[11],
        }

        return data