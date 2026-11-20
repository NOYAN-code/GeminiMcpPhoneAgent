package com.geminimcp.phoneagent.bridge

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min

class PhoneAccessibilityService : AccessibilityService() {

    private val lastUiEventTimestamp = AtomicLong(0L)

    @Volatile
    var currentPackageName: String = "unknown"
        private set

    @Volatile
    var lastSnapshot: UiTreeSnapshot? = null
        private set

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        event.packageName?.toString()?.let { pkg ->
            if (pkg.isNotBlank()) currentPackageName = pkg
        }
        lastUiEventTimestamp.set(System.currentTimeMillis())
    }

    override fun onInterrupt() {
        // No-op; service remains bound
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    fun getLastUiEventMs(): Long = lastUiEventTimestamp.get()

    fun captureUiTree(maxNodes: Int = 110): UiTreeSnapshot {
        val root = rootInActiveWindow
        val snapshot = UiTreeSerializer.serialize(root, maxNodes)
        if (snapshot.packageName != "unknown") {
            currentPackageName = snapshot.packageName
        }
        lastSnapshot = snapshot
        return snapshot
    }

    fun getScreenDimensions(): Pair<Int, Int> {
        val metrics = resources.displayMetrics
        return metrics.widthPixels to metrics.heightPixels
    }

    suspend fun tapCoordinates(x: Float, y: Float, durationMs: Long = 80L): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs.coerceIn(40L, 3000L))
        return dispatchSingleGesture(GestureDescription.Builder().addStroke(stroke).build())
    }

    suspend fun swipeCoordinates(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long = 320L
    ): Boolean {
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs.coerceIn(100L, 2000L))
        return dispatchSingleGesture(GestureDescription.Builder().addStroke(stroke).build())
    }

    suspend fun scrollDirection(direction: String): Boolean {
        val root = rootInActiveWindow
        if (root != null) {
            val scrollable = findFirstScrollable(root)
            if (scrollable != null) {
                val action = when (direction.lowercase()) {
                    "down", "forward", "right" -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                    else -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                }
                val performed = scrollable.performAction(action)
                scrollable.recycle()
                if (performed) return true
            }
        }
        // Fallback to physical swipe gesture
        val (w, h) = getScreenDimensions()
        val cx = w * 0.5f
        return when (direction.lowercase()) {
            "down", "forward" -> swipeCoordinates(cx, h * 0.78f, cx, h * 0.25f, 350L)
            "up", "backward" -> swipeCoordinates(cx, h * 0.25f, cx, h * 0.78f, 350L)
            "left" -> swipeCoordinates(w * 0.85f, h * 0.5f, w * 0.15f, h * 0.5f, 320L)
            "right" -> swipeCoordinates(w * 0.15f, h * 0.5f, w * 0.85f, h * 0.5f, 320L)
            else -> swipeCoordinates(cx, h * 0.78f, cx, h * 0.25f, 350L)
        }
    }

    suspend fun typeTextIntoNode(
        targetNode: UiNodeSnapshot?,
        text: String,
        clearExisting: Boolean = false,
        pressEnter: Boolean = false
    ): Boolean {
        if (targetNode != null) {
            tapCoordinates(targetNode.center[0].toFloat(), targetNode.center[1].toFloat())
            kotlinx.coroutines.delay(180L)
        }

        val root = rootInActiveWindow ?: return false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: targetNode?.let { findNodeByBounds(root, it.bounds) }
            ?: findFirstEditable(root)
            ?: return false

        return try {
            val finalText = if (clearExisting) text else {
                val existing = focused.text?.toString().orEmpty()
                if (existing.isEmpty() || existing == focused.hintText?.toString()) text else "$existing$text"
            }
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, finalText)
            }
            var success = focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)

            if (!success) {
                // Clipboard paste fallback for WebViews / Flutter / custom editors
                withContext(Dispatchers.Main) {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("agent_input", text))
                }
                success = focused.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            }

            if (success && pressEnter && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                focused.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            }
            success
        } finally {
            focused.recycle()
        }
    }

    fun performGlobalKey(key: String): Boolean {
        val action = when (key.lowercase()) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents", "overview" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> GLOBAL_ACTION_QUICK_SETTINGS
            "power_dialog" -> GLOBAL_ACTION_POWER_DIALOG
            "lock_screen" -> GLOBAL_ACTION_LOCK_SCREEN
            else -> return false
        }
        return performGlobalAction(action)
    }

    suspend fun takeScreenshotBase64Jpeg(maxDimension: Int = 768, jpegQuality: Int = 65): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val deferred = CompletableDeferred<String?>()

        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    try {
                        val hwBuffer = screenshot.hardwareBuffer
                        val colorSpace = screenshot.colorSpace
                        val rawBitmap = Bitmap.wrapHardwareBuffer(hwBuffer, colorSpace)
                        hwBuffer.close()
                        if (rawBitmap == null) {
                            deferred.complete(null)
                            return
                        }
                        val swBitmap = rawBitmap.copy(Bitmap.Config.ARGB_8888, false)
                        rawBitmap.recycle()

                        val scale = min(
                            1f,
                            maxDimension.toFloat() / maxOf(swBitmap.width, swBitmap.height).toFloat()
                        )
                        val finalBitmap = if (scale < 1f) {
                            Bitmap.createScaledBitmap(
                                swBitmap,
                                (swBitmap.width * scale).toInt().coerceAtLeast(1),
                                (swBitmap.height * scale).toInt().coerceAtLeast(1),
                                true
                            ).also { swBitmap.recycle() }
                        } else {
                            swBitmap
                        }

                        val out = ByteArrayOutputStream()
                        finalBitmap.compress(Bitmap.CompressFormat.JPEG, jpegQuality.coerceIn(30, 90), out)
                        finalBitmap.recycle()
                        deferred.complete(Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP))
                    } catch (_: Exception) {
                        deferred.complete(null)
                    }
                }

                override fun onFailure(errorCode: Int) {
                    deferred.complete(null)
                }
            }
        )

        return withTimeoutOrNull(4_000L) { deferred.await() }
    }

    private suspend fun dispatchSingleGesture(gesture: GestureDescription): Boolean {
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
        return withTimeoutOrNull(3_500L) { deferred.await() } ?: false
    }

    private fun findFirstScrollable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            if (cur.isScrollable && cur.isVisibleToUser) return cur
            for (i in 0 until cur.childCount) {
                cur.getChild(i)?.let { queue.add(it) }
            }
            if (cur !== root) cur.recycle()
        }
        return null
    }

    private fun findFirstEditable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            if (cur.isEditable && cur.isVisibleToUser) return cur
            for (i in 0 until cur.childCount) {
                cur.getChild(i)?.let { queue.add(it) }
            }
            if (cur !== root) cur.recycle()
        }
        return null
    }

    private fun findNodeByBounds(root: AccessibilityNodeInfo, targetBounds: List<Int>): AccessibilityNodeInfo? {
        if (targetBounds.size != 4) return null
        val rect = Rect()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            cur.getBoundsInScreen(rect)
            if (rect.left == targetBounds[0] && rect.top == targetBounds[1] &&
                rect.right == targetBounds[2] && rect.bottom == targetBounds[3]
            ) {
                return cur
            }
            for (i in 0 until cur.childCount) {
                cur.getChild(i)?.let { queue.add(it) }
            }
            if (cur !== root) cur.recycle()
        }
        return null
    }

    companion object {
        @Volatile
        var instance: PhoneAccessibilityService? = null
            private set

        fun isRunning(): Boolean = instance != null
    }
}
