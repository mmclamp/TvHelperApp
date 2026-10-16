package com.tvhelper.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "tvhelper")

/**
 * 本地持久化：Pin 到首页的电视应用（保序）、最近连接与历史电视设备。
 */
class SettingsStore(private val context: Context) {

    companion object {
        private val KEY_PINNED = stringPreferencesKey("pinned_apps")
        private val KEY_LAST_DEVICE = stringPreferencesKey("last_device")
        private val KEY_KNOWN_DEVICES = stringPreferencesKey("known_devices")
        private val KEY_AUTO_CONNECT = booleanPreferencesKey("auto_connect")
        private val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
    }

    /** 主题模式 */
    enum class ThemeMode(val label: String) {
        SYSTEM("跟随系统"),
        LIGHT("浅色"),
        DARK("深色"),
    }

    /** 主题模式（默认跟随系统） */
    val themeMode: Flow<ThemeMode> = context.dataStore.data.map { prefs ->
        runCatching {
            ThemeMode.valueOf(prefs[KEY_THEME_MODE] ?: ThemeMode.SYSTEM.name)
        }.getOrDefault(ThemeMode.SYSTEM)
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { it[KEY_THEME_MODE] = mode.name }
    }

    /** 自动连接上次连接过的电视（默认开） */
    val autoConnect: Flow<Boolean> =
        context.dataStore.data.map { it[KEY_AUTO_CONNECT] ?: true }

    suspend fun setAutoConnect(enabled: Boolean) {
        context.dataStore.edit { it[KEY_AUTO_CONNECT] = enabled }
    }

    suspend fun autoConnectOnce(): Boolean =
        context.dataStore.data.first()[KEY_AUTO_CONNECT] ?: true

    val pinnedApps: Flow<List<PinnedApp>> =
        context.dataStore.data.map { decodePinned(it[KEY_PINNED]) }

    val lastDevice: Flow<TvDevice?> =
        context.dataStore.data.map { prefs ->
            prefs[KEY_LAST_DEVICE]?.let { runCatching { decodeDevice(JSONObject(it)) }.getOrNull() }
        }

    val knownDevices: Flow<List<TvDevice>> =
        context.dataStore.data.map { prefs -> decodeDevices(prefs[KEY_KNOWN_DEVICES]) }

    suspend fun setPinned(apps: List<PinnedApp>) {
        val arr = JSONArray()
        apps.forEach { p ->
            arr.put(
                JSONObject().apply {
                    put("pkg", p.packageName)
                    p.label?.let { l -> put("label", l) }
                    p.className?.let { c -> put("cls", c) }
                }
            )
        }
        context.dataStore.edit { it[KEY_PINNED] = arr.toString() }
    }

    /** 返回 true 表示本次是「新增 Pin」，false 表示取消 Pin */
    suspend fun togglePin(app: PinnedApp): Boolean {
        val current = decodePinned(context.dataStore.data.first()[KEY_PINNED]).toMutableList()
        val idx = current.indexOfFirst { it.packageName == app.packageName }
        val added = idx < 0
        if (added) current.add(app) else current.removeAt(idx)
        setPinned(current)
        return added
    }

    suspend fun movePin(from: Int, to: Int) {
        val current = decodePinned(context.dataStore.data.first()[KEY_PINNED]).toMutableList()
        if (from !in current.indices || to !in current.indices || from == to) return
        val item = current.removeAt(from)
        current.add(to, item)
        setPinned(current)
    }

    suspend fun setLastDevice(device: TvDevice) {
        context.dataStore.edit { it[KEY_LAST_DEVICE] = encodeDevice(device).toString() }
        val known = decodeDevices(context.dataStore.data.first()[KEY_KNOWN_DEVICES])
            .filterNot { it.id == device.id }
            .toMutableList()
        known.add(0, device)
        val arr = JSONArray()
        known.take(10).forEach { arr.put(encodeDevice(it)) }
        context.dataStore.edit { it[KEY_KNOWN_DEVICES] = arr.toString() }
    }

    suspend fun forgetAllDevices() {
        context.dataStore.edit {
            it.remove(KEY_LAST_DEVICE)
            it.remove(KEY_KNOWN_DEVICES)
        }
    }

    // ------------------------------------------------------------ codec

    private fun encodeDevice(d: TvDevice) = JSONObject().apply {
        put("id", d.id)
        put("name", d.name)
        put("host", d.host)
        put("rcPort", d.rcPort)
        put("udtPort", d.udtPort)
        d.model?.let { m -> put("model", m) }
    }

    private fun decodeDevice(o: JSONObject) = TvDevice(
        id = o.optString("id", o.optString("host", "")),
        name = o.optString("name", ""),
        host = o.optString("host", ""),
        rcPort = o.optInt("rcPort", 30612),
        udtPort = o.optInt("udtPort", 6093),
        model = o.optString("model", "").takeIf { it.isNotEmpty() },
    )

    private fun decodeDevices(raw: String?): List<TvDevice> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { decodeDevice(it) } }
        }.getOrDefault(emptyList())
    }

    private fun decodePinned(raw: String?): List<PinnedApp> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val pkg = o.optString("pkg", "")
                if (pkg.isEmpty()) null
                else PinnedApp(
                    pkg,
                    o.optString("label", "").takeIf { it.isNotEmpty() },
                    o.optString("cls", "").takeIf { it.isNotEmpty() },
                )
            }
        }.getOrDefault(emptyList())
    }
}
