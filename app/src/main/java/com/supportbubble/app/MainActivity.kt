package com.supportbubble.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import com.supportbubble.app.services.OverlayService
import com.supportbubble.app.services.SocketForegroundService
import com.supportbubble.app.ui.ChatScreen
import com.supportbubble.app.ui.ChatViewModel
import com.supportbubble.app.ui.theme.SupportBubbleTheme

private const val TAG = "MainActivity"

class MainActivity : ComponentActivity() {

    private val viewModel: ChatViewModel by viewModels()

    // ── POST_NOTIFICATIONS runtime permission (Android 13+) ───────────────────
    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // Start the socket service regardless of whether the user granted
            // the notification permission — the foreground service still runs,
            // just without a visible notification.
            SocketForegroundService.start(this)
            // Request overlay permission next
            ensureOverlayPermission()
        }

    // ── ActivityResult for overlay Settings screen ────────────────────────────
    private val overlayPermissionResult =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            // User returned from Settings — start overlay service if granted
            if (Settings.canDrawOverlays(this)) {
                OverlayService.start(this)
                Log.d(TAG, "Overlay permission granted")
            } else {
                Log.w(TAG, "Overlay permission denied — bubble will not be shown")
            }
        }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            SupportBubbleTheme {
                ChatScreen(viewModel = viewModel)
            }
        }

        ensureSocketServiceRunning()
    }

    override fun onResume() {
        super.onResume()
        // If the user previously granted overlay permission and returns to the app,
        // make sure the service is running (e.g. after a process restart).
        if (Settings.canDrawOverlays(this)) {
            OverlayService.start(this)
        }
    }

    // ── Socket service ────────────────────────────────────────────────────────

    /**
     * Requests POST_NOTIFICATIONS (Android 13+) then starts [SocketForegroundService].
     * On older OS versions starts the service directly.
     */
    private fun ensureSocketServiceRunning() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED

            if (granted) {
                SocketForegroundService.start(this)
                ensureOverlayPermission()
            } else {
                // Permission request result → ensureOverlayPermission() in callback
                requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            SocketForegroundService.start(this)
            ensureOverlayPermission()
        }
    }

    // ── Overlay permission ────────────────────────────────────────────────────

    /**
     * Checks SYSTEM_ALERT_WINDOW permission.
     *   • Granted  → start [OverlayService] immediately.
     *   • Denied   → open the system Settings page so the user can grant it.
     *
     * The result is handled in [overlayPermissionResult] (launched when not granted)
     * and in [onResume] for subsequent app-opens where the user already granted.
     */
    private fun ensureOverlayPermission() {
        if (Settings.canDrawOverlays(this)) {
            OverlayService.start(this)
        } else {
            Log.d(TAG, "Requesting SYSTEM_ALERT_WINDOW — opening Settings")
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName"),
            )
            overlayPermissionResult.launch(intent)
        }
    }
}
