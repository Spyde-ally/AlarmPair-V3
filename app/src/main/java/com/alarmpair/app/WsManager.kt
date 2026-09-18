package com.alarmpair.app

import android.os.Handler
import android.os.Looper
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
    private val client = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).pingInterval(25, TimeUnit.SECONDS).build()
    private val handler = Handler(Looper.getMainLooper())
    private var ws: WebSocket? = null
    private var token = ""
    private var reconnectAttempts = 0
    private var stopped = false
    private var opened = false
    private val queue = ArrayDeque<String>()

    fun setDeviceToken(value: String) { token = value }
    fun connect(afterOpen: (() -> Unit)? = null) {
        stopped = false
        handler.post { connectInternal(afterOpen) }
    }
    private fun connectInternal(afterOpen: (() -> Unit)? = null) {
        if (stopped) return
        opened = false
        ws?.cancel()
        val request = Request.Builder().url(serverUrl).build()
        ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(socket: WebSocket, response: Response) {
                opened = true; reconnectAttempts = 0
                handler.post {
                    onConnected()
                    if (token.isNotBlank()) socket.send(JSONObject().put("type", "resume").put("deviceToken", token).toString())
                    while (queue.isNotEmpty()) socket.send(queue.removeFirst())
                    afterOpen?.invoke()
                }
            }
            override fun onMessage(socket: WebSocket, text: String) { try { val j=JSONObject(text); handler.post { onMessage(j) } } catch (_: Exception) {} }
            override fun onFailure(socket: WebSocket, t: Throwable, response: Response?) { handler.post { opened=false; onDisconnected(); scheduleReconnect() } }
            override fun onClosed(socket: WebSocket, code: Int, reason: String) { handler.post { opened=false; onDisconnected(); scheduleReconnect() } }
        })
    }
    private fun scheduleReconnect() {
        if (stopped) return
        val delay = minOf(2000L * (reconnectAttempts + 1), 30000L); reconnectAttempts++
        handler.postDelayed({ if (!stopped && !opened) connectInternal() }, delay)
    }
    fun send(obj: JSONObject): Boolean {
        val s=obj.toString()
        if (opened && ws != null) return ws!!.send(s)
        queue.addLast(s); return false
    }
    fun disconnect() { stopped=true; opened=false; queue.clear(); ws?.close(1000,"closed"); ws=null }
}
