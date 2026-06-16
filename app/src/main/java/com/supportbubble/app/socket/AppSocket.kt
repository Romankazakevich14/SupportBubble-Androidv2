package com.supportbubble.app.socket

import android.content.Context
import android.util.Log
import com.supportbubble.app.BubbleAppSettings
import com.supportbubble.app.BuildConfig
import com.supportbubble.app.DEFAULT_BUBBLE_SETTINGS
import com.supportbubble.app.services.DeviceInfoService
import io.socket.client.IO
import io.socket.client.Socket
import io.socket.engineio.client.transports.WebSocket
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/**
 * Application-level Socket.io singleton shared by [SocketForegroundService],
 * [AppMonitorService], and the UI layer.
 *
 * ## Real-time events received from the server
 *
 * | Event                    | Payload                               | Handler                  |
 * |--------------------------|---------------------------------------|--------------------------|
 * | `bubble_settings_updated`| `{ allowedBubbleApps: [...] \| null }`| [onBubbleSettingsUpdated] |
 *
 * Lifecycle:
 *   - Call [init] once (from Application.onCreate or SocketForegroundService.onCreate).
 *   - The socket stays alive until [shutdown] is called.
 *   - socket.io-client handles automatic reconnection; [reconnect] is a safety net.
 */
object AppSocket {

    private const val TAG = "AppSocket"

    private var socket: Socket? = null
    private var deviceId: String = ""

    // ── Connection state ──────────────────────────────────────────────────────
    private val _isConnected = MutableStateFlow(false)
    val isConnectedFlow: StateFlow<Boolean> = _isConnected.asStateFlow()
    val isConnected: Boolean get() = socket?.connected() == true

    // ── Callbacks set by AllowedAppsManager ───────────────────────────────────

    /**
     * Called when the server pushes a `bubble_settings_updated` event.
     * Payload: `Map<String, BubbleAppSettings>?`
     *   null → all packages reset to defaults
     *   map  → per-package settings (absent packages use defaults)
     */
    var onBubbleSettingsUpdated: ((Map<String, BubbleAppSettings>?) -> Unit)? = null

    // ── Init ──────────────────────────────────────────────────────────────────

    fun init(context: Context) {
        if (socket != null) return
        deviceId = DeviceInfoService.getDeviceId(context)
        connect()
    }

    private fun connect() {
        try {
            val opts = IO.Options().apply {
                path = BuildConfig.SOCKET_PATH
                query = "deviceId=$deviceId"
                transports = arrayOf(WebSocket.NAME)
                reconnection = true
                reconnectionDelay = 3_000
                reconnectionDelayMax = 30_000
                reconnectionAttempts = Int.MAX_VALUE
            }

            socket = IO.socket(URI.create(BuildConfig.SERVER_URL), opts).apply {
                on(Socket.EVENT_CONNECT) {
                    _isConnected.value = true
                    Log.d(TAG, "Connected — id: ${id()}")
                }
                on(Socket.EVENT_DISCONNECT) { args ->
                    _isConnected.value = false
                    Log.d(TAG, "Disconnected: ${args.firstOrNull()}")
                }
                on(Socket.EVENT_CONNECT_ERROR) { args ->
                    _isConnected.value = false
                    Log.w(TAG, "Connect error: ${args.firstOrNull()}")
                }
                on("bubble_settings_updated") { args ->
                    handleBubbleSettingsUpdated(args)
                }
                connect()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create socket", e)
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    fun reconnect() {
        val s = socket
        if (s == null) { connect(); return }
        if (!s.connected()) s.connect()
    }

    fun emitAppChange(packageName: String) {
        if (socket?.connected() != true) return
        try {
            socket?.emit("app_change", JSONObject().apply {
                put("deviceId", deviceId)
                put("packageName", packageName)
            })
            Log.d(TAG, "emitAppChange → $packageName")
        } catch (e: Exception) {
            Log.e(TAG, "emitAppChange failed", e)
        }
    }

    fun shutdown() {
        socket?.disconnect()
        socket = null
        _isConnected.value = false
        Log.d(TAG, "AppSocket shut down")
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    /**
     * Parses a `bubble_settings_updated` payload.
     *
     * ```json
     * { "allowedBubbleApps": null }             // all defaults
     * { "allowedBubbleApps": [] }               // no explicit settings
     * { "allowedBubbleApps": [{ "packageName": "com.example", "enabled": false, ... }] }
     * ```
     */
    private fun handleBubbleSettingsUpdated(args: Array<Any?>) {
        try {
            val json = args.firstOrNull() as? JSONObject ?: return

            val map: Map<String, BubbleAppSettings>? = if (json.isNull("allowedBubbleApps")) {
                null
            } else {
                val arr: JSONArray = json.getJSONArray("allowedBubbleApps")
                buildMap {
                    repeat(arr.length()) { i ->
                        val obj: JSONObject = arr.getJSONObject(i)
                        val pkg = obj.getString("packageName")
                        put(pkg, BubbleAppSettings(
                            packageName = pkg,
                            enabled     = obj.optBoolean("enabled", true),
                            bubbleText  = obj.optString("bubbleText",  DEFAULT_BUBBLE_SETTINGS.bubbleText),
                            bubbleIcon  = obj.optString("bubbleIcon",  DEFAULT_BUBBLE_SETTINGS.bubbleIcon),
                            bubbleColor = obj.optString("bubbleColor", DEFAULT_BUBBLE_SETTINGS.bubbleColor),
                            bubbleSize  = obj.optInt("bubbleSize",     DEFAULT_BUBBLE_SETTINGS.bubbleSize),
                        ))
                    }
                }
            }

            Log.d(TAG, "bubble_settings_updated: ${map?.size ?: "null"} entries")
            onBubbleSettingsUpdated?.invoke(map)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse bubble_settings_updated", e)
        }
    }
}
