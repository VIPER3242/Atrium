package com.example.atrium.network

import android.os.Handler
import android.os.Looper
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.math.pow

/**
 * Thin wrapper around the Hub API WebSocket (/hub/ws). Mirrors the message
 * shapes hub_api.py already implements — no new protocol invented here,
 * this is a client for what the server already speaks:
 *   client -> server: {"type": "auth", "token": ...}
 *   client -> server: {"type": "switch_persona", "persona": "mary"|"calista"}
 *   client -> server: {"type": "chat", "message": ...}
 *   server -> client: {"type": "auth_ok"}
 *   server -> client: {"type": "persona_switched", "persona": ...}
 *   server -> client: {"type": "reply", "persona": ..., "text": ...}
 *   server -> client: {"type": "error", "message": ...}
 */
class HubApiClient(
    private val serverUrl: String,
    private val authToken: String? = null,
    private val listener: HubApiListener,
) {
    private var webSocket: WebSocket? = null
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS) // WebSockets are long-lived — no read timeout
        .pingInterval(20, TimeUnit.SECONDS)    // keeps bot-hosting.net's connection alive
        .build()

    // --- reconnect state ---
    // Exponential backoff (2s, 4s, 8s... capped at 30s) rather than hammering
    // bot-hosting.net's free tier with instant retries. manuallyDisconnected
    // distinguishes "the app called disconnect() on purpose" from "the
    // connection dropped" — only the latter should trigger a retry.
    private val reconnectHandler = Handler(Looper.getMainLooper())
    private var reconnectAttempt = 0
    private var manuallyDisconnected = false
    private val baseReconnectDelayMs = 2000L
    private val maxReconnectDelayMs = 30000L
    private val maxBackoffShift = 5 // 2000 * 2^5 = 64000, already past the cap — no need to grow further

    fun connect() {
        manuallyDisconnected = false
        val request = Request.Builder().url(serverUrl).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                reconnectAttempt = 0 // a real successful connection resets backoff
                if (!authToken.isNullOrEmpty()) {
                    send(JSONObject().apply {
                        put("type", "auth")
                        put("token", authToken)
                    })
                } else {
                    listener.onConnected() // dev mode, no token configured server-side
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) = handleMessage(text)

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                listener.onError(t.message ?: "Connection failed")
                scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                listener.onDisconnected()
                if (!manuallyDisconnected) scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        if (manuallyDisconnected) return
        val shift = min(reconnectAttempt, maxBackoffShift)
        val delay = min(baseReconnectDelayMs * 2.0.pow(shift).toLong(), maxReconnectDelayMs)
        reconnectAttempt++
        listener.onReconnecting(reconnectAttempt)
        reconnectHandler.postDelayed({ connect() }, delay)
    }

    private fun handleMessage(text: String) {
        val json = try {
            JSONObject(text)
        } catch (e: Exception) {
            listener.onError("Malformed message from server: $text")
            return
        }
        when (json.optString("type")) {
            "auth_ok" -> listener.onConnected()
            "persona_switched" -> listener.onPersonaSwitched(json.optString("persona"))
            "reply" -> listener.onReply(json.optString("persona"), json.optString("text"))
            "error" -> listener.onError(json.optString("message"))
            else -> listener.onError("Unknown message type: ${json.optString("type")}")
        }
    }

    fun switchPersona(persona: String) = send(JSONObject().apply {
        put("type", "switch_persona")
        put("persona", persona)
    })

    fun sendChat(message: String) = send(JSONObject().apply {
        put("type", "chat")
        put("message", message)
    })

    fun sendRoomMessage(message: String) = send(JSONObject().apply {
        put("type", "room_message")
        put("message", message)
    })

    private fun send(json: JSONObject) {
        webSocket?.send(json.toString())
    }

    fun disconnect() {
        manuallyDisconnected = true
        reconnectHandler.removeCallbacksAndMessages(null)
        webSocket?.close(1000, "Client closing")
        webSocket = null
    }
}

interface HubApiListener {
    fun onConnected()
    fun onDisconnected()
    fun onReconnecting(attempt: Int)
    fun onPersonaSwitched(persona: String)
    fun onReply(persona: String, text: String)
    fun onError(message: String)
}

// --- chat history (plain HTTP GET, not over the WebSocket) ---

data class HistoryEntry(val role: String, val content: String, val timestamp: String)

/**
 * Hits GET /hub/history?persona=...&limit=... — see the hub_api.py patch
 * (Atrium Android Plan v0.2, Section 1) for the server side of this.
 */
class HubHistoryClient(private val baseHttpUrl: String) {
    private val client = OkHttpClient()

    fun fetchHistory(persona: String, limit: Int = 50, onResult: (List<HistoryEntry>?) -> Unit) {
        val url = "$baseHttpUrl/hub/history?persona=$persona&limit=$limit"
        val request = Request.Builder().url(url).build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = onResult(null)

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string()
                if (!response.isSuccessful || body == null) {
                    onResult(null)
                    return
                }
                try {
                    val arr = JSONArray(body)
                    val entries = (0 until arr.length()).map { i ->
                        val o = arr.getJSONObject(i)
                        HistoryEntry(
                            role = o.optString("role"),
                            content = o.optString("content"),
                            timestamp = o.optString("timestamp"),
                        )
                    }
                    onResult(entries)
                } catch (e: Exception) {
                    onResult(null)
                }
            }
        })
    }
}