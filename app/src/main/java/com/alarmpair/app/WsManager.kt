package com.alarmpair.app

import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class WsManager(
    private val serverUrl: String,
    private val onMessage: (JSONObject) -> Unit,
    private val onConnected: () -> Unit,
    private val onDisconnected: () -> Unit
) {
    companion object { private const val TAG = "AlarmPair.Ws" }
    private val client = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).pingInterval(25, TimeUnit.SECONDS).build()
    private val handler = Handler(Looper.getMainLooper())
    private var ws: WebSocket? = null
    private var token = ""
    private var reconnectAttempts = 0
    private var stopped = false
    private var opened = false
    private val queue = ArrayDeque<String>()

    fun setDeviceToken(value: String) { token = value }
    fun connect(afterOpen: (() -> Unit)? = null) { stopped = false; handler.post { connectInternal(afterOpen) } }
    private fun connectInternal(afterOpen: (() -> Unit)? = null) {
        if (stopped) return
        opened = false; ws?.cancel()
        ws = client.newWebSocket(Request.Builder().url(serverUrl).build(), object : WebSocketListener() {
            override fun onOpen(socket: WebSocket, response: Response) {
                opened = true; reconnectAttempts = 0
                handler.post {
                    Log.d(TAG, "WebSocket opened; authentication is separate from transport connection")
                    onConnected()
                    if (token.isNotBlank()) socket.send(JSONObject().put("type", "resume").put("deviceToken", token).toString())
                    while (queue.isNotEmpty()) socket.send(queue.removeFirst())
                    afterOpen?.invoke()
                }
            }
            override fun onMessage(socket: WebSocket, text: String) { try { val message = JSONObject(text); handler.post { onMessage(message) } } catch (e: Exception) { Log.w(TAG, "invalid server message", e) } }
            override fun onFailure(socket: WebSocket, t: Throwable, response: Response?) { handler.post { opened = false; onDisconnected(); scheduleReconnect() } }
            override fun onClosed(socket: WebSocket, code: Int, reason: String) { handler.post { opened = false; onDisconnected(); scheduleReconnect() } }
        })
    }
    private fun scheduleReconnect() { if (stopped) return; val delay = minOf(2000L * (reconnectAttempts + 1), 30000L); reconnectAttempts++; handler.postDelayed({ if (!stopped && !opened) connectInternal() }, delay) }
    fun send(obj: JSONObject): Boolean { val text = obj.toString(); if (opened && ws != null) return ws!!.send(text); queue.addLast(text); return false }
    fun disconnect() { stopped = true; opened = false; queue.clear(); ws?.close(1000, "closed"); ws = null }
}
