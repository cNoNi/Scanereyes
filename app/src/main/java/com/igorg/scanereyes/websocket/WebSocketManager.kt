package com.igorg.scanereyes.websocket

import okhttp3.*

class WebSocketManager(
    private val onTakePhoto: () -> Unit
) {

    private val client = OkHttpClient()
    private var webSocket: WebSocket? = null

    fun connect(ip: String) {
        val request = Request.Builder().url("ws://$ip:8765").build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                println("Połączono")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                when (text) {
                    "take_photo" -> {
                        onTakePhoto()
                    }
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                println("Rozłączanie")
            }

            override fun onFailure(
                webSocket: WebSocket, t: Throwable, response: Response?
            ) {
                println("Błąd: ${t.message}")
            }
        })
    }

    fun send(message: String) {
        webSocket?.send(message)
    }

    fun notifyPhotoTaken() {
        send("photo_taken")
    }

    fun notifyPhotoFailed(reason: String?) {
        send("photo_failed:$reason")
    }

    fun disconnect() {
        webSocket?.close(1000, "Bye")
    }
}