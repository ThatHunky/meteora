package dev.thathunky.meteora.stream

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView
import android.webkit.WebViewClient
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** Challenge → solved value, for the signature (`s`) and the `n` parameter. */
data class Solved(val sig: Map<String, String>, val n: Map<String, String>)

/** Runs YouTube's player JavaScript to solve signature and `n` challenges. An interface so the rest can be tested with a fake. */
interface JsSolver {
    /** [playerJs] is only called when the player is not already loaded. */
    suspend fun solve(playerId: String, playerJs: suspend () -> String, sig: List<String>, n: List<String>): Solved
}

/**
 * yt-dlp's challenge solver (yt-dlp/ejs) in a headless WebView: the JavaScript engine the app
 * already has. The WebView never loads a network page and has no bridge to Kotlin, since it
 * runs YouTube's code.
 *
 * Nothing is kept inside the page between calls. On the OP7T the page lost its globals after
 * every solve (the player's code runs with a real `window` here and appears to replace the
 * document), so each call is one self-contained script: solver, input, answer. The
 * preprocessed player (~4 MB) is kept on the Kotlin side and passed in each time.
 */
@Singleton
class WebViewJsSolver @Inject constructor(@ApplicationContext private val context: Context) : JsSolver {

    private enum class Scripts { YtDlp, Assets }

    private val lock = Mutex()
    // Main thread only.
    private var webView: WebView? = null
    // Under [lock].
    private val scriptCache = HashMap<Scripts, Pair<String, String>>()
    private var preprocessed: Pair<String, String>? = null
    /** The copy that last worked is tried first. */
    @Volatile private var preferred = Scripts.YtDlp

    override suspend fun solve(playerId: String, playerJs: suspend () -> String, sig: List<String>, n: List<String>): Solved = lock.withLock {
        val zip = withContext(Dispatchers.IO) { ytDlpZip().isFile }
        val order = listOf(preferred, if (preferred == Scripts.YtDlp) Scripts.Assets else Scripts.YtDlp)
            .filter { it != Scripts.YtDlp || zip }
        var failure: Exception? = null
        for (scripts in order) {
            val t = SystemClock.elapsedRealtime()
            // withTimeoutOrNull, not withTimeout: a timeout must surface as a StreamException,
            // which the resolver falls back on, not as a cancellation, which it passes up.
            // An IOException (fetching the player) is the network's fault and passes up as is.
            val solved = try {
                withTimeoutOrNull(TIMEOUT_MS) { attempt(scripts, playerId, playerJs, sig, n) }
            } catch (e: StreamException) {
                Log.w(TAG, "solve with $scripts scripts failed: ${e.message}")
                failure = e
                // Start clean for the next try, whichever copy it uses.
                withContext(Dispatchers.Main) { reset() }
                preprocessed = null
                continue
            }
            if (solved == null) {
                // Probably stuck, not broken: start clean next time, and do not wait another 15 s now.
                withContext(Dispatchers.Main) { reset() }
                throw StreamException("solver timed out after $TIMEOUT_MS ms")
            }
            Log.i(TAG, "solved ${sig.size} sig + ${n.size} n for player $playerId with $scripts scripts in ${SystemClock.elapsedRealtime() - t} ms")
            preferred = scripts
            return@withLock solved
        }
        throw failure ?: StreamException("no solver scripts")
    }

    private suspend fun attempt(scripts: Scripts, playerId: String, playerJs: suspend () -> String, sig: List<String>, n: List<String>): Solved {
        val (lib, core) = scriptCache[scripts] ?: withContext(Dispatchers.IO) { read(scripts) }.also { scriptCache[scripts] = it }
        val kept = preprocessed?.takeIf { it.first == playerId }?.second
        val player = if (kept == null) playerJs() else null
        val script = solveScript(lib, core, sig, n, player, kept)
        val raw = withContext(Dispatchers.Main) { evaluate(webView ?: create(), script) }
        val (newPreprocessed, solved) = parseResult(raw, sig, n)
        if (newPreprocessed != null) preprocessed = playerId to newPreprocessed
        return solved
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun create(): WebView {
        val ready = CompletableDeferred<Unit>()
        val view = WebView(context)
        view.settings.apply {
            javaScriptEnabled = true
            blockNetworkLoads = true
            allowFileAccess = false
            allowContentAccess = false
        }
        view.webViewClient = object : WebViewClient() {
            // Only our own page counts: the initial blank document also reports "finished".
            override fun onPageFinished(view: WebView, url: String?) {
                if (url?.startsWith(PAGE_URL) == true) ready.complete(Unit)
            }

            // Without this a renderer crash takes the whole app down; instead, start over next time.
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                Log.w(TAG, "solver renderer gone (crashed: ${detail.didCrash()})")
                if (webView === view) reset() else view.destroy()
                return true
            }
        }
        webView = view
        view.loadDataWithBaseURL(PAGE_URL, "<html></html>", "text/html", "utf-8", null)
        ready.await()
        return view
    }

    /** Main thread. */
    private fun reset() {
        webView?.destroy()
        webView = null
    }

    private suspend fun evaluate(view: WebView, script: String): String = suspendCancellableCoroutine { cont ->
        view.evaluateJavascript(script) { if (cont.isActive) cont.resume(it ?: "null") }
    }

    private fun ytDlpZip() = File(context.noBackupFilesDir, "youtubedl-android/yt-dlp/yt-dlp")

    /** lib.min.js and core.min.js. yt-dlp's own copy updates daily with yt-dlp, so it tracks YouTube's changes. */
    private fun read(scripts: Scripts): Pair<String, String> = try {
        when (scripts) {
            Scripts.YtDlp -> {
                val found = readZipApp(ytDlpZip().readBytes(), setOf(ZIP_LIB, ZIP_CORE))
                val lib = found[ZIP_LIB] ?: throw StreamException("no lib.min.js in yt-dlp")
                val core = found[ZIP_CORE] ?: throw StreamException("no core.min.js in yt-dlp")
                lib to core
            }
            Scripts.Assets -> {
                fun asset(name: String) = context.assets.open("ejs/$name").bufferedReader().use { it.readText() }
                asset("lib.min.js") to asset("core.min.js")
            }
        }
    } catch (e: StreamException) {
        throw e
    } catch (e: Exception) {
        // A local file, so not a network failure: let the other copy be tried.
        throw StreamException("solver scripts unreadable ($scripts): ${e.javaClass.simpleName}")
    }

    companion object {
        private const val TAG = "JsSolver"
        private const val TIMEOUT_MS = 15_000L
        /** A base URL that is ours and loads nothing (network loads are blocked anyway). */
        private const val PAGE_URL = "https://solver.meteora.invalid/"
        private const val ZIP_LIB = "yt_dlp_ejs/yt/solver/lib.min.js"
        private const val ZIP_CORE = "yt_dlp_ejs/yt/solver/core.min.js"
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * yt-dlp is a "zipapp": a `#!` line, then a zip. `ZipFile` refuses the extra bytes in
         * front, so find the first entry and read forward from there.
         */
        fun readZipApp(bytes: ByteArray, names: Set<String>): Map<String, String> {
            var start = -1
            for (i in 0..bytes.size - 4) {
                if (bytes[i] == 'P'.code.toByte() && bytes[i + 1] == 'K'.code.toByte() && bytes[i + 2] == 3.toByte() && bytes[i + 3] == 4.toByte()) {
                    start = i
                    break
                }
            }
            if (start < 0) return emptyMap()
            val found = HashMap<String, String>()
            ZipInputStream(ByteArrayInputStream(bytes, start, bytes.size - start)).use { zip ->
                while (found.size < names.size) {
                    val entry = zip.nextEntry ?: break
                    if (entry.name in names) found[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
                }
            }
            return found
        }

        /**
         * One self-contained solve: the solver scripts, the input, the answer. Every value is put
         * in as a JSON literal. With [player] the player is preprocessed and the preprocessed text
         * comes back as `pre`; otherwise [preprocessed] (from an earlier call) is used. Returns
         * `JSON.stringify({pre, out})`, so the callback gets a JSON string holding JSON.
         */
        fun solveScript(lib: String, core: String, sig: List<String>, n: List<String>, player: String?, preprocessed: String?): String {
            val requests = buildJsonArray {
                if (sig.isNotEmpty()) add(request("sig", sig))
                if (n.isNotEmpty()) add(request("n", n))
            }
            val js = player?.let { Json.encodeToString(String.serializer(), it) } ?: "null"
            val kept = preprocessed?.let { Json.encodeToString(String.serializer(), it) } ?: "null"
            return """(function(){try{
$lib
;Object.assign(globalThis, lib);
$core
;var req=$requests;var js=$js;var kept=$kept;var input;
if(js!==null)input={type:"player",player:js,requests:req,output_preprocessed:true};
else if(kept!==null)input={type:"preprocessed",preprocessed_player:kept,requests:req};
else return JSON.stringify({pre:null,out:{type:"error",error:"no player given"}});
var out=jsc(input);var pre=null;
if(out&&typeof out.preprocessed_player==="string"){pre=out.preprocessed_player;delete out.preprocessed_player;}
return JSON.stringify({pre:pre,out:out});
}catch(e){return JSON.stringify({pre:null,out:{type:"error",error:String(e)}});}})()"""
        }

        private fun request(type: String, challenges: List<String>) = buildJsonObject {
            put("type", type)
            put("challenges", Json.encodeToJsonElement(ListSerializer(String.serializer()), challenges))
        }

        /** `evaluateJavascript`'s raw value → (preprocessed player if one was made, answers). Throws [StreamException] on any error or missing answer. */
        fun parseResult(raw: String, sig: List<String>, n: List<String>): Pair<String?, Solved> {
            val text = runCatching { json.decodeFromString(String.serializer(), raw) }.getOrNull()
                ?: throw StreamException("solver returned nothing")
            val root = runCatching { json.parseToJsonElement(text) as JsonObject }.getOrNull()
                ?: throw StreamException("solver returned no JSON")
            val pre = (root["pre"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val out = root["out"] as? JsonObject ?: throw StreamException("solver returned no result")
            fun failed(o: JsonObject) = (o["type"] as? JsonPrimitive)?.contentOrNull != "result"
            fun error(o: JsonObject) = StreamException("solver: ${clean((o["error"] as? JsonPrimitive)?.contentOrNull, sig + n)}")
            if (failed(out)) throw error(out)
            val responses = (out["responses"] as? JsonArray).orEmpty().map { it as? JsonObject ?: throw StreamException("solver: bad response") }
            val asked = listOfNotNull(sig.takeIf { it.isNotEmpty() }, n.takeIf { it.isNotEmpty() })
            if (responses.size != asked.size) throw StreamException("solver: ${responses.size} responses for ${asked.size} requests")
            val answers = asked.zip(responses).map { (challenges, r) ->
                if (failed(r)) throw error(r)
                val data = r["data"] as? JsonObject ?: throw StreamException("solver: no data")
                challenges.associateWith { c -> (data[c] as? JsonPrimitive)?.contentOrNull ?: throw StreamException("solver left a challenge unsolved") }
            }
            var i = 0
            val sigs = if (sig.isNotEmpty()) answers[i++] else emptyMap()
            val ns = if (n.isNotEmpty()) answers[i] else emptyMap()
            return pre to Solved(sigs, ns)
        }

        /** The error's first line, short, with the challenge values taken out: those must never reach a log. */
        private fun clean(error: String?, challenges: List<String>): String {
            var e = error?.lineSequence()?.firstOrNull().orEmpty().take(200)
            challenges.forEach { if (it.isNotEmpty()) e = e.replace(it, "…") }
            return e.ifBlank { "unknown error" }
        }
    }
}
