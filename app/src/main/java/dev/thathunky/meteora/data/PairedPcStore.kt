package dev.thathunky.meteora.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The one paired PC (PLAN.md Q5). Keyed by name so more PCs can be added later. */
@Serializable
data class PairedPc(
    val name: String,
    /** Every address that answered `/metadata` at pairing, plus any added later. Probed in [preferredOrder]. */
    val addresses: List<String>,
    val port: Int,
    val token: String,
    /** The address that worked last; tried first. */
    val lastGood: String? = null,
) {
    /** LAN first, Tailscale second, everything else (WSL/Hyper-V adapters) last. */
    fun preferredOrder(): List<String> {
        fun rank(a: String) = when {
            a == lastGood -> 0
            a.startsWith("192.168.") || a.startsWith("10.") -> 1
            a.startsWith("100.") -> 2
            else -> 3
        }
        return addresses.distinct().sortedBy(::rank)
    }
}

private val Context.pcDataStore: DataStore<Preferences> by preferencesDataStore("paired_pc")

@Singleton
class PairedPcStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val secure: SecureStore,
) {
    private val key = stringPreferencesKey("pc_encrypted")
    private val json = Json { ignoreUnknownKeys = true }

    val pc: Flow<PairedPc?> = context.pcDataStore.data.map { prefs ->
        prefs[key]?.let(secure::decrypt)?.let { runCatching { json.decodeFromString<PairedPc>(it) }.getOrNull() }
    }

    suspend fun save(pc: PairedPc) {
        val enc = secure.encrypt(json.encodeToString(PairedPc.serializer(), pc))
        context.pcDataStore.edit { it[key] = enc }
    }

    suspend fun clear() {
        context.pcDataStore.edit { it.remove(key) }
    }
}
