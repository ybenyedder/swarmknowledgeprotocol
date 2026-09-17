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
import com.swarmknowledge.osp.MiniJson
import java.net.URL

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
    private lateinit var peerUrl: TextInputEditText
    private lateinit var peerToken: TextInputEditText

    /** one-shot: peer card fields refilled from the persisted peer table */
    private var peerFieldsRestored = false

    /** Field-tested examples: the "new mail" scenario against the bot peer.
     *  The "3ème article" chip demonstrates honest abstention: the indexed
     *  proof doesn't number articles, so the swarm must refuse (INSUFFICIENT_
     *  EVIDENCE / firewall) rather than guess one. */
    private val examples = listOf(
        "résume le document justificatif de ressources",
        "quelle est la date de l'engagement financier",
        "résume le magazine de robotique reçu",
        "résume le 3ème article du magazine de robotique",
        "que montre la vidéo reçue aujourd'hui",
    )

    private val poll = object : Runnable {
        override fun run() {
            val svc = OspService.instance
            status.text = svc?.statusSummary() ?: getString(R.string.btn_start)
            // Restore the peer card once the service is up: url stays readable,
            // the link secret goes back into the masked field so ADD PEER works
            // without retyping anything after an app restart.
            if (!peerFieldsRestored && svc != null && svc.remotes.isNotEmpty()) {
                val first = svc.remotes.entries.first()
                if (peerUrl.text.isNullOrBlank()) peerUrl.setText(first.value.url)
                if (peerToken.text.isNullOrBlank()) first.value.token?.let { peerToken.setText(it) }
                peerFieldsRestored = true
            }
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

        // Show a prefix only: the full link secret stays in app-private prefs
        // (run-as / adb for peering), never on screen in a store build.
        OspService.instance?.let { out.text = "token: ${it.token.take(6)}…" }

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

        // Forenseek menu: diagnostics dump, full reset, version — support tools
        findViewById<MaterialButton>(R.id.btnLogAll).setOnClickListener {
            val svc = OspService.instance
            if (svc == null) {
                out.text = "node not started — START NODE first"
                return@setOnClickListener
            }
            Thread {
                val dump = svc.dumpInfo()
                val dir = getExternalFilesDir(null) ?: filesDir
                var saved: String? = null
                try {
                    val f = java.io.File(dir, "forenseek_%d.txt".format(System.currentTimeMillis()))
                    f.writeText(dump)
                    saved = f.absolutePath
                    dump.chunked(3000).forEachIndexed { i, part ->
                        android.util.Log.i("forenseek", "[$i] $part")
                    }
                } catch (_: Exception) { }
                handler.post {
                    out.text = dump + (saved?.let { "\n— saved to $it" } ?: "")
                }
            }.start()
        }

        findViewById<MaterialButton>(R.id.btnResetAll).setOnClickListener {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Reset all")
                .setMessage(
                    "Wipe the node identity, link secret, peers and taught " +
                    "knowledge on this device? This cannot be undone."
                )
                .setPositiveButton("Reset") { _, _ ->
                    OspService.instance?.resetAll()
                    stopService(Intent(this, OspService::class.java))
                    peerUrl.setText("")
                    peerToken.setText("")
                    peerFieldsRestored = false
                    out.text = "reset done — identity, peers and knowledge cleared\n" +
                        "START NODE provisions a fresh node"
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        findViewById<MaterialButton>(R.id.btnVersion).setOnClickListener {
            val pkg = packageManager.getPackageInfo(packageName, 0)
            val vcode = if (Build.VERSION.SDK_INT >= 28) pkg.longVersionCode
            else @Suppress("DEPRECATION") pkg.versionCode.toLong()
            val s = OspService.instance?.statusMap()
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(getString(R.string.app_name))
                .setMessage(
                    "App      : v${pkg.versionName} ($vcode)\n" +
                    "Protocol : OSP v${s?.get("packet_version") ?: "0.6"}\n" +
                    "Node     : ${s?.get("node_id") ?: "(not started)"}" +
                    (s?.get("node_class")?.let { " ($it)" } ?: "") + "\n" +
                    "Engine   : " + when {
                        s == null -> "—"
                        s["llmprovider_bound"] == true -> "LLMProvider v${s["llmprovider_version"]}"
                        else -> "LLMProvider not bound"
                    }
                )
                .setPositiveButton("OK", null)
                .show()
        }

        // Peering straight from the UI: resolve the remote's node id from its
        // status endpoint, then merge it (url + link secret) into the persisted
        // peer table — no adb needed on a store install.
        peerUrl = findViewById(R.id.peerUrl)
        peerToken = findViewById(R.id.peerToken)
        findViewById<MaterialButton>(R.id.btnAddPeer).setOnClickListener {
            val svc = OspService.instance ?: return@setOnClickListener
            val url = peerUrl.text.toString().trim().removeSuffix("/")
            if (!url.startsWith("http")) {
                out.text = "peer URL must start with http"
                return@setOnClickListener
            }
            // link secrets are whitespace-free; strip everything so a pasted
            // token can't pick up stray spaces/newlines
            val token = peerToken.text.toString().replace(Regex("\\s"), "")
            Thread {
                val result = try {
                    val status = URL("$url/osp/status").openStream().use { ins ->
                        MiniJson.parse(ins.readBytes().toString(Charsets.UTF_8)) as Map<*, *>
                    }
                    val nodeId = status["node_id"]?.toString()
                        ?: url.substringAfter("//").replace('/', '_')
                    svc.setPeers(svc.remotes + (nodeId to OspService.Peer(url, token.ifEmpty { null })))
                    "peer added: $nodeId (${svc.remotes.size} peers)"
                } catch (e: Exception) {
                    "peer failed: ${e.message}"
                }
                handler.post {
                    out.text = result
                    // keep url + token in the fields: they are persisted in the
                    // peer table and the secret only ever renders masked
                    // (textPassword), so a restart resumes with no retyping
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
