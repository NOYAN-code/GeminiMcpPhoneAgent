package com.geminimcp.phoneagent.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

class PhoneAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: PhoneAccessibilityService? = null
            private set

        fun isConnected(): Boolean = instance != null
    }

    @Volatile
    var lastSnapshot: UiSnapshot = UiSnapshot("unknown", "empty", emptyList(), false)
        private set

    private val screenshotExecutor = Executors.newSingleThreadExecutor()

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Lightweight passive hook; live capture is performed on demand for minimum CPU overhead.
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (instance === this) instance = null
        screenshotExecutor.shutdownNow()
        super.onDestroy()
    }

    fun captureUiSnapshot(maxNodes: Int = 120): UiSnapshot {
        val root = rootInActiveWindow
        val snapshot = UiTreeSerializer.capture(root, maxNodes)
        lastSnapshot = snapshot
        return snapshot
    }

    fun getActivePackage(): String {
        return rootInActiveWindow?.packageName?.toString() ?: lastSnapshot.packageName
    }

    suspend fun waitForUiChange(previousHash: String, maxWaitMs: Long = 350L): UiSnapshot {
        val start = System.currentTimeMillis()
        var current = captureUiSnapshot()
        while (current.stateHash == previousHash && System.currentTimeMillis() - start < maxWaitMs) {
            delay(50)
            current = captureUiSnapshot()
        }
        return current
    }

    suspend fun tapCoordinates(x: Float, y: Float, durationMs: Long = 45L): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs.coerceIn(20L, 1500L))
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGestureAwait(gesture)
    }

    suspend fun swipeGesture(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long = 220L
    ): Boolean {
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs.coerceIn(60L, 1500L))
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGestureAwait(gesture)
    }

    private suspend fun dispatchGestureAwait(gesture: GestureDescription): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        val started = dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    deferred.complete(true)
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    deferred.complete(false)
                }
            },
            null
        )
        if (!started) return false
        return withTimeoutOrNull(3000L) { deferred.await() } ?: false
    }

    suspend fun tapNode(nodeId: Int?, text: String?, contentDesc: String?): Pair<Boolean, String> {
        val snapshot = if (nodeId != null && lastSnapshot.nodes.isNotEmpty()) lastSnapshot else captureUiSnapshot()
        val match = findNode(snapshot, nodeId, text, contentDesc)
            ?: findNode(captureUiSnapshot(), nodeId, text, contentDesc)
            ?: return false to "Node not found (nodeId=$nodeId, text=$text, desc=$contentDesc)."

        val ok = tapCoordinates(match.bounds.centerX().toFloat(), match.bounds.centerY().toFloat())
        val label = match.text.ifEmpty { match.desc.ifEmpty { match.cls } }
        return ok to if (ok) "Tapped #${match.id} ($label)." else "Gesture tap cancelled on #${match.id}."
    }

    suspend fun longPressNode(nodeId: Int?, text: String?, x: Int?, y: Int?, durationMs: Long = 650L): Pair<Boolean, String> {
        if (x != null && y != null) {
            val ok = tapCoordinates(x.toFloat(), y.toFloat(), durationMs)
            return ok to if (ok) "Long-pressed ($x, $y)." else "Long-press cancelled."
        }
        val snapshot = captureUiSnapshot()
        val match = findNode(snapshot, nodeId, text, null)
            ?: return false to "Node not found for long press."
        val ok = tapCoordinates(match.bounds.centerX().toFloat(), match.bounds.centerY().toFloat(), durationMs)
        return ok to if (ok) "Long-pressed #${match.id}." else "Long-press cancelled."
    }

    suspend fun typeTextIntoNode(
        text: String,
        nodeId: Int?,
        clearExisting: Boolean = false,
        pressEnter: Boolean = false
    ): Pair<Boolean, String> {
        if (nodeId != null) {
            val snap = if (lastSnapshot.nodes.isNotEmpty()) lastSnapshot else captureUiSnapshot()
            val target = snap.nodes.firstOrNull { it.id == nodeId }
            if (target != null) {
                tapCoordinates(target.bounds.centerX().toFloat(), target.bounds.centerY().toFloat())
                delay(120)
            }
        }

        val root = rootInActiveWindow ?: return false to "No active window."
        var editable = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: findFirstEditable(root)

        if (editable == null) {
            return false to "No editable input field found on screen."
        }

        val finalText = if (clearExisting) {
            text
        } else {
            val existing = editable.text?.toString() ?: ""
            if (existing.isEmpty() || existing == editable.hintText?.toString()) text else text
        }

        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, finalText)
        }
        val setOk = editable.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)

        if (pressEnter) {
            delay(80)
            editable.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        }
        return setOk to if (setOk) "Typed text into input field." else "Failed to set text on input field."
    }

    fun performSystemKey(key: String): Pair<Boolean, String> {
        val action = when (key.lowercase()) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> GLOBAL_ACTION_QUICK_SETTINGS
            "lock_screen" -> GLOBAL_ACTION_LOCK_SCREEN
            "power_dialog" -> GLOBAL_ACTION_POWER_DIALOG
            else -> return false to "Unsupported key '$key'."
        }
        val ok = performGlobalAction(action)
        return ok to if (ok) "Pressed system key '$key'." else "Failed to press '$key'."
    }

    suspend fun captureScreenshotBase64(): Pair<String?, String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return null to "Screenshot requires Android 11+."
        }
        val deferred = CompletableDeferred<Pair<String?, String>>()
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            screenshotExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    try {
                        val hw = Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
                        screenshot.hardwareBuffer.close()
                        if (hw == null) {
                            deferred.complete(null to "Failed to wrap screenshot buffer.")
                            return
                        }
                        val sw = hw.copy(Bitmap.Config.ARGB_8888, false)
                        hw.recycle()
                        val targetW = minOf(sw.width, 540)
                        val targetH = (sw.height * (targetW.toFloat() / sw.width)).toInt().coerceAtLeast(1)
                        val scaled = Bitmap.createScaledBitmap(sw, targetW, targetH, true)
                        sw.recycle()
                        val out = ByteArrayOutputStream()
                        scaled.compress(Bitmap.CompressFormat.JPEG, 70, out)
                        scaled.recycle()
                        val b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
                        deferred.complete(b64 to "Captured screenshot (${targetW}x${targetH}).")
                    } catch (e: Exception) {
                        deferred.complete(null to "Screenshot encode error: ${e.message}")
                    }
                }

                override fun onFailure(errorCode: Int) {
                    deferred.complete(null to "Screenshot failed with errorCode=$errorCode.")
                }
            }
        )
        return withTimeoutOrNull(4000L) { deferred.await() } ?: (null to "Screenshot timed out.")
    }

    private fun findNode(
        snapshot: UiSnapshot,
        nodeId: Int?,
        text: String?,
        contentDesc: String?
    ): IndexedUiNode? {
        if (nodeId != null) {
            snapshot.nodes.firstOrNull { it.id == nodeId }?.let { return it }
        }
        if (!text.isNullOrBlank()) {
            snapshot.nodes.firstOrNull { it.text.equals(text, ignoreCase = true) }?.let { return it }
            snapshot.nodes.firstOrNull { it.text.contains(text, ignoreCase = true) }?.let { return it }
            snapshot.nodes.firstOrNull { it.desc.contains(text, ignoreCase = true) }?.let { return it }
        }
        if (!contentDesc.isNullOrBlank()) {
            snapshot.nodes.firstOrNull { it.desc.equals(contentDesc, ignoreCase = true) }?.let { return it }
            snapshot.nodes.firstOrNull { it.desc.contains(contentDesc, ignoreCase = true) }?.let { return it }
        }
        return null
    }

    private fun findFirstEditable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isEditable && node.isVisibleToUser) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findFirstEditable(child)
            if (found != null) return found
        }
        return null
    }
}
