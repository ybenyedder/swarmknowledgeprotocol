package com.swarmknowledge.ospbridge

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.tree4five.gguf.IEmbedCallback
import com.tree4five.gguf.ILLMCallback
import com.tree4five.gguf.ILLMService
import com.swarmknowledge.osp.Q8Codec
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Client of the LLMProvider app (com.tree4five.gguf). Binds its exported
 * `LLMInferenceService` (AIDL, action ACTION_LLM_SERVICE) and exposes the calls
 * OSP needs: prompt generation and text embedding (v1.1.0 embeddings pipeline,
 * q8 wire layout decoded by Q8Codec).
 *
 * AIDL callbacks arrive on binder threads; this class converts them into
 * blocking calls for the OSP worker threads. LLMProvider is an on-device
 * engine — no network, no HTTP — so this bridge only works when both apps run
 * on the same device.
 */
class LlmBridge(private val context: Context) {

    @Volatile private var svc: ILLMService? = null
    @Volatile private var bound = false
    private val lock = Object()

    val connected: Boolean
        get() = svc != null

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            synchronized(lock) {
                svc = ILLMService.Stub.asInterface(binder)
                lock.notifyAll()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            synchronized(lock) { svc = null }
        }
    }

    fun bind(): Boolean {
        val intent = Intent(ACTION_LLM_SERVICE).setPackage(LLMPROVIDER_PACKAGE)
        bound = try {
            context.bindService(intent, conn, Context.BIND_AUTO_CREATE)
        } catch (e: SecurityException) {
            false
        }
        return bound
    }

    fun unbind() {
        if (bound) {
            try {
                context.unbindService(conn)
            } finally {
                bound = false
                svc = null
            }
        }
    }

    private fun awaitService(timeoutS: Long = 10): ILLMService? {
        val deadline = System.currentTimeMillis() + timeoutS * 1000
        synchronized(lock) {
            while (svc == null && System.currentTimeMillis() < deadline) lock.wait(500)
            return svc
        }
    }

    /** Full generation; blocks until onGenerationComplete or timeout.
     *  Slow hardware (emulated CPU, big quants) legitimately needs minutes —
     *  an honest completed answer beats a timeout that would surface as an
     *  RFO "provider failure" at the negotiation layer. */
    fun generate(prompt: String, timeoutS: Long = 600): String {
        val s = awaitService() ?: throw IllegalStateException("LLMProvider service not bound")
        val text = AtomicReference<String?>(null)
        val done = CountDownLatch(1)
        s.generateTextStream(prompt, object : ILLMCallback.Stub() {
            override fun onTokenReceived(token: String?) {
                // streamed tokens; the OSP path waits for the complete text
            }

            override fun onGenerationComplete(fullText: String?) {
                text.set(fullText ?: "")
                done.countDown()
            }
        })
        if (!done.await(timeoutS, TimeUnit.SECONDS)) throw IllegalStateException("generation timed out")
        return text.get() ?: ""
    }

    /** Embeds text via LLMProvider and returns the float vector (q8-decoded). */
    fun embed(text: String, timeoutS: Long = 30): FloatArray {
        val s = awaitService() ?: throw IllegalStateException("LLMProvider service not bound")
        val packed = AtomicReference<ByteArray?>()
        val error = AtomicReference<String?>()
        val done = CountDownLatch(1)
        s.embedText(text, object : IEmbedCallback.Stub() {
            override fun onEmbedding(q8: ByteArray?, dim: Int) {
                packed.set(q8)
                done.countDown()
            }

            override fun onError(message: String?) {
                error.set(message)
                done.countDown()
            }
        })
        if (!done.await(timeoutS, TimeUnit.SECONDS)) throw IllegalStateException("embed timed out")
        error.get()?.let { throw IllegalStateException("LLMProvider embed error: $it") }
        return Q8Codec.decodeOne(packed.get() ?: ByteArray(0))
    }

    /** Dimension of the model's embedding space, or -1 when unavailable. */
    fun embeddingDim(): Int = svc?.embeddingDim ?: -1

    /** Context window of the loaded model, or -1 when no model is loaded. */
    fun contextLength(): Int = svc?.contextLength ?: -1

    fun providerVersion(): String? = svc?.version

    companion object {
        const val LLMPROVIDER_PACKAGE = "com.tree4five.gguf"
        const val ACTION_LLM_SERVICE = "com.tree4five.gguf.ACTION_LLM_SERVICE"
    }
}
