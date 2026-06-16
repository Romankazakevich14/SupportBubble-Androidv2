package com.supportbubble.app.services

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.supportbubble.app.AppState
import com.supportbubble.app.socket.AppSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAG = "AppMonitorService"

/**
 * Debounce window: wait this many ms after the last event before treating
 * the change as a stable foreground app switch. This filters out transient
 * system-overlay windows that flash between apps.
 */
private const val DEBOUNCE_MS = 800L

/**
 * System-owned packages whose window changes we always ignore.
 * Also filtered: packages that start with "android." (framework windows).
 */
private val IGNORED_PACKAGES = setOf(
    "com.android.systemui",
    "com.android.launcher",
    "com.android.launcher2",
    "com.android.launcher3",
    "com.google.android.apps.nexuslauncher",
    "com.google.android.apps.taskbar",
    "com.huawei.android.launcher",
    "com.miui.home",
    "com.sec.android.app.launcher",
    "com.oppo.launcher",
    "com.vivo.launcher",
    "com.oneplus.launcher",
    "com.android.inputmethod.latin",
    "com.google.android.inputmethod.latin",
    "com.samsung.android.app.spage",
    "com.android.settings",
)

class AppMonitorService : AccessibilityService() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var debounceJob: Job? = null

    /** Last confirmed foreground package (after debounce). */
    private var currentApp: String = ""

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = serviceInfo?.also { info ->
            info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            info.notificationTimeout = 100
            info.flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        }

        // Initialise the shared socket so this service can emit events
        AppSocket.init(applicationContext)

        Log.d(TAG, "AppMonitorService connected")
    }

    override fun onInterrupt() {
        Log.d(TAG, "AppMonitorService interrupted")
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
        Log.d(TAG, "AppMonitorService destroyed")
    }

    // ── Event handling ────────────────────────────────────────────────────────

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val pkg = event.packageName?.toString()?.trim() ?: return
        if (pkg.isBlank()) return
        if (pkg == currentApp) return
        if (shouldIgnore(pkg)) return

        // Debounce: cancel any pending job and schedule a new one
        debounceJob?.cancel()
        debounceJob = serviceScope.launch {
            delay(DEBOUNCE_MS)
            handleAppChange(pkg)
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun shouldIgnore(pkg: String): Boolean {
        if (pkg == applicationContext.packageName) return true
        if (pkg.startsWith("android.")) return true
        return IGNORED_PACKAGES.any { pkg.startsWith(it) }
    }

    private fun handleAppChange(packageName: String) {
        currentApp = packageName

        // 1. Notify OverlayService (updates the bubble icon in real time)
        AppState.updateCurrentApp(packageName)

        // 2. Persist locally so DeviceInfo carries the latest value on next launch
        DeviceInfoService.saveLastApp(applicationContext, packageName)

        // 3. Emit to server via Socket.io
        AppSocket.emitAppChange(packageName)

        Log.i(TAG, "Foreground app → $packageName")
    }
}
