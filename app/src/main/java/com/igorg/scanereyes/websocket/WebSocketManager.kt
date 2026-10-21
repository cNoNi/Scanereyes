package com.igorg.scanereyes.websocket

import android.util.Log
import okhttp3.*
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class WebSocketManager(
    private val onTakePhoto: () -> Unit
) {

    private val client = OkHttpClient.Builder()
        .pingInterval(10, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var serverIp: String? = null
    private var isManualDisconnect = false

    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private var reconnectFuture: ScheduledFuture<*>? = null

    fun connect(ip: String) {
        serverIp = ip
        isManualDisconnect = false
        reconnectFuture?.cancel(true)
        doConnect()
    }

    @Synchronized
    private fun doConnect() {
        val ip = serverIp ?: return
        if (isManualDisconnect) return

        webSocket?.close(1000, "Reconnecting")
        val request = Request.Builder().url("ws://$ip:8765").build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "Połączono z ws://$ip:8765")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                when (text) {
                    "take_photo" -> {
                        onTakePhoto()
                    }
                    else -> {
                        Log.d(TAG, "Ignorowanie nieznanego komunikatu: $text")
                    }
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "Rozłączanie: $code / $reason")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "Połączenie zamknięte: $code / $reason")
                scheduleReconnect()
            }

            override fun onFailure(
                webSocket: WebSocket, t: Throwable, response: Response?
            ) {
                Log.e(TAG, "Błąd WebSocket: ${t.message}")
                scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        if (isManualDisconnect) return
        synchronized(this) {
            if (reconnectFuture?.isDone == false) return
            Log.d(TAG, "Zaplanowano ponowne połączenie za $RECONNECT_DELAY_SEC s")
            reconnectFuture = scheduler.schedule({
                if (!isManualDisconnect) {
                    Log.d(TAG, "Próba ponownego połączenia...")
                    doConnect()
                }
            }, RECONNECT_DELAY_SEC, TimeUnit.SECONDS)
        }
    }

    fun send(message: String) {
        webSocket?.send(message)
    }

    fun notifyPhotoTaken() {
        send("photo_taken")
    }

    fun disconnect() {
        isManualDisconnect = true
        reconnectFuture?.cancel(true)
        webSocket?.close(1000, "Disconnect requested")
        webSocket = null
    }

    companion object {
        private const val TAG = "WebSocketManager"
        private const val RECONNECT_DELAY_SEC = 3L
    }
}