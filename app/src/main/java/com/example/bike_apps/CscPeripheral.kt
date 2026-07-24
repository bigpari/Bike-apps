package com.example.bike_apps

import android.bluetooth.*
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.ParcelUuid
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * Broadcasts the standard Bluetooth SIG "Cycling Speed and Cadence" (CSC)
 * GATT service (0x1816), so a real CSC client - like Garmin's indoor
 * cycling activity asking to pair a speed/cadence sensor - can connect to
 * this phone and receive speed + cadence derived from the bike's own
 * decoded telemetry (BikeData).
 *
 * Unlike the bike's own protocol, this part is NOT reverse-engineered -
 * it's the public Bluetooth SIG spec, so the framing here is exact, not a
 * best guess.
 *
 * WHEEL CIRCUMFERENCE IS A CONFIGURED ASSUMPTION, NOT A MEASURED FACT.
 * CSC has no notion of "speed" directly - a client computes speed itself
 * from (wheel circumference x revolutions) / time. Since this bike has no
 * real wheel, WHEEL_CIRCUMFERENCE_MM below is used to back-compute a
 * revolution count that reproduces the bike's own reported speed. If
 * Garmin's displayed speed doesn't match this app's, this is the first
 * thing to adjust - common real-world values run roughly 1900-2200mm
 * depending on tire/wheel size; 2105mm (700x23c) is just a starting point.
 *
 * Requires BLUETOOTH_ADVERTISE permission (Android 12+) in addition to
 * whatever BLEManager already requests, and the same permission declared
 * in AndroidManifest.xml:
 *   <uses-permission android:name="android.permission.BLUETOOTH_ADVERTISE" />
 */
class CscPeripheral(
    private val context: Context,
    private val log: (String) -> Unit
) {

    private val CSC_SERVICE_UUID =
        UUID.fromString("00001816-0000-1000-8000-00805f9b34fb")

    private val CSC_MEASUREMENT_UUID =
        UUID.fromString("00002a5b-0000-1000-8000-00805f9b34fb")

    private val CSC_FEATURE_UUID =
        UUID.fromString("00002a5c-0000-1000-8000-00805f9b34fb")

    private val SENSOR_LOCATION_UUID =
        UUID.fromString("00002a5d-0000-1000-8000-00805f9b34fb")

    private val CCCD_UUID =
        UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // See docstring above - adjust this if Garmin's speed doesn't match.
    private val WHEEL_CIRCUMFERENCE_MM = 2105.0

    private var gattServer: BluetoothGattServer? = null
    private var measurementCharacteristic: BluetoothGattCharacteristic? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private val subscribedDevices = mutableSetOf<BluetoothDevice>()

    // Running sensor state - a real CSC sensor keeps exactly this kind of
    // running total, which is why cumulative counts (not raw speed) is
    // what actually goes over the air.
    private var wheelRevWhole: Long = 0
    private var wheelRevFraction: Double = 0.0
    private var crankRevWhole: Int = 0
    private var crankRevFraction: Double = 0.0
    private var eventClock1024: Int = 0  // ticks of 1/1024s, wraps at 65536
    private var lastUpdateMillis: Long = 0L

    fun start() {

        val bluetoothManager =
            context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager

        val adapter = bluetoothManager.adapter

        gattServer = bluetoothManager.openGattServer(context, gattServerCallback)

        val service = BluetoothGattService(
            CSC_SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )

        val measurement = BluetoothGattCharacteristic(
            CSC_MEASUREMENT_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            0
        )

        val cccd = BluetoothGattDescriptor(
            CCCD_UUID,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        )
        measurement.addDescriptor(cccd)

        val feature = BluetoothGattCharacteristic(
            CSC_FEATURE_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )

        val sensorLocation = BluetoothGattCharacteristic(
            SENSOR_LOCATION_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )

        service.addCharacteristic(measurement)
        service.addCharacteristic(feature)
        service.addCharacteristic(sensorLocation)

        gattServer?.addService(service)
        measurementCharacteristic = measurement

        advertiser = adapter.bluetoothLeAdvertiser

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .addServiceUuid(ParcelUuid(CSC_SERVICE_UUID))
            .build()

        advertiser?.startAdvertising(settings, data, advertiseCallback)

        lastUpdateMillis = System.currentTimeMillis()

        log("CSC PERIPHERAL STARTED - advertising as a speed/cadence sensor")
    }

    fun stop() {

        advertiser?.stopAdvertising(advertiseCallback)
        gattServer?.close()
        subscribedDevices.clear()

        log("CSC PERIPHERAL STOPPED")
    }

    private val advertiseCallback = object : AdvertiseCallback() {

        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            log("CSC ADVERTISING STARTED")
        }

        override fun onStartFailure(errorCode: Int) {
            log("CSC ADVERTISING FAILED: $errorCode")
        }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {

        override fun onConnectionStateChange(
            device: BluetoothDevice,
            status: Int,
            newState: Int
        ) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                log("CSC CLIENT CONNECTED: ${device.address}")
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                log("CSC CLIENT DISCONNECTED: ${device.address}")
                subscribedDevices.remove(device)
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            val value = when (characteristic.uuid) {
                // Feature flags: bit0 wheel data supported, bit1 crank
                // data supported. LE uint16.
                CSC_FEATURE_UUID -> byteArrayOf(0x03, 0x00)
                // Sensor location "Other" - Garmin doesn't require a
                // specific value here, this is just informational.
                SENSOR_LOCATION_UUID -> byteArrayOf(0x00)
                else -> null
            }
            gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            if (descriptor.uuid == CCCD_UUID) {
                if (value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) {
                    subscribedDevices.add(device)
                    log("CSC NOTIFICATIONS ENABLED for ${device.address}")
                } else {
                    subscribedDevices.remove(device)
                    log("CSC NOTIFICATIONS DISABLED for ${device.address}")
                }
            }
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
            }
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            descriptor: BluetoothGattDescriptor
        ) {
            gattServer?.sendResponse(
                device, requestId, BluetoothGatt.GATT_SUCCESS, offset,
                descriptor.value ?: ByteArray(0)
            )
        }
    }

    /**
     * Call this each time BLEManager decodes a fresh record (i.e. from the
     * same onDecoded callback that updates the UI). Computes elapsed-time
     * deltas from the bike's current speed/rpm, accumulates them into the
     * running wheel/crank revolution counters CSC expects, and notifies
     * any subscribed client (Garmin).
     */
    fun updateMeasurement(bike: BikeData) {

        val now = System.currentTimeMillis()
        val dtSeconds = (now - lastUpdateMillis) / 1000.0
        lastUpdateMillis = now

        if (dtSeconds <= 0) return

        // --- Wheel revolutions this interval, back-computed from speed ---
        val speedMetersPerSecond = bike.speedMph * 0.44704
        val distanceThisIntervalM = speedMetersPerSecond * dtSeconds
        val wheelCircumferenceM = WHEEL_CIRCUMFERENCE_MM / 1000.0
        val wheelRevsThisInterval = distanceThisIntervalM / wheelCircumferenceM

        wheelRevFraction += wheelRevsThisInterval
        val wheelWholeIncrement = wheelRevFraction.toLong()
        wheelRevWhole += wheelWholeIncrement
        wheelRevFraction -= wheelWholeIncrement

        // --- Crank revolutions this interval, directly from rpm ---
        val crankRevsThisInterval = bike.rpm * (dtSeconds / 60.0)
        crankRevFraction += crankRevsThisInterval
        val crankWholeIncrement = crankRevFraction.toInt()
        crankRevWhole = (crankRevWhole + crankWholeIncrement) and 0xFFFF
        crankRevFraction -= crankWholeIncrement

        // --- Shared event clock, 1/1024s ticks, wraps at 65536 per spec ---
        val ticksThisInterval = (dtSeconds * 1024).toInt()
        eventClock1024 = (eventClock1024 + ticksThisInterval) and 0xFFFF

        // CSC Measurement payload, all fields little-endian per BLE spec
        // (note: this is the OPPOSITE byte order from the bike's own
        // custom protocol, which was big-endian - don't mix them up):
        //   flags(1) + cumulative_wheel_revs(4) + last_wheel_event_time(2)
        //   + cumulative_crank_revs(2) + last_crank_event_time(2)
        val buffer = ByteBuffer.allocate(11).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(0x03)  // flags: wheel + crank data present
        buffer.putInt(wheelRevWhole.toInt())
        buffer.putShort(eventClock1024.toShort())
        buffer.putShort(crankRevWhole.toShort())
        buffer.putShort(eventClock1024.toShort())

        val payload = buffer.array()

        val characteristic = measurementCharacteristic ?: return
        characteristic.value = payload

        for (device in subscribedDevices) {
            gattServer?.notifyCharacteristicChanged(device, characteristic, false)
        }
    }
}