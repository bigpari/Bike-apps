import Toybox.WatchUi;
import Toybox.Lang;

// SELECT button (usually the top-right or middle button, device-
// dependent) toggles recording, matching the press-to-start feel of a
// real activity rather than auto-starting the moment phone data arrives.
// If you'd rather it auto-start on first data, that's a one-line change
// in BikeCyclingApp.updateBikeData() instead of requiring this button -
// worth deciding once you're actually using this day to day.
class BikeCyclingDelegate extends WatchUi.BehaviorDelegate {

    function initialize() {
        BehaviorDelegate.initialize();
    }

    function onSelect() as Boolean {
        var app = getApp();

        if (app.isRecording()) {
            app.stopSession();
        } else {
            app.startSession();
        }

        WatchUi.requestUpdate();
        return true;
    }

    function onBack() as Boolean {
        // Let BACK exit normally rather than swallowing it - don't want
        // an accidental stuck app if something's gone wrong with a ride.
        return false;
    }
}
