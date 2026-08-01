package com.example.bike_apps

/*
 * UPDATED against real Garmin documentation the user extracted directly
 * from developer.garmin.com (my own fetch attempts got JS-rendered nav
 * only, not the article text - the extract was the actual fix for that).
 *
 * CONFIRMED correct against that extract:
 * - ConnectIQ.getInstance(IQConnectType) - single argument, no context
 * - initialize(context, autoUI, listener) - context goes here instead
 * - ConnectIQListener.onInitializationError(IQSdkErrorStatus) - NOT
 *   onInitializeError, which is what this file had before and was wrong
 * - getKnownDevices() / getStatus(device) / IQDeviceStatus enum values
 *   (CONNECTED / NOT_CONNECTED / NOT_PAIRED)
 * - sendMessage(device, app, List<Object>, IQSendMessageListener) shape
 *
 * STILL UNCONFIRMED:
 * - Exact Java package for these classes (com.garmin.android.connectiq
 *   below is a reasonable guess given the SDK's history, but the extract
 *   didn't show an explicit import statement to confirm it)
 * - The watch-side Communications.registerForPhoneAppMessages() API used
 *   in BikeCyclingApp.mc - the extract confirmed the general "mailbox"
 *   messaging model exists but didn't show this specific method name
 *
 * OPEN QUESTION, not a bug: whether FitContributor fields (used in
 * BikeCyclingApp.mc for distance/speed/cadence) feed the activity's
 * native/primary metrics or only show up as supplementary custom charts.
 * The extract's field-ID docs read like the latter. Worth confirming
 * against the ActivityRecording.Session class reference specifically
 * before assuming this gets you the same result as a real sensor pairing.
 */

import android.content.Context
import com.garmin.android.connectiq.ConnectIQ
import com.garmin.android.connectiq.IQApp
import com.garmin.android.connectiq.IQDevice

class GarminBridge(
    private val context: Context,
    private val log: (String) -> Unit
) {

    // Must exactly match the id field in the watch app's manifest.xml.
    // Sourced from BuildConfig (see local.properties/GARMIN_APP_ID) so
    // it's not hardcoded here - update local.properties when you
    // regenerate the Watch App project and get its real generated ID,
    // rather than editing this file.
    //
    // (Correction to an earlier note here: the Watch Face vs. Watch App
    // type distinction turned out NOT to be what was blocking
    // ActivityRecording/Communications - a real build showed those were
    // just missing permission declarations. Still worth using a proper
    // Watch App project regardless, just not for the reason first
    // assumed.)
    private val WATCH_APP_ID = BuildConfig.GARMIN_APP_ID

    // REVERTED again: the doc extract's single-arg getInstance(type) does
    // NOT match what's actually in the resolved Gradle dependency - a
    // real build error confirmed the two-arg overload below is what
    // actually exists. The doc extract apparently describes a different
    // SDK version than 2.2.0. Trust the compiler over the docs from here.
    private val connectIQ: ConnectIQ =
        ConnectIQ.getInstance(context, ConnectIQ.IQConnectType.WIRELESS)

    private var watchDevice: IQDevice? = null
    private var watchApp: IQApp? = null
    private var sdkReady = false


    fun initialize() {

        connectIQ.initialize(context, true, object : ConnectIQ.ConnectIQListener {

            override fun onSdkReady() {
                sdkReady = true
                log("GARMIN SDK READY")
                findDevice()
            }

            // REVERTED: real compiler error confirmed onInitializeError is
            // the actual abstract member, not onInitializationError.
            override fun onInitializeError(status: ConnectIQ.IQSdkErrorStatus) {
                log("GARMIN SDK INIT ERROR: $status")
            }

            override fun onSdkShutDown() {
                sdkReady = false
                log("GARMIN SDK SHUT DOWN")
            }
        })
    }


    private fun findDevice() {

        try {
            // getStatus()/IQDeviceStatus REMOVED - a real compiler error
            // confirmed neither exists in the resolved library (same
            // version-mismatch issue as the two fixes above). Falling
            // back to the simpler approach: just take the first known
            // device without a separate connected-status check. Less
            // informative logging if the watch is paired but not
            // currently connected, but at least it compiles against what
            // actually exists.
            val devices = connectIQ.knownDevices ?: emptyList()

            if (devices.isEmpty()) {
                log("GARMIN: no known devices - open Garmin Connect " +
                        "Mobile and pair your watch there first")
                return
            }

            // Assumes exactly one watch. If you ever pair more than one
            // Garmin device, this needs to pick the right one instead of
            // just taking the first.
            val device = devices[0]

            watchDevice = device
            watchApp = IQApp(WATCH_APP_ID)

            log("GARMIN: using device ${device.friendlyName}")

        } catch (e: Exception) {
            log("GARMIN ERROR finding device: $e")
        }
    }


    fun sendBikeData(bike: BikeData) {

        val device = watchDevice ?: return
        val app = watchApp ?: return

        if (!sdkReady) return

        // Connect IQ messages are simple Lists/primitives, not arbitrary
        // objects - matches Monkey C's own limited type system on the
        // receiving end. Order here MUST match the parsing order in
        // BikeCyclingApp.mc's onPhoneMessage.
        val message = listOf(
            bike.distanceMiles * 1.609344, // -> km
            bike.speedMph * 1.609344,      // -> km/h
            bike.rpm,
            bike.resistance
        )

        try {
            connectIQ.sendMessage(device, app, message, object : ConnectIQ.IQSendMessageListener {
                override fun onMessageStatus(
                    device: IQDevice,
                    app: IQApp,
                    status: ConnectIQ.IQMessageStatus
                ) {
                    if (status != ConnectIQ.IQMessageStatus.SUCCESS) {
                        log("GARMIN SEND FAILED: $status")
                    }
                }
            })
        } catch (e: Exception) {
            log("GARMIN SEND EXCEPTION: $e")
        }
    }


    fun shutdown() {
        try {
            connectIQ.shutdown(context)
        } catch (e: Exception) {
            log("GARMIN SHUTDOWN ERROR: $e")
        }
    }
}