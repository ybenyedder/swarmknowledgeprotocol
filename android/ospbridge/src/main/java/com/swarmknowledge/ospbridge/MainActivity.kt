package com.swarmknowledge.ospbridge

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.textfield.TextInputEditText

/**
 * Status + smoke-test surface for the bridge, in the Tree4Five look and feel
 * (same dark Material theme as the LLMProvider engine): starts/stops the node,
 * shows engine binding and budget, teaches a chunk and runs one verified query.
 * Example chips preload the queries field-tested against the remote
 * whatsapp-bot peer (new-mail verification, see android/README.md).
 */
class MainActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var learnBox: TextInputEditText
    private lateinit var queryBox: TextInputEditText
    private lateinit var out: TextView

    /** Field-tested examples: the "new mail" scenario against the bot peer. */
    private val examples = listOf(
        "résume le document justificatif de ressources",
        "quelle est la date de l'engagement financier",
        "résume le magazine de robotique reçu",
        "que montre la vidéo reçue aujourd'hui",
    )

    private val poll = object : Runnable {
        override fun run() {
            status.text = OspService.instance?.statusSummary()
                ?: getString(R.string.btn_start)
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
        setContentView(R.layout.activity_main)

        status = findViewById(R.id.status)
        learnBox = findViewById(R.id.learnBox)
        queryBox = findViewById(R.id.queryBox)
        out = findViewById(R.id.out)

        OspService.instance?.let { out.text = "token: ${it.token}" }

        findViewById<MaterialButton>(R.id.btnStart).setOnClickListener {
            val i = Intent(this, OspService::class.java)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        }
        findViewById<MaterialButton>(R.id.btnStop).setOnClickListener {
            stopService(Intent(this, OspService::class.java))
        }

        val chips = findViewById<ChipGroup>(R.id.chipGroup)
        for (example in examples) {
            val chip = Chip(this).apply {
                text = example
                isCheckable = false
                setOnClickListener { queryBox.setText(example) }
            }
            chips.addView(chip)
        }

        findViewById<MaterialButton>(R.id.btnTeach).setOnClickListener {
            val svc = OspService.instance ?: return@setOnClickListener
            val text = learnBox.text.toString()
            Thread {
                val ok = svc.teach(text)
                handler.post {
                    out.text = if (ok) "taught · ${svc.rag.entries.size} chunks" else "empty chunk"
                }
            }.start()
        }
        findViewById<MaterialButton>(R.id.btnQuery).setOnClickListener {
            val svc = OspService.instance ?: return@setOnClickListener
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
        }
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
