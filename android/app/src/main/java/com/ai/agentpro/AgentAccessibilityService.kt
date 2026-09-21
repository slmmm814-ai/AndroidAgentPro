package com.ai.agentpro

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.atomic.AtomicLong

class AgentAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "AndroidAgentPro.Accessibility"
        private const val GESTURE_DURATION_MS = 120L
        private const val MAX_TREE_DEPTH = 80

        @Volatile
        private var instance: AgentAccessibilityService? = null

        fun getInstance(): AgentAccessibilityService? = instance
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val operationCounter = AtomicLong(0L)

    @Volatile
    private var connected = false

    override fun onServiceConnected() {
        super.onServiceConnected()

        instance = this
        connected = true

        ScreenshotEngine.install(this)

        BridgeServer.getInstance(applicationContext).start()

        Log.i(TAG, "Accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) {
            return
        }

        Log.d(
            TAG,
            "event=${event.eventType}, package=${event.packageName}"
        )
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted")
    }

    override fun onDestroy() {
        connected = false

        if (instance === this) {
            instance = null
        }

        ScreenshotEngine.clear(this)

        BridgeServer.getInstance(applicationContext).stop()

        Log.i(TAG, "Accessibility service destroyed")

        super.onDestroy()
    }

    fun isConnected(): Boolean {
        return connected && instance === this
    }

    fun dumpUi(): UiDumpResult {
        if (!isConnected()) {
            return UiDumpResult(
                success = false,
                errorCode = "ACCESSIBILITY_NOT_CONNECTED",
                errorMessage = "Accessibility service is not connected",
                root = null
            )
        }

        return try {
            val root = rootInActiveWindow

            if (root == null) {
                UiDumpResult(
                    success = false,
                    errorCode = "UI_ROOT_UNAVAILABLE",
                    errorMessage = "Active window UI root is unavailable",
                    root = null
                )
            } else {
                UiDumpResult(
                    success = true,
                    errorCode = null,
                    errorMessage = null,
                    root = nodeToData(root, 0, 0)
                )
            }
        } catch (securityException: SecurityException) {
            Log.e(TAG, "Security error during UI dump", securityException)

            UiDumpResult(
                success = false,
                errorCode = "UI_DUMP_SECURITY_ERROR",
                errorMessage = securityException.message
                    ?: "Security error during UI dump",
                root = null
            )
        } catch (exception: Exception) {
            Log.e(TAG, "UI dump failed", exception)

            UiDumpResult(
                success = false,
                errorCode = "UI_DUMP_FAILED",
                errorMessage = exception.message
                    ?: "Unexpected UI dump failure",
                root = null
            )
        }
    }

    fun tap(
        x: Float,
        y: Float,
        callback: (GestureResult) -> Unit
    ) {
        if (!isConnected()) {
            callback(
                GestureResult(
                    false,
                    "ACCESSIBILITY_NOT_CONNECTED",
                    "Accessibility service is not connected",
                    null
                )
            )
            return
        }

        if (!x.isFinite() || !y.isFinite()) {
            callback(
                GestureResult(
                    false,
                    "INVALID_COORDINATES",
                    "Coordinates must be finite",
                    null
                )
            )
            return
        }

        val operationId = operationCounter.incrementAndGet()

        try {
            val path = Path().apply {
                moveTo(x, y)
            }

            val gesture = GestureDescription.Builder()
                .addStroke(
                    GestureDescription.StrokeDescription(
                        path,
                        0L,
                        GESTURE_DURATION_MS
                    )
                )
                .build()

            val dispatched = dispatchGesture(
                gesture,
                object : GestureResultCallback() {

                    override fun onCompleted(
                        completedGesture: GestureDescription?
                    ) {
                        Log.i(
                            TAG,
                            "tap completed id=$operationId x=$x y=$y"
                        )

                        callback(
                            GestureResult(
                                true,
                                null,
                                null,
                                operationId
                            )
                        )
                    }

                    override fun onCancelled(
                        cancelledGesture: GestureDescription?
                    ) {
                        Log.w(
                            TAG,
                            "tap cancelled id=$operationId"
                        )

                        callback(
                            GestureResult(
                                false,
                                "GESTURE_CANCELLED",
                                "Android cancelled the gesture",
                                operationId
                            )
                        )
                    }
                },
                mainHandler
            )

            if (!dispatched) {
                Log.w(
                    TAG,
                    "gesture dispatch rejected id=$operationId"
                )

                callback(
                    GestureResult(
                        false,
                        "GESTURE_DISPATCH_REJECTED",
                        "Android rejected the gesture",
                        operationId
                    )
                )
            }
        } catch (securityException: SecurityException) {
            Log.e(TAG, "Gesture security failure", securityException)

            callback(
                GestureResult(
                    false,
                    "GESTURE_SECURITY_ERROR",
                    securityException.message
                        ?: "Gesture security failure",
                    operationId
                )
            )
        } catch (exception: Exception) {
            Log.e(TAG, "Gesture failure", exception)

            callback(
                GestureResult(
                    false,
                    "GESTURE_FAILED",
                    exception.message ?: "Unexpected gesture failure",
                    operationId
                )
            )
        }
    }

    fun back(callback: (GestureResult) -> Unit) {
        if (!isConnected()) {
            callback(
                GestureResult(
                    false,
                    "ACCESSIBILITY_NOT_CONNECTED",
                    "Accessibility service is not connected",
                    null
                )
            )
            return
        }

        val operationId = operationCounter.incrementAndGet()

        try {
            val performed = performGlobalAction(GLOBAL_ACTION_BACK)

            if (performed) {
                Log.i(TAG, "back dispatched id=$operationId")

                callback(
                    GestureResult(
                        true,
                        null,
                        null,
                        operationId
                    )
                )
            } else {
                callback(
                    GestureResult(
                        false,
                        "BACK_ACTION_REJECTED",
                        "Android rejected the back action",
                        operationId
                    )
                )
            }
        } catch (exception: Exception) {
            Log.e(TAG, "Back action failed", exception)

            callback(
                GestureResult(
                    false,
                    "BACK_ACTION_FAILED",
                    exception.message ?: "Unexpected back action failure",
                    operationId
                )
            )
        }
    }

    private fun nodeToData(
        node: AccessibilityNodeInfo,
        depth: Int,
        index: Int
    ): UiNodeData {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)

        val children = ArrayList<UiNodeData>()

        if (depth < MAX_TREE_DEPTH) {
            for (childIndex in 0 until node.childCount) {
                val child = node.getChild(childIndex) ?: continue

                children.add(
                    nodeToData(
                        child,
                        depth + 1,
                        childIndex
                    )
                )
            }
        }

        return UiNodeData(
            index = index,
            depth = depth,
            className = node.className?.toString(),
            packageName = node.packageName?.toString(),
            viewIdResourceName = node.viewIdResourceName,
            text = node.text?.toString(),
            contentDescription = node.contentDescription?.toString(),
            clickable = node.isClickable,
            enabled = node.isEnabled,
            focusable = node.isFocusable,
            focused = node.isFocused,
            scrollable = node.isScrollable,
            selected = node.isSelected,
            visibleToUser = node.isVisibleToUser,
            boundsLeft = bounds.left,
            boundsTop = bounds.top,
            boundsRight = bounds.right,
            boundsBottom = bounds.bottom,
            children = children
        )
    }

    data class GestureResult(
        val success: Boolean,
        val errorCode: String?,
        val errorMessage: String?,
        val operationId: Long?
    )

    data class UiDumpResult(
        val success: Boolean,
        val errorCode: String?,
        val errorMessage: String?,
        val root: UiNodeData?
    )

    data class UiNodeData(
        val index: Int,
        val depth: Int,
        val className: String?,
        val packageName: String?,
        val viewIdResourceName: String?,
        val text: String?,
        val contentDescription: String?,
        val clickable: Boolean,
        val enabled: Boolean,
        val focusable: Boolean,
        val focused: Boolean,
        val scrollable: Boolean,
        val selected: Boolean,
        val visibleToUser: Boolean,
        val boundsLeft: Int,
        val boundsTop: Int,
        val boundsRight: Int,
        val boundsBottom: Int,
        val children: List<UiNodeData>
    )
}
