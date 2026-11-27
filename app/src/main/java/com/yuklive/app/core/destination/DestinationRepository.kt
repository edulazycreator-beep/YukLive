package com.yuklive.app.core.destination

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.yuklive.app.core.security.CryptoManager
import com.yuklive.app.core.settings.enumOr
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

private val Context.destinationStore: DataStore<Preferences> by preferencesDataStore(name = "destinations")

data class DestinationState(
    val destinations: List<Destination> = emptyList(),
    val selectedId: String? = null,
) {
    val selected: Destination? get() = destinations.firstOrNull { it.id == selectedId } ?: destinations.firstOrNull()
}

/**
 * Menyimpan tujuan stream. Stream key disimpan terenkripsi (AES-GCM, kunci di Android Keystore).
 */
@Singleton
class DestinationRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val crypto: CryptoManager,
) {
    private val listKey = stringPreferencesKey("list_json")
    private val selectedKey = stringPreferencesKey("selected_id")

    val state: Flow<DestinationState> = context.destinationStore.data
        .map { prefs ->
            DestinationState(
                destinations = decode(prefs[listKey]),
                selectedId = prefs[selectedKey],
            )
        }
        .flowOn(Dispatchers.Default)

    suspend fun current(): DestinationState = state.first()

    suspend fun save(destination: Destination) {
        context.destinationStore.edit { prefs ->
            val list = decode(prefs[listKey]).toMutableList()
            val index = list.indexOfFirst { it.id == destination.id }
            if (index >= 0) list[index] = destination else list += destination
            prefs[listKey] = encode(list)
            if (prefs[selectedKey] == null) prefs[selectedKey] = destination.id
        }
    }

    suspend fun delete(id: String) {
        context.destinationStore.edit { prefs ->
            val list = decode(prefs[listKey]).filterNot { it.id == id }
            prefs[listKey] = encode(list)
            if (prefs[selectedKey] == id) {
                list.firstOrNull()?.let { prefs[selectedKey] = it.id } ?: prefs.remove(selectedKey)
            }
        }
    }

    suspend fun select(id: String) {
        context.destinationStore.edit { it[selectedKey] = id }
    }

    private fun encode(list: List<Destination>): String = JSONArray().apply {
        list.forEach { d ->
            put(
                JSONObject()
                    .put("id", d.id)
                    .put("platform", d.platform.name)
                    .put("name", d.name)
                    .put("server", d.server)
                    .put("key", crypto.encrypt(d.streamKey)),
            )
        }
    }.toString()

    private fun decode(json: String?): List<Destination> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Destination(
                    id = o.getString("id"),
                    platform = enumOr(o.optString("platform"), Platform.CustomRtmp),
                    name = o.optString("name"),
                    server = o.optString("server"),
                    streamKey = crypto.decrypt(o.optString("key")),
                )
            }
        }.getOrDefault(emptyList())
    }
}
