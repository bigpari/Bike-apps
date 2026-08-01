import Toybox.WatchUi;
import Toybox.Graphics;
import Toybox.Lang;
import Toybox.System;

// Layout mirrors the common Garmin activity-screen pattern: large
// elapsed-time field at top, a 2x2 grid of secondary fields below
// (Distance, Speed, Cadence, Resistance). This is NOT Garmin's actual
// built-in Indoor Cycling screen - that's closed-source firmware, not
// something a Connect IQ app can reuse - but it follows the same visual
// convention their own activities use, so it should feel familiar.
class BikeCyclingView extends WatchUi.View {

    function initialize() {
        View.initialize();
    }

    function onUpdate(dc as Graphics.Dc) as Void {

        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_BLACK);
        dc.clear();

        var app = getApp();
        var width = dc.getWidth();
        var height = dc.getHeight();

        // --- Elapsed time, large, top third ---
        var elapsedSeconds = 0;
        if (app.isRecording()) {
            elapsedSeconds = (System.getTimer() - app.sessionStartMillis) / 1000;
        }
        var hours = elapsedSeconds / 3600;
        var minutes = (elapsedSeconds % 3600) / 60;
        var seconds = elapsedSeconds % 60;
        var timeText = Lang.format("$1$:$2$:$3$", [
            hours.format("%02d"), minutes.format("%02d"), seconds.format("%02d")
        ]);

        dc.drawText(
            width / 2, height * 0.22,
            Graphics.FONT_NUMBER_MEDIUM,
            timeText,
            Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER
        );

        // --- 2x2 grid of secondary fields, bottom two-thirds ---
        drawField(dc, width * 0.28, height * 0.55, "DISTANCE",
            app.distanceKm.format("%.2f") + " km");

        drawField(dc, width * 0.72, height * 0.55, "SPEED",
            app.speedKmh.format("%.1f") + " km/h");

        drawField(dc, width * 0.28, height * 0.80, "CADENCE",
            app.rpm.format("%d") + " rpm");

        drawField(dc, width * 0.72, height * 0.80, "RESISTANCE",
            app.resistance.format("%d"));

        // --- Recording state indicator ---
        var statusText = app.isRecording() ? "RECORDING" : "PRESS START";
        dc.drawText(
            width / 2, height * 0.95,
            Graphics.FONT_XTINY,
            statusText,
            Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER
        );
    }

    private function drawField(dc as Graphics.Dc, x as Float, y as Float, label as String, value as String) as Void {
        dc.setColor(Graphics.COLOR_LT_GRAY, Graphics.COLOR_TRANSPARENT);
        dc.drawText(x, y - 14, Graphics.FONT_XTINY, label,
            Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);

        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_TRANSPARENT);
        dc.drawText(x, y + 6, Graphics.FONT_MEDIUM, value,
            Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
    }
}
