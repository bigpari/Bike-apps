package com.example.bike_apps

import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener


class WebSocketManager(
    private val callback: (String) -> Unit
) {


    private val client = OkHttpClient()

    private var socket: WebSocket? = null


    // Remembered so reconnect attempts know where to reconnect to.
    private var lastUrl: String? = null

    // Set to false by close() so a deliberate shutdown doesn't trigger
    // reconnect attempts - only unexpected drops do.
    private var shouldReconnect = true

    private val handler = Handler(Looper.getMainLooper())

    private var reconnectAttempt = 0

    // Backoff schedule for reconnect attempts - starts quick (in case it's
    // a brief blip) and backs off so a genuinely offline server doesn't
    // get hammered for the rest of a long ride.
    private val RECONNECT_DELAYS_MS = longArrayOf(1000, 2000, 5000, 10000, 30000)



    fun connect(url: String) {


        lastUrl = url

        shouldReconnect = true

        reconnectAttempt = 0

        doConnect(url)


    }



    private fun doConnect(url: String) {


        callback("Connecting to $url")


        val request = Request.Builder()
            .url(url)
            .build()



        socket = client.newWebSocket(
            request,
            object : WebSocketListener() {


                override fun onOpen(
                    webSocket: WebSocket,
                    response: Response
                ) {

                    reconnectAttempt = 0

                    callback(
                        "WebSocket connected"
                    )

                }



                override fun onMessage(
                    webSocket: WebSocket,
                    text: String
                ) {

                    callback(
                        "Server: $text"
                    )

                }



                override fun onClosed(
                    webSocket: WebSocket,
                    code: Int,
                    reason: String
                ) {

                    callback(
                        "WebSocket closed: $reason"
                    )

                    scheduleReconnect()

                }



                override fun onFailure(
                    webSocket: WebSocket,
                    t: Throwable,
                    response: Response?
                ) {

                    callback(
                        "WebSocket error: ${t.message}"
                    )

                    scheduleReconnect()

                }

            }
        )

    }



    private fun scheduleReconnect() {


        if (!shouldReconnect) return

        val url = lastUrl ?: return


        val delayIndex =
            reconnectAttempt.coerceAtMost(RECONNECT_DELAYS_MS.size - 1)

        val delay =
            RECONNECT_DELAYS_MS[delayIndex]

        reconnectAttempt++


        callback(
            "Reconnecting in ${delay / 1000}s (attempt $reconnectAttempt)..."
        )


        handler.postDelayed(
            {
                if (shouldReconnect) {
                    doConnect(url)
                }
            },
            delay
        )


    }



    fun send(message: String) {

        val sent = socket?.send(message) ?: false

        // send() returning false usually means the socket is already
        // dead (e.g. mid-reconnect) - swallow it rather than crash, the
        // queued reconnect will pick things back up. Data points missed
        // during a drop are simply gone, same as before this change -
        // this only prevents the WHOLE rest of the session from being
        // silently lost.
        if (!sent) {

            callback(
                "Message dropped (not connected)"
            )

        }

    }



    fun close() {

        shouldReconnect = false

        socket?.close(
            1000,
            "App closed"
        )

    }

}