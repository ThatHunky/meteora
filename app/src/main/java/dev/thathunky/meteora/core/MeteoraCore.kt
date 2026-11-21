package dev.thathunky.meteora.core

import android.util.Log
import dev.thathunky.meteora.stream.StreamException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * The Rust core (`rust/meteora-core`, built into the APK as libmeteora_core.so): stream decisions
 * and the QuickJS signature solver. It does no I/O; Kotlin fetches, Rust decides.
 *
 * Every function takes and returns strings: `{"ok":true,...}` or `{"ok":false,"error":"..."}`.
 * Use [call], which checks the library is there and turns a failure into a [StreamException].
 */
object MeteoraCore {
    private const val TAG = "MeteoraCore"
    private val json = Json { ignoreUnknownKeys = true }

    /** Calling one function as well: a library that loads but lacks the symbols counts as missing. */
    val available: Boolean = try {
        System.loadLibrary("meteora_core")
        ping() != null
    } catch (e: Throwable) {
        // Not only LinkageError: a failing ping must not break this object's initialiser.
        Log.w(TAG, "native core unavailable: ${e.javaClass.simpleName}")
        false
    }

    @JvmStatic external fun ping(): String?
    @JvmStatic external fun pickAudio(playerJson: String, tier: String, allowCipher: Boolean): String?
    @JvmStatic external fun parseVisitorData(body: String): String?
    @JvmStatic external fun parsePlayerPage(html: String): String?
    @JvmStatic external fun signatureTimestamp(js: String): String?
    @JvmStatic external fun streamChallenges(format: String): String?
    @JvmStatic external fun buildStreamUrl(format: String, solvedS: String?, solvedN: String?): String?
    @JvmStatic external fun solverInit(libJs: String, coreJs: String): String?
    @JvmStatic external fun solverHasPlayer(playerId: String): String?
    @JvmStatic external fun solverPrepare(playerId: String, playerJs: String): String?
    @JvmStatic external fun solverSolve(playerId: String, sigJson: String, nJson: String): String?

    /** The start of the error a solve that ran too long ends with. */
    const val TIMED_OUT = "solver timed out"

    /** Runs [block] and returns its result object; [StreamException] if the library is missing or the call failed. */
    fun call(block: MeteoraCore.() -> String?): JsonObject {
        if (!available) throw StreamException("native core unavailable")
        val raw = try {
            block()
        } catch (e: RuntimeException) {
            // A JNI failure inside the library; the message could hold anything, so not passed on.
            throw StreamException("native core: ${e.javaClass.simpleName}")
        } catch (e: LinkageError) {
            throw StreamException("native core: ${e.javaClass.simpleName}")
        } ?: throw StreamException("native core returned nothing")
        val o = runCatching { json.parseToJsonElement(raw) as JsonObject }.getOrNull() ?: throw StreamException("native core: not JSON")
        if ((o["ok"] as? JsonPrimitive)?.booleanOrNull != true) {
            throw StreamException((o["error"] as? JsonPrimitive)?.contentOrNull ?: "native core failed")
        }
        return o
    }
}

/** A string, number or boolean field of a core result, as text; null when absent or null. */
fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
