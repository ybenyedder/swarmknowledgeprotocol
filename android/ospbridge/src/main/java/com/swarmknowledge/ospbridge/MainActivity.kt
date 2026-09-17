package com.swarmknowledge.ospbridge

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Status + smoke-test surface for the bridge: starts/stops the service, shows
 * engine binding and budget, lets you teach a chunk and run one verified query
 * — enough to validate a device install without adb.
 */
class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var learnBox: EditText
    private lateinit var queryBox: EditText
    private lateinit var out: TextView

    private val poll = object : Runnable {
        override fun run() {
            status.text = OspService.instance?.statusSummary()
                ?: "Service stopped — press Start"
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        title = "OSP Bridge"

        fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        fun button(label: String, action: () -> Unit): Button =
            Button(this).apply { text = label; setOnClickListener { action() } }

        fun field(hint: String): EditText = EditText(this).apply {
            this.hint = hint
            setTextAppearance(android.R.style.TextAppearance_Small)
        }

        status = TextView(this).apply { textSize = 14f; setPadding(0, dp(8), 0, dp(8)) }
        learnBox = field("teach: paste a knowledge chunk…")
        queryBox = field("query: ask the swarm…")
        out = TextView(this).apply {
            textSize = 13f
            setPadding(0, dp(8), 0, dp(8))
            val svc = OspService.instance
            text = svc?.let { "token: ${it.token}" } ?: ""
        }

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        row.addView(button("Start", {
            val i = Intent(this, OspService::class.java)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        }), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(button("Stop", { stopService(Intent(this, OspService::class.java)) }),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        root.addView(status)
        root.addView(row)
        root.addView(learnBox)
        root.addView(button("Teach chunk", {
            val svc = OspService.instance ?: return@button
            val text = learnBox.text.toString()
            Thread {
                val ok = svc.teach(text)
                handler.post { out.text = if (ok) "taught · ${svc.rag.entries.size} chunks" else "empty chunk" }
            }.start()
        }))
        root.addView(queryBox)
        root.addView(button("Run verified query (T0)", {
            val svc = OspService.instance ?: return@button
            val text = queryBox.text.toString()
            Thread {
                val result = try {
                    val o = svc.submitQueryLocal(text, 0)
                    if (o == null) "service not ready"
                    else "mode=${o.mode} groundedness=${o.groundedness}\n\n${o.answer ?: o.detail}"
                } catch (e: Exception) {
                    "error: ${e.message}"
                }
                handler.post { out.text = result }
            }.start()
        }))
        root.addView(out)

        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onStart() {
        super.onStart()
        handler.post(poll)
    }

    override fun onStop() {
        handler.removeCallbacks(poll)
        super.onStop()
    }
}
