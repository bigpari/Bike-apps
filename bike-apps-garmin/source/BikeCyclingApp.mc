import Toybox.Application;
import Toybox.Communications;
import Toybox.Lang;
import Toybox.WatchUi;

// Entry point. Holds the current BikeData snapshot that the view reads
// from - the phone-side bridge (next step) will call updateBikeData()
// as new decoded records arrive, same shape as BikeData.kt on the phone.
class BikeCyclingApp extends Application.AppBase {

    // Mirrors BikeData.kt's fields, kept as simple as Monkey C's type
    // system needs. Distance/speed already converted to km/km-h at this
    // point (matching what the phone's own UI shows), NOT the raw
    // miles/mph BikeDecoder.kt returns - convert on the phone side before
    // sending, so this app doesn't need to duplicate that logic.
    var distanceKm as Float = 0.0;
    var speedKmh as Float = 0.0;
    var rpm as Number = 0;
    var resistance as Number = 0;

    // Session lifecycle - see BikeCyclingView/Delegate for start/stop UI.
    var recordingSession as Toybox.ActivityRecording.Session?;
    var sessionStartMillis as Number = 0;


    function initialize() {
        AppBase.initialize();
    }

    function onStart(state as Dictionary?) as Void {
        // registerForPhoneAppMessages CONFIRMED correct by a real build -
        // the compiler error before this fix named the exact expected
        // callback type, which is what onPhoneMessage now uses below.
        Communications.registerForPhoneAppMessages(method(:onPhoneMessage));
    }

    // Signature confirmed by the actual compiler error this replaced:
    // "Invalid '$.Toybox.Lang.Method(data as Any) as Void' passed...
    // expected PolyType<Null or ($.Toybox.Lang.Method(msg as
    // $.Toybox.Communications.PhoneAppMessage) as Void)>". The payload
    // itself is msg.data - UNVERIFIED that ".data" is the right property
    // name specifically (that part isn't spelled out by the error), but
    // it's the standard pattern for this kind of wrapper object. If this
    // doesn't work, checking PhoneAppMessage's own property list is the
    // next place to look.
    //
    // Order here MUST match the list GarminBridge.kt builds in
    // sendBikeData(): [distanceKm, speedKmh, rpm, resistance].
    function onPhoneMessage(msg as Communications.PhoneAppMessage) as Void {
        var data = msg.data;
        if (data instanceof Array && data.size() >= 4) {
            updateBikeData(
                data[0].toFloat(),
                data[1].toFloat(),
                data[2].toNumber(),
                data[3].toNumber()
            );
        }
    }

    function onStop(state as Dictionary?) as Void {
        // Make sure a session doesn't get silently orphaned if the app
        // is killed mid-ride.
        if (recordingSession != null && recordingSession.isRecording()) {
            recordingSession.stop();
            recordingSession.save();
        }
    }

    function getInitialView() as [Views] or [Views, InputDelegates] {
        return [new BikeCyclingView(), new BikeCyclingDelegate()];
    }

    // Called by the phone-side bridge handler (next step) each time a
    // fresh BikeData record arrives.
    function updateBikeData(newDistanceKm as Float, newSpeedKmh as Float, newRpm as Number, newResistance as Number) as Void {
        distanceKm = newDistanceKm;
        speedKmh = newSpeedKmh;
        rpm = newRpm;
        resistance = newResistance;

        if (recordingSession != null && recordingSession.isRecording()) {
            recordFields();
        }

        WatchUi.requestUpdate();
    }

    // Pushes the latest values into the FIT recording via FitContributor.
    // Field numbers/units below are the STANDARD FIT "record" message
    // fields for distance/speed/cadence - using these (rather than
    // arbitrary custom field numbers) is what makes Garmin Connect/Strava
    // display them as real distance/speed/cadence instead of an unlabeled
    // custom data field. Double-check these against the current
    // Toybox.FitContributor API reference when you build this - field
    // IDs and the exact constructor signature have shifted across SDK
    // versions and this is worth confirming rather than trusting blindly.
    function recordFields() as Void {
        if (distanceField != null) {
            distanceField.setData(distanceKm * 1000.0); // FIT wants meters
        }
        if (speedField != null) {
            speedField.setData(speedKmh / 3.6); // FIT wants m/s
        }
        if (cadenceField != null) {
            cadenceField.setData(rpm);
        }
    }

    var distanceField as Toybox.FitContributor.Field?;
    var speedField as Toybox.FitContributor.Field?;
    var cadenceField as Toybox.FitContributor.Field?;

    function startSession() as Void {
        if (recordingSession != null && recordingSession.isRecording()) {
            return;
        }

        recordingSession = Toybox.ActivityRecording.createSession({
            :name => "Indoor Cycling",
            :sport => Toybox.ActivityRecording.SPORT_CYCLING,
            :subSport => Toybox.ActivityRecording.SUB_SPORT_INDOOR_CYCLING
        });

        // FIT_FIELD numbers below correspond to the standard "record"
        // message: distance=5, speed=6, cadence=4. VERIFY against current
        // Toybox.FitContributor docs before relying on this - noting the
        // uncertainty explicitly rather than presenting it as certain.
        distanceField = recordingSession.createField(
            "distance", 5, Toybox.FitContributor.DATA_TYPE_FLOAT,
            { :mesgType => Toybox.FitContributor.MESG_TYPE_RECORD, :units => "m" }
        );
        speedField = recordingSession.createField(
            "speed", 6, Toybox.FitContributor.DATA_TYPE_FLOAT,
            { :mesgType => Toybox.FitContributor.MESG_TYPE_RECORD, :units => "m/s" }
        );
        cadenceField = recordingSession.createField(
            "cadence", 4, Toybox.FitContributor.DATA_TYPE_UINT8,
            { :mesgType => Toybox.FitContributor.MESG_TYPE_RECORD, :units => "rpm" }
        );

        recordingSession.start();
        sessionStartMillis = Toybox.System.getTimer();
    }

    function stopSession() as Void {
        if (recordingSession == null || !recordingSession.isRecording()) {
            return;
        }
        recordingSession.stop();
        recordingSession.save();
        recordingSession = null;
    }

    function isRecording() as Boolean {
        return recordingSession != null && recordingSession.isRecording();
    }
}

function getApp() as BikeCyclingApp {
    return Application.getApp() as BikeCyclingApp;
}
