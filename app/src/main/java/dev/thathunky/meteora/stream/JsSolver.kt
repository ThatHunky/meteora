package dev.thathunky.meteora.stream

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView
import android.webkit.WebViewClient
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.thathunky.meteora.core.MeteoraCore
import dev.thathunky.meteora.core.text
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
 * The QuickJS solver in the Rust core when it works, else the WebView one. After a native
 * failure the same call is retried on the WebView, and that player stays on the WebView; a new
 * player tries native again. [webView] is only created once it is needed.
 */
class ChoosingJsSolver(
    private val native: JsSolver,
    private val nativeAvailable: () -> Boolean,
    private val webView: () -> JsSolver,
) : JsSolver {
    @Volatile private var nativeFailedFor: String? = null

    override suspend fun solve(playerId: String, playerJs: suspend () -> String, sig: List<String>, n: List<String>): Solved {
        if (nativeAvailable() && nativeFailedFor != playerId) {
            try {
                return native.solve(playerId, playerJs, sig, n)
            } catch (e: StreamException) {
                nativeFailedFor = playerId
                Log.w(TAG, "native solver failed for player $playerId, using the WebView: ${e.message}")
            }
        }
        return webView().solve(playerId, playerJs, sig, n)
    }

    private companion object {
        const val TAG = "JsSolver"
    }
}

/** Where the two solver scripts come from: yt-dlp's own copy on the device (updated daily with yt-dlp), or the app's assets. */
class SolverScripts(private val context: Context) {
    enum class Source { YtDlp, Assets }

    /** The copy that last worked is tried first; the other one after it, if it exists. */
    @Volatile var preferred = Source.YtDlp

    fun order(): List<Source> {
        val zip = ytDlpZip().isFile
        return listOf(preferred, if (preferred == Source.YtDlp) Source.Assets else Source.YtDlp)
            .filter { it != Source.YtDlp || zip }
    }

    private fun ytDlpZip() = File(context.noBackupFilesDir, "youtubedl-android/yt-dlp/yt-dlp")

    /** lib.min.js and core.min.js. */
    fun read(source: Source): Pair<String, String> = try {
        when (source) {
            Source.YtDlp -> {
                val found = readZipApp(ytDlpZip().readBytes(), setOf(ZIP_LIB, ZIP_CORE))
                val lib = found[ZIP_LIB] ?: throw StreamException("no lib.min.js in yt-dlp")
                val core = found[ZIP_CORE] ?: throw StreamException("no core.min.js in yt-dlp")
                lib to core
            }
            Source.Assets -> {
                fun asset(name: String) = context.assets.open("ejs/$name").bufferedReader().use { it.readText() }
                asset("lib.min.js") to asset("core.min.js")
            }
        }
    } catch (e: StreamException) {
        throw e
    } catch (e: Exception) {
        // A local file, so not a network failure: let the other copy be tried.
        throw StreamException("solver scripts unreadable ($source): ${e.javaClass.simpleName}")
    }

    companion object {
        private const val ZIP_LIB = "yt_dlp_ejs/yt/solver/lib.min.js"
        private const val ZIP_CORE = "yt_dlp_ejs/yt/solver/core.min.js"

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
    }
}

/**
 * yt-dlp's challenge solver in QuickJS, inside the Rust core: no WebView and no renderer
 * process. The core keeps the scripts and the preprocessed player loaded between calls, so a
 * call sends only the challenges. The core aborts a solve after 15 s (preprocessing: 45 s).
 */
@Singleton
class NativeJsSolver @Inject constructor(@ApplicationContext context: Context) : JsSolver {
    private val scripts = SolverScripts(context)
    private val lock = Mutex()
    // Under [lock]: which scripts the core's runtime has loaded, null for none.
    private var loaded: SolverScripts.Source? = null

    override suspend fun solve(playerId: String, playerJs: suspend () -> String, sig: List<String>, n: List<String>): Solved = lock.withLock {
        var failure: StreamException? = null
        for (source in withContext(Dispatchers.IO) { scripts.order() }) {
            val t = SystemClock.elapsedRealtime()
            val solved = try {
                attempt(source, playerId, playerJs, sig, n)
            } catch (e: StreamException) {
                Log.w(TAG, "native solve with $source scripts failed: ${e.message}")
                failure = e
                // Start clean for the next try, whichever copy it uses.
                loaded = null
                // Probably stuck, not broken: do not wait the limit out again with the other copy.
                if (e.message.orEmpty().startsWith(MeteoraCore.TIMED_OUT)) throw e
                continue
            }
            Log.i(TAG, "native: solved ${sig.size} sig + ${n.size} n for player $playerId with $source scripts in ${SystemClock.elapsedRealtime() - t} ms")
            scripts.preferred = source
            return@withLock solved
        }
        throw failure ?: StreamException("no solver scripts")
    }

    private suspend fun attempt(source: SolverScripts.Source, playerId: String, playerJs: suspend () -> String, sig: List<String>, n: List<String>): Solved {
        if (loaded != source) {
            val (lib, core) = withContext(Dispatchers.IO) { scripts.read(source) }
            withContext(Dispatchers.IO) { MeteoraCore.call { solverInit(lib, core) } }
            loaded = source
        }
        val has = withContext(Dispatchers.IO) { MeteoraCore.call { solverHasPlayer(playerId) } }.text("has") == "true"
        if (!has) {
            val js = playerJs()
            val r = withContext(Dispatchers.IO) { MeteoraCore.call { solverPrepare(playerId, js) } }
            Log.i(TAG, "native: player $playerId preprocessed in ${r.text("ms")} ms, heap ${mb(r.text("heap"))} MB, peak ${mb(r.text("heap_peak"))} MB")
        }
        if (sig.isEmpty() && n.isEmpty()) return Solved(emptyMap(), emptyMap())
        val sigJson = Json.encodeToString(ListSerializer(String.serializer()), sig)
        val nJson = Json.encodeToString(ListSerializer(String.serializer()), n)
        val r = withContext(Dispatchers.IO) { MeteoraCore.call { solverSolve(playerId, sigJson, nJson) } }
        fun answers(key: String, asked: List<String>): Map<String, String> {
            val data = r[key] as? JsonObject ?: throw StreamException("native solver: no $key answers")
            return asked.associateWith { c -> data.text(c) ?: throw StreamException("solver left a challenge unsolved") }
        }
        return Solved(answers("sig", sig), answers("n", n))
    }

    private fun mb(bytes: String?): String = bytes?.toLongOrNull()?.let { "%.1f".format(it / 1048576.0) } ?: "?"

    private companion object {
        const val TAG = "JsSolver"
    }
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

    private val sources = SolverScripts(context)
    private val lock = Mutex()
    // Main thread only.
    private var webView: WebView? = null
    // Under [lock].
    private val scriptCache = HashMap<SolverScripts.Source, Pair<String, String>>()
    private var preprocessed: Pair<String, String>? = null

    override suspend fun solve(playerId: String, playerJs: suspend () -> String, sig: List<String>, n: List<String>): Solved = lock.withLock {
        val order = withContext(Dispatchers.IO) { sources.order() }
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
            sources.preferred = scripts
            return@withLock solved
        }
        throw failure ?: StreamException("no solver scripts")
    }

    private suspend fun attempt(scripts: SolverScripts.Source, playerId: String, playerJs: suspend () -> String, sig: List<String>, n: List<String>): Solved {
        val (lib, core) = scriptCache[scripts] ?: withContext(Dispatchers.IO) { sources.read(scripts) }.also { scriptCache[scripts] = it }
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

    companion object {
        private const val TAG = "JsSolver"
        private const val TIMEOUT_MS = 15_000L
        /** A base URL that is ours and loads nothing (network loads are blocked anyway). */
        private const val PAGE_URL = "https://solver.meteora.invalid/"
        private val json = Json { ignoreUnknownKeys = true }

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
