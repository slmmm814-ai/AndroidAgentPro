package com.ai.agentbridge

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 100, 40, 40)
        }
        val text = TextView(this).apply {
            text = "AgentBridge\nالخطوة 1: افتح الإعدادات وفعّل الخدمة"
            textSize = 18f
        }
        val btn = Button(this).apply {
            text = "فتح إعدادات إمكانية الوصول"
            setOnClickListener {
                startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
        layout.addView(text)
        layout.addView(btn)
        setContentView(layout)
    }
}
