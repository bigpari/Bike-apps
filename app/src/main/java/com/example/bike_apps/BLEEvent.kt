package com.example.bike_apps


sealed class BLEEvent {


    data class DeviceFound(
        val device: BikeDevice
    ) : BLEEvent()



    data class Status(
        val message: String
    ) : BLEEvent()


}