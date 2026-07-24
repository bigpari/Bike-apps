package com.example.bike_apps

import android.Manifest
import android.content.Context
import android.os.Bundle
import android.os.PowerManager
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity


class MainActivity : AppCompatActivity() {


    private lateinit var ble: BLEManager

    private lateinit var csc: CscPeripheral

    private var wakeLock: PowerManager.WakeLock? = null

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


    private lateinit var websocket: WebSocketManager


    private var training = false

    private var logsExpanded = true




    private val permissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) {


            if (
                it[Manifest.permission.BLUETOOTH_SCAN] == true
            ) {

                startTraining()

            }
            else {

                addLog(
                    "Bluetooth permission denied"
                )

            }

        }





    override fun onCreate(
        savedInstanceState: Bundle?
    ) {


        super.onCreate(
            savedInstanceState
        )


        setContentView(
            R.layout.activity_main
        )



        status =
            findViewById(
                R.id.status
            )


        trainingButton =
            findViewById(
                R.id.trainingButton
            )


        logTitle =
            findViewById(
                R.id.logTitle
            )


        logContainer =
            findViewById(
                R.id.logContainer
            )


        timeValue =
            findViewById(
                R.id.timeValue
            )


        caloriesValue =
            findViewById(
                R.id.caloriesValue
            )


        distanceValue =
            findViewById(
                R.id.distanceValue
            )


        speedValue =
            findViewById(
                R.id.speedValue
            )


        powerValue =
            findViewById(
                R.id.powerValue
            )


        rpmValue =
            findViewById(
                R.id.rpmValue
            )


        resistanceValue =
            findViewById(
                R.id.resistanceValue
            )


        // This protocol doesn't transmit calories at all (confirmed
        // against a public reverse-engineering of the same device
        // family - it's simply not one of the fields the bike sends).
        // Rather than show a number that's silently always wrong, make
        // that explicit instead of wiring it to anything.
        caloriesValue.text =
            "Calories: not available"




        websocket =
            WebSocketManager {

                addLog(it)

            }



        websocket.connect(
            "ws://YOUR_LOCAL_IP:8080"
        )



        csc =
            CscPeripheral(this) { message ->

                addLog(message)

            }



        ble =
            BLEManager(

                this,

                // LOG CALLBACK
                { message ->

                    addLog(message)


                    websocket.send(

                        """
                {
                  "type":"BLE",
                  "data":"$message"
                }
                """.trimIndent()

                    )


                    if(message == "TRAINING STOPPED") {

                        training = false

                        csc.stop()

                        wakeLock?.let {
                            if (it.isHeld) it.release()
                        }
                        wakeLock = null

                        runOnUiThread {

                            trainingButton.text =
                                "Start Training"

                        }

                    }

                },


                // RAW DATA CALLBACK - still forwarded for logging/debugging
                { data ->

                    val hex =
                        data.joinToString(" ") {
                            "%02X".format(it)
                        }


                    websocket.send(

                        """
                {
                  "type":"BLE_DATA",
                  "data":"$hex"
                }
                """.trimIndent()

                    )

                },


                // DECODED DATA CALLBACK - one full E5/E6/E7 triple, ready
                // to display
                { bike ->

                    runOnUiThread {

                        updateTrainingData(bike)

                    }


                    csc.updateMeasurement(bike)


                    websocket.send(

                        """
                {
                  "type":"DECODED",
                  "data":{
                    "minute":${bike.minute},
                    "second":${bike.second},
                    "distanceMiles":${bike.distanceMiles},
                    "workoutTimeSeconds":${bike.workoutTimeSeconds},
                    "speedMph":${bike.speedMph},
                    "rpm":${bike.rpm},
                    "resistance":${bike.resistance},
                    "avgSpeedMph":${bike.avgSpeedMph},
                    "maxSpeedMph":${bike.maxSpeedMph},
                    "avgRpmRaw":${bike.avgRpmRaw},
                    "unknownE6":${bike.unknownE6},
                    "powerLive":${bike.powerLive},
                    "powerAvg":${bike.powerAvg},
                    "powerMax":${bike.powerMax}
                  }
                }
                """.trimIndent()

                    )

                }

            )




        trainingButton.setOnClickListener {


            if(training) {

                ble.stopTraining()

            }
            else {

                requestPermission()

            }


        }


        logTitle.setOnClickListener {

            toggleLogs()

        }


    }




    private fun updateTrainingData(bike: BikeData) {


        val hours =
            bike.workoutTimeSeconds / 3600

        val minutes =
            (bike.workoutTimeSeconds % 3600) / 60

        val seconds =
            bike.workoutTimeSeconds % 60


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


        logsExpanded =
            !logsExpanded


        logContainer.visibility =
            if(logsExpanded) View.VISIBLE else View.GONE


        logTitle.text =
            if(logsExpanded) "Logs ▼" else "Logs ▶"


    }




    private fun requestPermission() {


        if(
            android.os.Build.VERSION.SDK_INT >= 31
        ) {


            permissionLauncher.launch(

                arrayOf(

                    Manifest.permission.BLUETOOTH_SCAN,

                    Manifest.permission.BLUETOOTH_CONNECT,

                    Manifest.permission.BLUETOOTH_ADVERTISE

                )

            )


        }
        else {


            startTraining()


        }


    }




    private fun startTraining() {


        training = true


        trainingButton.text =
            "Stop Training"



        addLog(
            "Starting BLE scan..."
        )


        ble.scan()


        csc.start()


        // Keeps the CPU running after the screen locks so BLE scanning,
        // the GATT server, and the websocket don't get suspended by
        // Android's background limits mid-ride. Screen can still turn
        // off - this only stops the CPU itself from sleeping. 3-hour
        // safety timeout so a forgotten release() can't drain the
        // battery indefinitely if something goes wrong.
        val powerManager =
            getSystemService(Context.POWER_SERVICE) as PowerManager

        wakeLock =
            powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "BikeApps::TrainingWakeLock"
            )

        wakeLock?.acquire(3 * 60 * 60 * 1000L)


    }




    private fun addLog(
        message: String
    ) {


        runOnUiThread {


            status.append(

                "\n$message"

            )


        }


    }


}