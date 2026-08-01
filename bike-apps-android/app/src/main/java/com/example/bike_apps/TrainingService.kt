package com.example.bike_apps

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat

/**
 * Owns the bike BLE connection and websocket
 * logging - runs as a foreground service so this keeps working when the
 * screen locks or the app is backgrounded, which a plain PARTIAL_WAKE_LOCK
 * held by an Activity does NOT reliably guarantee (confirmed by two real
 * failures - the wake lock kept the CPU running, but the Activity itself
 * still got backgrounded/throttled).
 *
 * MainActivity binds to this service and registers a Listener to receive
 * UI updates; the service itself keeps running independently of whether
 * anything is bound to it, as long as it's been started with
 * startForegroundService().
 */
class TrainingService : Service() {

    interface Listener {
        fun onLog(message: String)
        fun onDecoded(bike: BikeData)
    }

    private var listener: Listener? = null

    fun setListener(l: Listener?) {
        listener = l
    }

    inner class LocalBinder : Binder() {
        fun getService(): TrainingService = this@TrainingService
    }

    private val binder = LocalBinder()

    override fun onBind(intent: Intent): IBinder = binder


    private lateinit var ble: BLEManager
    private lateinit var garmin: GarminBridge
    private lateinit var websocket: WebSocketManager

    private var wakeLock: PowerManager.WakeLock? = null

    private var training = false


    override fun onCreate() {
        super.onCreate()

        createNotificationChannel()

        websocket = WebSocketManager { message ->
            listener?.onLog(message)
        }

        websocket.connect(BuildConfig.SERVER_URL)

        garmin = GarminBridge(this) { message ->
            handleLog(message)
        }
        garmin.initialize()

        ble = BLEManager(
            this,

            // LOG CALLBACK
            { message ->
                handleLog(message)
            },

            // RAW DATA CALLBACK
            { data ->
                val hex = data.joinToString(" ") { "%02X".format(it) }
                websocket.send(
                    """
                    {"type":"BLE_DATA","data":"$hex"}
                    """.trimIndent()
                )
            },

            // DECODED DATA CALLBACK
            { bike ->
                listener?.onDecoded(bike)
                garmin.sendBikeData(bike)
                updateNotification(
                    "Speed ${"%.1f".format(bike.speedMph * 1.609344)} km/h  " +
                            "RPM ${bike.rpm}"
                )
                websocket.send(
                    """
                    {"type":"DECODED","data":{
                      "minute":${bike.minute},"second":${bike.second},
                      "distanceMiles":${bike.distanceMiles},
                      "workoutTimeSeconds":${bike.workoutTimeSeconds},
                      "speedMph":${bike.speedMph},"rpm":${bike.rpm},
                      "resistance":${bike.resistance},
                      "avgSpeedMph":${bike.avgSpeedMph},
                      "maxSpeedMph":${bike.maxSpeedMph},
                      "avgRpmRaw":${bike.avgRpmRaw},
                      "unknownE6":${bike.unknownE6},
                      "powerLive":${bike.powerLive},
                      "powerAvg":${bike.powerAvg},"powerMax":${bike.powerMax}
                    }}
                    """.trimIndent()
                )
            }
        )
    }


    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification("Starting..."))
        return START_STICKY
    }


    fun startTraining() {

        if (training) return
        training = true

        listener?.onLog("Starting BLE scan...")

        ble.scan()

        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "BikeApps::TrainingServiceWakeLock"
        )
        wakeLock?.acquire(3 * 60 * 60 * 1000L)

        updateNotification("Connecting to bike...")
    }


    fun stopTraining() {
        ble.stopTraining()
    }


    fun isTraining(): Boolean = training


    private fun handleLog(message: String) {

        listener?.onLog(message)

        websocket.send(
            """
            {"type":"BLE","data":"$message"}
            """.trimIndent()
        )

        if (message == "TRAINING STOPPED") {

            training = false

            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = null

            updateNotification("Stopped")
        }
    }


    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Bike training",
                NotificationManager.IMPORTANCE_LOW
            )
            channel.description = "Shows while connected to the bike and broadcasting to Garmin"
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }


    private fun buildNotification(text: String): Notification {

        val openAppIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Bike training active")
            .setContentText(text)
            // Placeholder icon - swap for a real app icon resource before
            // shipping, a notification needs a valid small icon to show.
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }


    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }


    override fun onDestroy() {
        ble.stopTraining()
        garmin.shutdown()
        websocket.close()
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }


    companion object {
        const val CHANNEL_ID = "bike_training_channel"
        const val NOTIFICATION_ID = 1
    }
}