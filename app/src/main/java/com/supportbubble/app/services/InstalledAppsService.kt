package com.supportbubble.app.services

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.Base64
import android.util.Log
import com.supportbubble.app.BuildConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

private const val TAG = "InstalledAppsService"
private const val ICON_SIZE_PX = 48       // pixel size for icon encoding
private const val ICON_MAX_BYTES = 8_192  // skip oversized icons (>8 KB base64)

/**
 * Scans the device for user-launchable apps and syncs the list to the backend.
 *
 * ## Icon encoding
 * Each icon is rasterised to a [ICON_SIZE_PX]×[ICON_SIZE_PX] bitmap and
 * base64-encoded as a PNG.  Icons larger than [ICON_MAX_BYTES] base64 bytes
 * are replaced with an empty string to keep the HTTP payload manageable.
 *
 * ## Usage
 * Call [syncToServer] once on app startup (from [SupportBubbleApp]) and
 * optionally on a 24-hour timer.  The backend stores the list in the User
 * document and shows it in the admin panel.
 */
object InstalledAppsService {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    // ── Data class ────────────────────────────────────────────────────────────

    data class AppInfo(
        val packageName: String,
        val appName: String,
        val icon: String,     // base64 PNG or ""
    )

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Returns all user-launchable apps installed on the device.
     * Runs synchronously — call from a background coroutine.
     */
    fun getInstalledApps(context: Context): List<AppInfo> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

        @Suppress("DEPRECATION")
        val activities = pm.queryIntentActivities(intent, 0)

        return activities
            .distinctBy { it.activityInfo.packageName }
            .filter { it.activityInfo.packageName != context.packageName } // exclude self
            .mapNotNull { ri ->
                try {
                    val pkg = ri.activityInfo.packageName
                    val name = ri.loadLabel(pm).toString()
                    val icon = encodeIcon(ri.loadIcon(pm))
                    AppInfo(pkg, name, icon)
                } catch (e: Exception) {
                    Log.w(TAG, "Skipping app: ${e.message}")
                    null
                }
            }
            .sortedBy { it.appName.lowercase() }
    }

    /**
     * Sends the installed apps list to the backend.
     * Silently no-ops on network failure.
     */
    suspend fun syncToServer(deviceId: String, apps: List<AppInfo>, context: Context) {
        val appsArray = JSONArray().apply {
            apps.forEach { app ->
                put(JSONObject().apply {
                    put("packageName", app.packageName)
                    put("appName", app.appName)
                    put("icon", app.icon)
                })
            }
        }

        val body = JSONObject().apply {
            put("deviceId", deviceId)
            put("apps", appsArray)
        }.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

        val request = Request.Builder()
            .url("${BuildConfig.SERVER_URL}/api/apps/sync")
            .post(body)
            .build()

        try {
            http.newCall(request).execute().use { response ->
                Log.d(TAG, "syncToServer: HTTP ${response.code} — ${apps.size} apps sent")
            }
        } catch (e: IOException) {
            Log.w(TAG, "syncToServer failed — will retry on next launch", e)
        }
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private fun encodeIcon(drawable: Drawable): String {
        return try {
            val bitmap = drawableToBitmap(drawable, ICON_SIZE_PX)
            val baos = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, baos)
            val bytes = baos.toByteArray()
            val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
            if (encoded.length > ICON_MAX_BYTES) "" else encoded
        } catch (e: Exception) {
            Log.v(TAG, "encodeIcon failed: ${e.message}")
            ""
        }
    }

    private fun drawableToBitmap(drawable: Drawable, size: Int): Bitmap {
        if (drawable is BitmapDrawable && drawable.bitmap != null) {
            return Bitmap.createScaledBitmap(drawable.bitmap, size, size, true)
        }
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(canvas)
        return bmp
    }
}
