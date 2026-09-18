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
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.google.android.material.appbar.MaterialToolbar
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
 * The toolbar overflow carries the Language submenu (per-app locale, UI only —
 * the knowledge corpus itself stays in whatever language it was taught in).
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
     *  The "3rd article" chip demonstrates honest abstention: the indexed
     *  proof doesn't number articles, so the swarm must refuse (INSUFFICIENT_
     *  EVIDENCE / firewall) rather than guess one. Localized per UI language. */
    private val examples: List<String> by lazy { resources.getStringArray(R.array.examples).toList() }

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

        setupLanguageMenu(findViewById<MaterialToolbar>(R.id.toolbar))

        status = findViewById(R.id.status)
        learnBox = findViewById(R.id.learnBox)
        queryBox = findViewById(R.id.queryBox)
        out = findViewById(R.id.out)

        // Show a prefix only: the full link secret stays in app-private prefs
        // (run-as / adb for peering), never on screen in a store build.
        OspService.instance?.let { out.text = getString(R.string.token_prefix, it.token.take(6)) }

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
                    out.text = if (ok) getString(R.string.msg_taught, svc.rag.entries.size)
                               else getString(R.string.msg_empty_chunk)
                }
            }.start()
        }

        // Forenseek menu: diagnostics dump, full reset, version — support tools
        findViewById<MaterialButton>(R.id.btnLogAll).setOnClickListener {
            val svc = OspService.instance
            if (svc == null) {
                out.text = getString(R.string.msg_node_not_started)
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
                    out.text = dump + (saved?.let { getString(R.string.msg_saved_to, it) } ?: "")
                }
            }.start()
        }

        findViewById<MaterialButton>(R.id.btnResetAll).setOnClickListener {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.btn_reset_all)
                .setMessage(R.string.reset_message)
                .setPositiveButton(R.string.btn_reset) { _, _ ->
                    OspService.instance?.resetAll()
                    stopService(Intent(this, OspService::class.java))
                    peerUrl.setText("")
                    peerToken.setText("")
                    peerFieldsRestored = false
                    out.text = getString(R.string.msg_reset_done)
                }
                .setNegativeButton(R.string.btn_cancel, null)
                .show()
        }

        findViewById<MaterialButton>(R.id.btnVersion).setOnClickListener {
            val pkg = packageManager.getPackageInfo(packageName, 0)
            val vcode = if (Build.VERSION.SDK_INT >= 28) pkg.longVersionCode
            else @Suppress("DEPRECATION") pkg.versionCode.toLong()
            val s = OspService.instance?.statusMap()
            val nodeLine = (s?.get("node_id")?.toString() ?: getString(R.string.version_node_none))
                .let { id -> s?.get("node_class")?.toString()?.let { cls -> "$id ($cls)" } ?: id }
            val engineLine = when {
                s == null -> "—"
                s["llmprovider_bound"] == true ->
                    getString(R.string.version_engine_bound, s["llmprovider_version"]?.toString() ?: "?")
                else -> getString(R.string.version_engine_unbound)
            }
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(getString(R.string.app_name))
                .setMessage(
                    getString(
                        R.string.version_msg,
                        pkg.versionName ?: "?",
                        vcode.toString(),
                        s?.get("packet_version")?.toString() ?: "0.6",
                        nodeLine,
                        engineLine,
                    )
                )
                .setPositiveButton(R.string.btn_ok, null)
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
                out.text = getString(R.string.msg_peer_url_http)
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
                    getString(R.string.msg_peer_added, nodeId, svc.remotes.size)
                } catch (e: Exception) {
                    getString(R.string.msg_peer_failed, e.message ?: "?")
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
                    if (o == null) getString(R.string.msg_service_not_ready)
                    else getString(R.string.msg_query_result, o.mode, o.groundedness.toString(), o.answer ?: o.detail)
                } catch (e: Exception) {
                    getString(R.string.msg_query_error, e.message ?: "?")
                }
                handler.post { out.text = result }
            }.start()
        }
    }

    /**
     * Toolbar overflow → Language submenu. Empty tag = follow the system
     * locale; otherwise pin the per-app locale. appcompat applies it to all
     * activities (auto-recreate) and, with autoStoreLocales declared in the
     * manifest, survives process death; on API 33+ it is backed by the
     * framework's per-app language settings.
     */
    private fun setupLanguageMenu(toolbar: MaterialToolbar) {
        toolbar.inflateMenu(R.menu.menu_main)
        toolbar.setOnMenuItemClickListener { item ->
            val tag = when (item.itemId) {
                R.id.lang_system -> ""
                R.id.lang_en -> "en"
                R.id.lang_fr -> "fr"
                R.id.lang_zh -> "zh"
                R.id.lang_ar -> "ar"
                else -> return@setOnMenuItemClickListener false
            }
            AppCompatDelegate.setApplicationLocales(
                if (tag.isEmpty()) LocaleListCompat.getEmptyLocaleList()
                else LocaleListCompat.forLanguageTags(tag)
            )
            true
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
