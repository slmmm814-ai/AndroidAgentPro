package com.ai.agentbridge

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.io.File

class AgentService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private val commandFile = File("/sdcard/agent_command.json")
    private val resultFile = File("/sdcard/agent_result.json")

    private val checkRunnable = object : Runnable {
        override fun run() {
            try {
                if (commandFile.exists()) {
                    val text = commandFile.readText()
                    commandFile.delete()
                    val json = JSONObject(text)
                    when (json.getString("action")) {
                        "click" -> {
                            val x = json.getDouble("x")
                            val y = json.getDouble("y")
                            doClick(x, y)
                            writeResult("clicked at $x,$y")
                        }
                        "status" -> writeResult("ready")
                    }
                }
            } catch (e: Exception) {
                Log.e("AgentBridge", "Error", e)
                writeResult("error: ${e.message}")
            }
            handler.postDelayed(this, 300)
        }
    }

    private fun doClick(x: Double, y: Double) {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()
        dispatchGesture(gesture, null, null)
    }

    private fun writeResult(msg: String) {
        try {
            resultFile.writeText(JSONObject().put("result", msg).toString())
        } catch(e: Exception) {}
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        handler.post(checkRunnable)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
}
