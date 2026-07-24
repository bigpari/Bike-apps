package com.example.bike_apps

data class Packet(
    val type: String,
    val device: String,
    val service: String,
    val characteristic: String,
    val data: String,
    val timestamp: Long = System.currentTimeMillis()
)