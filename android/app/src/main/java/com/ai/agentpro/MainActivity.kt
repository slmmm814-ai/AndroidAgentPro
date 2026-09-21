package com.ai.agentpro

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 64, 48, 48)
        }

        val title = TextView(this).apply {
            text = "AndroidAgentPro"
            textSize = 24f
        }

        val status = TextView(this).apply {
            text = "Phase 0 — Bridge foundation"
            textSize = 16f
        }

        val accessibilityButton = Button(this).apply {
            text = "فتح إعدادات إمكانية الوصول"
            setOnClickListener {
                startActivity(
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                )
            }
        }

        root.addView(title)
        root.addView(status)
        root.addView(accessibilityButton)

        setContentView(root)
    }
}
