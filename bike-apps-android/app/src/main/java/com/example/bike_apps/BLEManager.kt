package com.example.bike_apps

import android.Manifest
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import androidx.core.app.ActivityCompat
import java.util.UUID


class BLEManager(

    private val context: Context,

    private val log: (String) -> Unit,

    private val onDataReceived: (ByteArray) -> Unit,

    private val onDecoded: (BikeData) -> Unit

) {


    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE)
                as BluetoothManager


    private val adapter =
        bluetoothManager.adapter


    private var scanner: BluetoothLeScanner? = null

    private var gatt: BluetoothGatt? = null


    private var tx: BluetoothGattCharacteristic? = null

    private var rx: BluetoothGattCharacteristic? = null



    private val SERVICE =
        UUID.fromString(
            "6e400001-b5a3-f393-e0a9-e50e24dcca9e"
        )


    private val TX =
        UUID.fromString(
            "6e400003-b5a3-f393-e0a9-e50e24dcca9e"
        )


    private val RX =
        UUID.fromString(
            "6e400002-b5a3-f393-e0a9-e50e24dcca9e"
        )




    // ----------------------------
    // Bike protocol commands
    // ----------------------------


    private object Cmd {

        const val PING_REQ = 0xD0

        const val PING_RSP = 0xE0

        const val DEVICE_INFO_REQ = 0xD1

        const val DEVICE_INFO_RSP = 0xE1

        const val SET_CONFIG_REQ = 0xD3

        const val SET_CONFIG_RSP = 0xE3

        const val START_SYNC_REQ = 0xD4

        const val START_SYNC_RSP = 0xE4

        const val READ_NEXT_REQ = 0xD5

        const val RECORD_A_RSP = 0xE5

        const val RECORD_B_RSP = 0xE6

        const val RECORD_C_RSP = 0xE7

    }


    // Confirmed byte-for-byte from the capture - this is what the original
    // app sends right after device info when "Quick Start" is picked.
    private val QUICK_START_PAYLOAD =
        byteArrayOf(
            0x00, 0x00, 0x00, 0x00,
            0x01, 0x01, 0x04, 0x00,
            0x45, 0x00, 0x00, 0x00,
            0x00
        )




    private fun buildFrame(

        cmd:Int,

        payload:ByteArray = ByteArray(0),

        padLen:Int = 20

    ):ByteArray {


        val header =
            byteArrayOf(

                0xF9.toByte(),

                cmd.toByte(),

                payload.size.toByte()

            )


        val body =
            header + payload


        val checksum =
            (body.sumOf {

                it.toInt() and 0xFF

            }) and 0xFF



        val frame =
            body + checksum.toByte()



        return if(frame.size < padLen)

            frame + ByteArray(
                padLen - frame.size
            )

        else

            frame


    }






    private enum class State {

        IDLE,

        PINGED,

        GOT_INFO,

        CONFIGURED,

        SYNCING,

        READING

    }


    private var state =
        State.IDLE


    private var recordsReceived =
        0


    // Holds the current record's E5/E6 frames until E7 arrives and the
    // full triple can be decoded together.
    private var lastE5: ByteArray? = null
    private var lastE6: ByteArray? = null


    // The original app polls D5 roughly once per second (measured gaps in
    // the capture: 0.85-0.96s, consistently ~0.9s) - not back-to-back as
    // fast as the BLE link allows. The bike's live-sensor snapshot appears
    // to only refresh on that same ~1s cadence, so polling faster just
    // gets you the same stale/all-zero buffer every time. This is the
    // delay between finishing one record and requesting the next.
    private val POLL_INTERVAL_MS = 900L

    private val handler =
        Handler(Looper.getMainLooper())

    private var pollRunnable: Runnable? = null







    // ----------------------------
    // SCANNING
    // ----------------------------


    private val scanCallback =
        object : ScanCallback(){


            override fun onScanResult(

                callbackType:Int,

                result:ScanResult

            ){


                log(

                    """
                    BIKE FOUND
                    ${result.device.name}
                    ${result.device.address}
                    RSSI=${result.rssi}
                    """.trimIndent()

                )


                stopScan()


                connect(
                    result.device
                )


            }




            override fun onScanFailed(
                errorCode:Int
            ){

                log(
                    "SCAN FAILED $errorCode"
                )

            }


        }







    fun scan(){


        if(
            Build.VERSION.SDK_INT >= 31 &&

            ActivityCompat.checkSelfPermission(

                context,

                Manifest.permission.BLUETOOTH_SCAN

            ) != PackageManager.PERMISSION_GRANTED

        ){

            log(
                "NO SCAN PERMISSION"
            )

            return

        }



        scanner =
            adapter.bluetoothLeScanner



        val filter =
            ScanFilter.Builder()

                .setServiceUuid(
                    ParcelUuid(SERVICE)
                )

                .build()



        val settings =
            ScanSettings.Builder()

                .setScanMode(
                    ScanSettings.SCAN_MODE_LOW_LATENCY
                )

                .build()



        log(
            "BLE SCAN STARTED"
        )



        scanner?.startScan(

            listOf(filter),

            settings,

            scanCallback

        )


    }








    fun stopScan(){


        if(
            Build.VERSION.SDK_INT >= 31 &&

            ActivityCompat.checkSelfPermission(

                context,

                Manifest.permission.BLUETOOTH_SCAN

            ) != PackageManager.PERMISSION_GRANTED

        ){

            return

        }



        scanner?.stopScan(
            scanCallback
        )



        log(
            "BLE SCAN STOPPED"
        )


    }









    // ----------------------------
    // STOP TRAINING
    // ----------------------------


    fun stopTraining(){


        log(
            "DISCONNECTING BIKE"
        )


        stopScan()


        pollRunnable?.let {
            handler.removeCallbacks(it)
        }
        pollRunnable = null



        gatt?.disconnect()


        gatt?.close()


        gatt = null



        tx = null

        rx = null



        state =
            State.IDLE



        recordsReceived =
            0


        lastE5 = null
        lastE6 = null



        log(
            "TRAINING STOPPED"
        )


    }









    private fun connect(
        device:BluetoothDevice
    ){


        log(
            "CONNECTING"
        )



        gatt =
            device.connectGatt(

                context,

                false,

                callback,

                BluetoothDevice.TRANSPORT_LE

            )


    }










    private val callback =
        object : BluetoothGattCallback(){



            override fun onConnectionStateChange(

                g:BluetoothGatt,

                status:Int,

                newState:Int

            ){



                if(
                    newState ==
                    BluetoothProfile.STATE_CONNECTED

                ){


                    log(
                        "CONNECTED"
                    )


                    g.discoverServices()


                }


            }







            override fun onServicesDiscovered(

                g:BluetoothGatt,

                status:Int

            ){


                log(
                    "SERVICES status=$status"
                )


                val service =
                    g.getService(
                        SERVICE
                    )



                tx =
                    service?.getCharacteristic(
                        TX
                    )


                rx =
                    service?.getCharacteristic(
                        RX
                    )



                log(
                    "TX=${tx != null}"
                )


                log(
                    "RX=${rx != null}"
                )



                rx?.writeType =
                    BluetoothGattCharacteristic
                        .WRITE_TYPE_NO_RESPONSE



                if(tx != null){

                    enableNotify(
                        g,
                        tx!!
                    )

                }


            }







            override fun onCharacteristicChanged(

                g:BluetoothGatt,

                c:BluetoothGattCharacteristic,

                value:ByteArray

            ){


                val hex =
                    value.joinToString(" ") {

                        "%02X".format(it)

                    }



                log(
                    "RX $hex"
                )



                // Send raw data upward

                onDataReceived(
                    value
                )



                handleFrame(
                    value
                )


            }








            override fun onDescriptorWrite(

                g:BluetoothGatt,

                d:BluetoothGattDescriptor,

                status:Int

            ){


                log(
                    "DESCRIPTOR COMPLETE $status"
                )


                startBike()


            }



        }









    private fun enableNotify(

        g:BluetoothGatt,

        c:BluetoothGattCharacteristic

    ){


        log(
            "ENABLE NOTIFICATION"
        )


        g.setCharacteristicNotification(

            c,

            true

        )


        val desc =
            c.getDescriptor(

                UUID.fromString(

                    "00002902-0000-1000-8000-00805f9b34fb"

                )

            )


        desc.value =
            BluetoothGattDescriptor
                .ENABLE_NOTIFICATION_VALUE



        g.writeDescriptor(
            desc
        )


    }









    private fun startBike(){


        log(
            "SENDING PING"
        )


        state =
            State.IDLE


        send(
            buildFrame(
                Cmd.PING_REQ
            )
        )


    }


    private fun handleFrame(

        value:ByteArray

    ){


        if(
            value.size < 4 ||

            value[0] != 0xF9.toByte()

        )
            return



        val cmd =
            value[1].toInt() and 0xFF




        when(cmd){



            Cmd.RECORD_A_RSP -> {

                lastE5 = value

            }




            Cmd.RECORD_B_RSP -> {

                lastE6 = value

            }




            Cmd.PING_RSP -> {


                log(
                    "GOT PING RESPONSE"
                )


                state =
                    State.PINGED



                send(

                    buildFrame(

                        Cmd.DEVICE_INFO_REQ,

                        byteArrayOf(
                            0x02,
                            0x00,
                            0x00,
                            0x00,
                            0x00
                        )

                    )

                )


            }







            Cmd.DEVICE_INFO_RSP -> {


                log(
                    "DEVICE INFO RECEIVED"
                )


                state =
                    State.GOT_INFO


                // Automatically apply Quick Start here instead of waiting
                // for the user to pick something - this is the step the
                // original app's picker sends before it ever starts sync.
                // Skipping it is why the bike used to send back all-zero
                // records: it was left in its idle/unconfigured state.

                log(
                    "AUTO-SELECTING QUICK START"
                )


                state =
                    State.CONFIGURED


                send(

                    buildFrame(

                        Cmd.SET_CONFIG_REQ,

                        QUICK_START_PAYLOAD

                    )

                )


            }







            Cmd.SET_CONFIG_RSP -> {


                log(
                    "QUICK START ACKED"
                )


                state =
                    State.SYNCING



                send(

                    buildFrame(

                        Cmd.START_SYNC_REQ,

                        byteArrayOf(

                            0x02,0x00,0x00,0x00,
                            0x00,0x00,0x00,0x00,
                            0x00,0x00,0x00,0x00,
                            0x00,0x00,0x1f,0x0f

                        )

                    )

                )


            }








            Cmd.START_SYNC_RSP -> {


                log(
                    "SYNC STARTED"
                )


                state =
                    State.READING


                recordsReceived =
                    0


                // The very first D5 after sync starts is NOT the same
                // request as every later one - the capture shows it fires
                // immediately (no throttle delay) with payload[0]=0x01
                // instead of 0x00. That's a one-shot "start reading"
                // trigger; sending the plain "continue" payload here
                // instead (as before) meant the live-data path never got
                // armed, which is why every record came back all-zero
                // even while pedaling.

                send(

                    buildFrame(

                        Cmd.READ_NEXT_REQ,

                        byteArrayOf(
                            0x01, 0x00, 0x00, 0x00,
                            0x00, 0x00, 0x00, 0x00,
                            0x00, 0x00, 0x00, 0x00,
                            0x00
                        )

                    )

                )


            }







            Cmd.RECORD_C_RSP -> {


                recordsReceived++


                log(
                    "RECORD #$recordsReceived COMPLETE"
                )


                val e5 = lastE5
                val e6 = lastE6

                if (e5 != null && e6 != null) {

                    val decoded = BikeDecoder.decode(e5, e6, value)

                    if (decoded != null) {

                        onDecoded(decoded)

                    } else {

                        log("DECODE FAILED - short frame")

                    }

                } else {

                    log("RECORD #$recordsReceived MISSING E5/E6 - skipping decode")

                }


                requestNextRecord()


            }



        }


    }









    private fun requestNextRecord(){


        pollRunnable?.let {
            handler.removeCallbacks(it)
        }


        val runnable = Runnable {

            send(

                buildFrame(

                    Cmd.READ_NEXT_REQ,

                    ByteArray(13)

                )

            )

        }


        pollRunnable = runnable


        handler.postDelayed(
            runnable,
            POLL_INTERVAL_MS
        )


    }









    private fun send(

        data:ByteArray

    ){


        val characteristic =
            rx ?: return



        characteristic.value =
            data



        gatt?.writeCharacteristic(
            characteristic
        )



        log(

            "TX ${
                data.joinToString(" ") {

                    "%02X".format(it)

                }
            }"

        )


    }



}