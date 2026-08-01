package com.example.bike_apps

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity


class MainActivity : AppCompatActivity(), TrainingService.Listener {


    private var service: TrainingService? = null

    private var serviceBound = false


    private lateinit var status: TextView

    private lateinit var trainingButton: Button

    private lateinit var logTitle: TextView

    private lateinit var logContainer: View


    private lateinit var timeValue: TextView

    private lateinit var caloriesValue: TextView

    private lateinit var distanceValue: TextView

    private lateinit var speedValue: TextView

    private lateinit var powerValue: TextView

    private lateinit var rpmValue: TextView

    private lateinit var resistanceValue: TextView


    private var logsExpanded = true

    // Wall-clock time of this session's start - see updateTrainingData for
    // why this is used instead of the bike's own workoutTimeSeconds field.
    private var sessionStartMillis: Long = 0L




    private val connection = object : ServiceConnection {

        override fun onServiceConnected(name: ComponentName, binder: IBinder) {

            service = (binder as TrainingService.LocalBinder).getService()
            serviceBound = true

            service?.setListener(this@MainActivity)

            trainingButton.text =
                if (service?.isTraining() == true) "Stop Training" else "Start Training"

        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            serviceBound = false
        }

    }




    private val permissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) {

            if (it[Manifest.permission.BLUETOOTH_SCAN] == true) {

                beginTraining()

            } else {

                addLog("Bluetooth permission denied")

            }

        }




    override fun onCreate(savedInstanceState: Bundle?) {

        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main)


        status = findViewById(R.id.status)
        trainingButton = findViewById(R.id.trainingButton)
        logTitle = findViewById(R.id.logTitle)
        logContainer = findViewById(R.id.logContainer)
        timeValue = findViewById(R.id.timeValue)
        caloriesValue = findViewById(R.id.caloriesValue)
        distanceValue = findViewById(R.id.distanceValue)
        speedValue = findViewById(R.id.speedValue)
        powerValue = findViewById(R.id.powerValue)
        rpmValue = findViewById(R.id.rpmValue)
        resistanceValue = findViewById(R.id.resistanceValue)


        // This protocol doesn't transmit calories at all (confirmed
        // against a public reverse-engineering of the same device
        // family) - shown as unavailable rather than wired to a fake
        // number.
        caloriesValue.text = "Calories: not available"


        trainingButton.setOnClickListener {

            val currentlyTraining = service?.isTraining() ?: false

            if (currentlyTraining) {

                service?.stopTraining()

            } else {

                requestPermission()

            }

        }


        logTitle.setOnClickListener {
            toggleLogs()
        }


        // Start the service so it keeps running independently of binding,
        // then bind to it for direct calls + UI callbacks. This combo
        // (started + bound) is what lets the service outlive the Activity
        // being backgrounded, unlike everything living in MainActivity
        // directly as before.
        val serviceIntent = Intent(this, TrainingService::class.java)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE)

    }




    override fun onStart() {
        super.onStart()

        if (!serviceBound) {
            val serviceIntent = Intent(this, TrainingService::class.java)
            bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE)
        }
    }


    override fun onStop() {
        super.onStop()

        // Unbinding does NOT stop the service - it keeps running in the
        // background exactly as intended. This just stops forwarding
        // callbacks to a UI that isn't visible anyway.
        if (serviceBound) {
            service?.setListener(null)
            unbindService(connection)
            serviceBound = false
        }
    }




    private fun requestPermission() {

        if (Build.VERSION.SDK_INT >= 31) {

            val permissions = mutableListOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE
            )

            if (Build.VERSION.SDK_INT >= 33) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }

            permissionLauncher.launch(permissions.toTypedArray())

        } else {

            beginTraining()

        }

    }




    private fun beginTraining() {

        sessionStartMillis = System.currentTimeMillis()

        trainingButton.text = "Stop Training"

        service?.startTraining()

    }




    // --- TrainingService.Listener callbacks ---

    override fun onLog(message: String) {

        addLog(message)

        if (message == "TRAINING STOPPED") {

            runOnUiThread {
                trainingButton.text = "Start Training"
            }

        }

    }


    override fun onDecoded(bike: BikeData) {

        runOnUiThread {
            updateTrainingData(bike)
        }

    }




    private fun updateTrainingData(bike: BikeData) {

        // NOT bike.workoutTimeSeconds - that field is device-persistent
        // (doesn't reset per BLE connection), so it runs ahead by however
        // much time was spent on the bike in past sessions. Wall-clock
        // elapsed-since-this-session-started is what "Time" should mean.
        val elapsedSeconds =
            ((System.currentTimeMillis() - sessionStartMillis) / 1000L).toInt()

        val hours = elapsedSeconds / 3600
        val minutes = (elapsedSeconds % 3600) / 60
        val seconds = elapsedSeconds % 60

        timeValue.text =
            "Time: %02d:%02d:%02d".format(hours, minutes, seconds)

        distanceValue.text =
            "Distance: %.2f km".format(bike.distanceMiles * 1.609344)

        speedValue.text =
            "Speed: %.1f km/h".format(bike.speedMph * 1.609344)

        powerValue.text =
            "Power: ${bike.powerLive} W (unconfirmed unit)"

        rpmValue.text =
            "RPM: ${bike.rpm}"

        resistanceValue.text =
            "Resistance: ${bike.resistance}"

    }




    private fun toggleLogs() {

        logsExpanded = !logsExpanded

        logContainer.visibility =
            if (logsExpanded) View.VISIBLE else View.GONE

        logTitle.text =
            if (logsExpanded) "Logs ▼" else "Logs ▶"

    }




    private fun addLog(message: String) {

        runOnUiThread {
            status.append("\n$message")
        }

    }

}